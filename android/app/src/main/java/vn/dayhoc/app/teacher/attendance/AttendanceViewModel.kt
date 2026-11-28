package vn.dayhoc.app.teacher.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.ConnectivityMonitor
import vn.dayhoc.app.core.network.apiCall
import vn.dayhoc.app.teacher.today.toInstantOrNull

/** Thông báo T03 hiện sau khi T04 đóng hoặc khi đồng bộ nền bỏ lô. */
sealed interface AttendanceNotice {
    data class Saved(val parentsToNotify: Int) : AttendanceNotice
    data object SavedLocal : AttendanceNotice
    data class Dropped(val code: String) : AttendanceNotice
}

enum class ReadOnly { Expired, Cancelled }

/** Lỗi lưu: [code] có thì hiện thông báo riêng; [retry] = có nút "Thử lại" (lô giữ nguyên khóa). */
data class SaveError(val code: String?, val retry: Boolean)

data class AttendanceUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val className: String? = null,
    val startsAt: Instant? = null,
    val rows: List<AttRow> = emptyList(),
    val expandedId: String? = null,
    /** Buổi đã điểm danh: chỉ gửi học sinh đổi, nút "Lưu thay đổi". */
    val editMode: Boolean = false,
    val readOnly: ReadOnly? = null,
    val lastEditedAt: Instant? = null,
    val saving: Boolean = false,
    val offline: Boolean = false,
    val confirmDiscard: Boolean = false,
    val error: SaveError? = null,
    val staleNotice: Boolean = false,
    /** T04 xong việc: màn đóng lại về T03. */
    val done: Boolean = false,
) {
    val counts get() = countsOf(rows)
    val dirty get() = rows.any { it.changed }
    val label get() = saveLabel(rows, editMode, offline)

    /** Lần đầu luôn lưu được (kể cả đủ mặt); chế độ sửa cần có thay đổi. */
    val canSave get() = readOnly == null && !saving && (!editMode || dirty)
}

/**
 * [T04] Điểm danh. Tải danh sách (có bản chụp khi mất mạng), đổi trạng thái, lưu online qua `POST /attendance/sync`
 * hoặc vào outbox khi mất mạng. Lô đang gửi giữ nguyên `Idempotency-Key` khi thử lại; đổi trạng thái thì lô mới.
 */
