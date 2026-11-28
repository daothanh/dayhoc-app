package vn.dayhoc.app.teacher.attendance

import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.apiCall

sealed interface SyncEvent {
    /** Lô đã gửi xong (applied/duplicate/stale): T03 tải lại. */
    data class Synced(val sessionId: String) : SyncEvent

    /** Lô bị bỏ vì lỗi không thử lại được ([code] theo `error_codes.json`, hoặc `MAX_ATTEMPTS`). */
    data class Dropped(val sessionId: String, val code: String) : SyncEvent
}

enum class FlushResult {
    /** Outbox đã trống. */
    Done,

    /** Còn lô chưa gửi được (mất mạng, 5xx): hẹn thử lại. */
    Retry,

    /** Đang có lần flush khác chạy. */
    Busy,
}

/**
 * Gửi lại outbox theo thứ tự tạo, dùng đúng `Idempotency-Key` và `clientMutationId` đã lưu nên gửi trùng không tạo
 * bản ghi trùng (US-3.5 AC2). Chỉ một lần flush chạy tại một thời điểm.
 */
class AttendanceSyncEngine(
    private val api: AttendanceApi,
    private val outbox: OutboxStore,
    private val device: DeviceIdProvider,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val mutex = Mutex()
    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    suspend fun flush(): FlushResult {
        if (!mutex.tryLock()) return FlushResult.Busy
        try {
            val deviceId = device.get()
            for (batch in outbox.all()) {
                try {
                    val request = SyncRequestDto(
                        deviceId, formatIso(clock.instant(), clock.zone), batch.sessionId, batch.markSessionTaught,
                        batch.mutations.map { MutationDto(it.clientMutationId, it.studentId, it.status, it.note, it.clientUpdatedAt) },
                    )
                    val result = apiCall { api.sync(batch.idempotencyKey, request) }
                    outbox.remove(batch.idempotencyKey)
                    _events.emit(
                        when (val v = verdictOf(result.results)) {
                            BatchVerdict.Done -> SyncEvent.Synced(batch.sessionId)
                            is BatchVerdict.Rejected -> SyncEvent.Dropped(batch.sessionId, v.code)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    when (val d = decideOnError(e, batch.attempts)) {
                        is ErrorDecision.Drop -> {
                            outbox.remove(batch.idempotencyKey)
                            _events.emit(SyncEvent.Dropped(batch.sessionId, d.code))
                        }
                        is ErrorDecision.Keep -> {
                            if (d.countAttempt) outbox.addAttempt(batch.idempotencyKey)
                            // Giữ thứ tự: lô sau không được vượt lô trước.
                            return FlushResult.Retry
                        }
                    }
                }
            }
            return FlushResult.Done
        } finally {
            mutex.unlock()
        }
    }
}
