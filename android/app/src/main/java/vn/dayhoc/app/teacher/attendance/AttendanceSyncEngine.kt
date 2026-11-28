package vn.dayhoc.app.teacher.attendance

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.apiCall

sealed interface SyncEvent {
    /** Lô đã gửi xong (applied/duplicate/stale): T03 tải lại. [rejected] = số học sinh server không ghi được (từng phần tử). */
    data class Synced(val sessionId: String, val rejected: Int = 0) : SyncEvent

    /** Lô bị xóa vì lỗi không còn ý nghĩa (buổi đã hủy, quá hạn sửa, không thấy buổi); [code] theo `error_codes.json`. */
    data class Dropped(val sessionId: String, val code: String) : SyncEvent
}

enum class FlushResult {
    /** Không còn lô chờ gửi (lô lỗi vĩnh viễn không tính). */
    Done,

    /** Còn lô chưa gửi được (mất mạng, 5xx, lỗi bất ngờ): hẹn thử lại. */
    Retry,
}

/** Kết quả của một lô trong lần flush, để T04 biết lô vừa lưu đi tới đâu. */
sealed interface BatchOutcome {
    data class Synced(val result: SyncResultDto) : BatchOutcome

    /** Còn trong outbox: [network] = mất mạng/hết giờ; ngược lại là lỗi server (5xx…). */
    data class Kept(val network: Boolean) : BatchOutcome

    /** Server từ chối vĩnh viễn, lô giữ lại với trạng thái `failed`. */
    data class Failed(val code: String) : BatchOutcome

    /** Lô bị xóa (buổi đã hủy, quá hạn sửa, không thấy buổi); [result] có khi server trả 200 kèm phần tử `rejected`. */
    data class Deleted(val code: String, val result: SyncResultDto? = null) : BatchOutcome
}

/** [outcomes] theo `Idempotency-Key`. */
data class FlushReport(val result: FlushResult, val outcomes: Map<String, BatchOutcome> = emptyMap())

/**
 * Gửi outbox theo thứ tự tạo, dùng đúng `Idempotency-Key` và body đã lưu nên gửi trùng không tạo bản ghi trùng
 * (US-3.5 AC2). Một lần flush chạy tại một thời điểm; lần gọi đến lúc đang bận xếp hàng chờ rồi chạy lại (đọc lại outbox),
 * nên lô vừa vào outbox không bao giờ bị bỏ sót. Lô lỗi chặn các lô sau của cùng buổi, không chặn buổi khác.
 * Chỉ gửi lô của chủ hiện tại ([owner]).
 */
class AttendanceSyncEngine(
    private val api: AttendanceApi,
    private val outbox: OutboxStore,
    private val roster: RosterCache,
    private val owner: () -> OutboxOwner?,
) {
    private val mutex = Mutex()
    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    /** [own]: khóa lô mà T04 vừa lưu và đang chờ kết quả; thông báo "bỏ lô" của lô đó do T04 tự hiện. */
    suspend fun flush(own: String? = null): FlushReport = mutex.withLock { flushLocked(own) }

    /** Ghi outbox rồi gửi trong cùng một lần giữ khóa: không lần flush nào (hay đăng xuất) chen vào giữa. */
    suspend fun enqueueAndFlush(batch: OutboxBatch): FlushReport = mutex.withLock {
        outbox.enqueue(batch)
        flushLocked(batch.idempotencyKey)
    }

    /** Chỉ ghi outbox (ngoại tuyến), vẫn trong khóa của engine. */
    suspend fun enqueue(batch: OutboxBatch) = mutex.withLock { outbox.enqueue(batch) }

    /** Chạy [block] khi không có flush nào đang chạy và chặn flush mới trong lúc đó (đăng xuất xóa CSDL). */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    private suspend fun flushLocked(own: String?): FlushReport {
        // Chưa có chủ (chưa gắn, hết phiên, đã đăng xuất): không đụng CSDL, không gửi gì.
        val me = owner() ?: return FlushReport(FlushResult.Done)
        val outcomes = HashMap<String, BatchOutcome>()
        try {
            val batches = outbox.all().filter { it.owner == me }
            val blocked = batches.filter { it.state == OutboxState.Failed }.map { it.sessionId }.toMutableSet()
            var retry = false
            for (b in batches) {
                if (b.state != OutboxState.Pending || b.sessionId in blocked) continue
                // Trước mỗi lần gửi: chủ không đổi và lô còn trong outbox (T04 có thể đã bỏ nó).
                if (owner() != me) return FlushReport(FlushResult.Done, outcomes)
                if (outbox.all().none { it.idempotencyKey == b.idempotencyKey }) continue
                try {
                    val result = apiCall { api.sync(b.idempotencyKey, b.request()) }
                    when (val v = verdictOf(result.results)) {
                        BatchVerdict.Done -> {
                            // Lỗi DB sau 200 không tính lần thử và giữ lô pending: gửi lại cùng khóa sẽ ra `duplicate`.
                            roster.removeRoster(b.sessionId)
                            outbox.remove(b.idempotencyKey)
                            outcomes[b.idempotencyKey] = BatchOutcome.Synced(result)
                            _events.emit(SyncEvent.Synced(b.sessionId, rejectedStudentIds(result.results).size))
                        }
                        is BatchVerdict.Rejected -> terminal(b, v.code, result, own, outcomes, blocked)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    when (val d = decideOnError(e, b.attempts)) {
                        is ErrorDecision.Drop -> terminal(b, d.code, null, own, outcomes, blocked)
                        is ErrorDecision.Keep -> {
                            if (d.countAttempt) outbox.addAttempt(b.idempotencyKey)
                            retry = true
                            outcomes[b.idempotencyKey] = BatchOutcome.Kept(network = !d.countAttempt)
                            // Mất mạng/401/429: mọi lô sau cũng sẽ lỗi, dừng và hẹn lại; lỗi server thì chỉ chặn buổi này.
                            if (!d.countAttempt) return FlushReport(FlushResult.Retry, outcomes)
                            blocked += b.sessionId
                        }
                    }
                }
            }
            return FlushReport(if (retry) FlushResult.Retry else FlushResult.Done, outcomes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Đọc/ghi outbox hay giải mã lỗi bất ngờ: không làm sập tiến trình, hẹn thử lại.
            return FlushReport(FlushResult.Retry, outcomes)
        }
    }

    private suspend fun terminal(
        b: OutboxBatch,
        code: String,
        result: SyncResultDto?,
        own: String?,
        outcomes: MutableMap<String, BatchOutcome>,
        blocked: MutableSet<String>,
    ) {
        if (isDeletableError(code)) {
            roster.removeRoster(b.sessionId)
            outbox.remove(b.idempotencyKey)
            outcomes[b.idempotencyKey] = BatchOutcome.Deleted(code, result)
            if (b.idempotencyKey != own) _events.emit(SyncEvent.Dropped(b.sessionId, code))
        } else {
            outbox.markFailed(b.idempotencyKey, code)
            outcomes[b.idempotencyKey] = BatchOutcome.Failed(code)
            blocked += b.sessionId
        }
    }
}