class AttendanceViewModel(
    private val sessionId: String,
    private val api: AttendanceApi,
    private val outbox: OutboxStore,
    private val roster: RosterCache,
    private val device: DeviceIdProvider,
    private val connectivity: ConnectivityMonitor,
    private val notify: (AttendanceNotice) -> Unit,
    private val onEnqueued: () -> Unit,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val collapseDelayMs: Long = 300,
) : ViewModel() {
    private val _state = MutableStateFlow(AttendanceUiState())
    val state: StateFlow<AttendanceUiState> = _state.asStateFlow()

    private var inFlight: OutboxBatch? = null
    private var collapseJob: Job? = null

    init {
        viewModelScope.launch { connectivity.online.collect { on -> _state.update { it.copy(offline = !on) } } }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            val dto = try {
                apiCall { api.get(sessionId) }.also { roster.put(sessionId, Json.encodeToString(SessionAttendanceDto.serializer(), it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Mất mạng: mở bằng bản chụp lần mở trước (nếu có) để vẫn điểm danh được.
                val offline = e == ApiError.Network || e == ApiError.Timeout
                val cached = if (offline) roster.get(sessionId)?.let { runCatching { Json.decodeFromString(SessionAttendanceDto.serializer(), it) }.getOrNull() } else null
                if (cached == null) {
                    _state.update { it.copy(loading = false, loadFailed = true) }
                    return@launch
                }
                cached
            }
            val pending = outbox.all().filter { it.sessionId == sessionId }
            _state.update {
                it.copy(
                    loading = false,
                    className = dto.className,
                    startsAt = toInstantOrNull(dto.startsAt),
                    rows = overlayPending(rowsOf(dto.items), pending),
                    editMode = dto.sessionStatus == "taught",
                    readOnly = when {
                        dto.sessionStatus == "cancelled" -> ReadOnly.Cancelled
                        !dto.canEdit -> ReadOnly.Expired
                        else -> null
                    },
                    lastEditedAt = lastEditedAt(dto.items),
                )
            }
        }
    }

    fun toggle(studentId: String) {
        collapseJob?.cancel()
        _state.update { s -> if (s.readOnly != null) s else s.copy(expandedId = if (s.expandedId == studentId) null else studentId) }
    }

    fun select(studentId: String, status: AttStatus) {
        inFlight = null
        _state.update { s -> s.copy(rows = s.rows.map { if (it.studentId == studentId) it.copy(status = status) else it }, error = null) }
        collapseJob?.cancel()
        collapseJob = viewModelScope.launch {
            delay(collapseDelayMs)
            _state.update { s -> if (s.expandedId == studentId) s.copy(expandedId = null) else s }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val batch = inFlight ?: buildBatch(s).also { inFlight = it }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            if (s.offline) return@launch enqueueLocal(batch)
            try {
                val request = SyncRequestDto(
                    device.get(), formatIso(clock.instant(), clock.zone), sessionId, batch.markSessionTaught,
                    batch.mutations.map { MutationDto(it.clientMutationId, it.studentId, it.status, it.note, it.clientUpdatedAt) },
                )
                onResult(batch, apiCall { api.sync(batch.idempotencyKey, request) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e == ApiError.Network || e == ApiError.Timeout) enqueueLocal(batch)
                else _state.update { it.copy(saving = false, error = errorFor(e)) }
            }
        }
    }

    private fun buildBatch(s: AttendanceUiState): OutboxBatch {
        val now = formatIso(clock.instant(), clock.zone)
        return OutboxBatch(
            idempotencyKey = newId(),
            sessionId = sessionId,
            mutations = buildMutations(s.rows, s.editMode, newId, now),
            markSessionTaught = true,
            createdAt = clock.millis(),
        )
    }

    private suspend fun enqueueLocal(batch: OutboxBatch) {
        outbox.enqueue(batch)
        onEnqueued()
        notify(AttendanceNotice.SavedLocal)
        _state.update { it.copy(saving = false, done = true) }
    }

    private fun onResult(batch: OutboxBatch, result: SyncResultDto) {
        when (val v = verdictOf(result.results)) {
            is BatchVerdict.Rejected -> {
                inFlight = null
                _state.update { it.copy(saving = false, error = SaveError(v.code, retry = false)) }
            }
            BatchVerdict.Done -> {
                val applied = applyResults(_state.value.rows, batch.mutations, result.results)
                inFlight = null
                if (applied.staleCount == 0) {
                    notify(AttendanceNotice.Saved(result.parentsToNotify))
                    _state.update { it.copy(saving = false, done = true) }
                } else {
                    // Server có bản mới hơn cho một số học sinh: hiện bản của server, ở lại để giáo viên xem.
                    _state.update { it.copy(saving = false, rows = applied.rows, editMode = true, staleNotice = true) }
                }
            }
        }
    }

    /** Lỗi từ server theo mã: [ApiError.Http.code] có thông báo riêng thì không thử lại được. */
    private fun errorFor(e: ApiError): SaveError =
        if (e is ApiError.Http && e.code in NO_RETRY_CODES) SaveError(e.code, retry = false) else SaveError(null, retry = true)

    fun staleNoticeShown() = _state.update { it.copy(staleNotice = false) }
    fun errorShown() = _state.update { it.copy(error = null) }
    fun askDiscard() = _state.update { it.copy(confirmDiscard = true) }
    fun stay() = _state.update { it.copy(confirmDiscard = false) }
    fun discard() = _state.update { it.copy(confirmDiscard = false, done = true) }

    private companion object {
        val NO_RETRY_CODES = setOf("ATTENDANCE_EDIT_WINDOW_CLOSED", "SESSION_CANCELLED", "NOT_FOUND")
    }
}
