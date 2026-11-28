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
    /** [rejected]: tên học sinh server không ghi được (vd. không còn trong lớp); lô vẫn xong. */
    data class Saved(val parentsToNotify: Int, val rejected: List<String> = emptyList()) : AttendanceNotice

    /** Đồng bộ nền xong nhưng [count] học sinh server không ghi được. */
    data class PartlyRejected(val count: Int) : AttendanceNotice
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
    /** Buổi đã điểm danh (hoặc đã có lô chờ): chỉ gửi học sinh đổi, nút "Lưu thay đổi". */
    val editMode: Boolean = false,
    val readOnly: ReadOnly? = null,
    val lastEditedAt: Instant? = null,
    val saving: Boolean = false,
    val offline: Boolean = false,
    val confirmDiscard: Boolean = false,
    val error: SaveError? = null,
    /** Mã lỗi của lô đã bị server từ chối vĩnh viễn (đang nằm trong outbox với trạng thái `failed`). */
    val failedCode: String? = null,
    val staleNotice: Boolean = false,
    /** T04 xong việc: màn đóng lại về T03. */
    val done: Boolean = false,
) {
    val counts get() = countsOf(rows)
    val dirty get() = rows.any { it.changed }
    val label get() = saveLabel(rows, editMode, offline)

    /** Lần đầu luôn lưu được (kể cả đủ mặt); chế độ sửa cần có thay đổi; lớp không có học sinh thì không lưu. */
    val canSave get() = readOnly == null && !saving && rows.isNotEmpty() && (!editMode || dirty || failedCode != null)
}

/**
 * [T04] Điểm danh. Một đường gửi duy nhất: lập lô (khóa, body, mốc giờ), ghi outbox trước, rồi nhờ [AttendanceSyncEngine]
 * gửi và chờ kết quả của lô đó. Lô giữ nguyên `Idempotency-Key` và body khi thử lại; đổi trạng thái thì lô mới.
 */
class AttendanceViewModel(
    private val sessionId: String,
    private val api: AttendanceApi,
    private val outbox: OutboxStore,
    private val roster: RosterCache,
    private val device: DeviceIdProvider,
    private val engine: AttendanceSyncEngine,
    private val connectivity: ConnectivityMonitor,
    /** Chủ dữ liệu (người dùng + tenant); `null` = chưa xác định: không cho lưu (đóng kín). */
    private val ownerProvider: suspend () -> OutboxOwner?,
    private val notify: (AttendanceNotice) -> Unit,
    private val onEnqueued: () -> Unit,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val collapseDelayMs: Long = 300,
) : ViewModel() {
    private val _state = MutableStateFlow(AttendanceUiState())
    val state: StateFlow<AttendanceUiState> = _state.asStateFlow()

    /** Lô vừa lưu vào outbox của lần bấm Lưu hiện tại (giữ khóa khi thử lại). */
    private var enqueued: OutboxBatch? = null

    /** Lô đã lập nhưng giáo viên đổi trạng thái sau đó: xóa khi lưu lại. */
    private val superseded = mutableListOf<String>()
    private var collapseJob: Job? = null

    init {
        viewModelScope.launch { connectivity.online.collect { on -> _state.update { it.copy(offline = !on) } } }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            var fromCache = false
            val owner = ownerProvider()
            val dto = try {
                apiCall { api.get(sessionId) }.also {
                    if (owner != null) roster.put(sessionId, owner, Json.encodeToString(SessionAttendanceDto.serializer(), it))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Mất mạng: mở bằng bản chụp lần mở trước (nếu có) để vẫn điểm danh được.
                val offline = e == ApiError.Network || e == ApiError.Timeout
                val cached = if (offline && owner != null) roster.get(sessionId, owner)?.let { runCatching { Json.decodeFromString(SessionAttendanceDto.serializer(), it) }.getOrNull() } else null
                if (cached == null) {
                    _state.update { it.copy(loading = false, loadFailed = true) }
                    return@launch
                }
                fromCache = true
                cached
            }
            // Đọc outbox lỗi: không biết còn lô nào của buổi này, nên không cho điểm danh (tránh ghi đè mù).
            val batches = try {
                outbox.all().filter { it.owner == owner && it.sessionId == sessionId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadFailed = true) }
                return@launch
            }
            // Bản chụp có thể đã cũ: tính lại hạn sửa theo giờ hiện tại.
            val expired = fromCache && dto.editableUntil?.let(::toInstantOrNull)?.let { clock.instant() > it } == true
            _state.update {
                it.copy(
                    loading = false,
                    className = dto.className,
                    startsAt = toInstantOrNull(dto.startsAt),
                    rows = overlayPending(rowsOf(dto.items), batches),
                    editMode = dto.sessionStatus == "taught" || batches.any { b -> b.state == OutboxState.Pending } ||
                        (fromCache && dto.items.any { i -> i.status != null }),
                    readOnly = when {
                        dto.sessionStatus == "cancelled" -> ReadOnly.Cancelled
                        !dto.canEdit || expired -> ReadOnly.Expired
                        else -> null
                    },
                    lastEditedAt = lastEditedAt(dto.items),
                    failedCode = batches.firstOrNull { b -> b.state == OutboxState.Failed }?.errorCode,
                )
            }
        }
    }

    fun toggle(studentId: String) {
        collapseJob?.cancel()
        _state.update { s -> if (s.readOnly != null) s else s.copy(expandedId = if (s.expandedId == studentId) null else studentId) }
    }

    fun select(studentId: String, status: AttStatus) {
        // Nội dung đổi: lô đã lập (đang chờ hoặc lỗi) không còn đúng, lần lưu sau lập lô mới.
        enqueued?.let { superseded += it.idempotencyKey }
        enqueued = null
        _state.update { s ->
            s.copy(rows = s.rows.map { if (it.studentId == studentId) it.copy(status = status) else it }, error = null, failedCode = null)
        }
        collapseJob?.cancel()
        collapseJob = viewModelScope.launch {
            delay(collapseDelayMs)
            _state.update { s -> if (s.expandedId == studentId) s.copy(expandedId = null) else s }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val owner = ownerProvider() ?: return@launch fail(SaveError(OWNER_MISSING, retry = true))
                val existing = enqueued
                if (s.failedCode != null && existing != null) {
                    outbox.retryFailed(sessionId)
                    _state.update { it.copy(failedCode = null) }
                    return@launch submit(existing, fresh = false)
                }
                val batch = existing ?: run {
                    discardSuperseded(owner)
                    buildBatch(owner, s)
                }
                when {
                    s.offline -> {
                        engine.enqueue(batch)
                        enqueued = batch
                        finishLocal()
                    }
                    else -> submit(batch, fresh = existing == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(SaveError(null, retry = true))
            }
        }
    }

    /** "Gửi lại" lô lỗi đang có trong outbox khi mở T04. */
    fun resend() {
        if (_state.value.failedCode == null || _state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val owner = ownerProvider() ?: return@launch fail(SaveError(OWNER_MISSING, retry = true))
                engine.exclusive { outbox.retryFailed(sessionId) }
                val batch = outbox.all().lastOrNull { it.owner == owner && it.sessionId == sessionId && it.state == OutboxState.Pending }
                _state.update { it.copy(failedCode = null) }
                if (batch == null) {
                    _state.update { it.copy(saving = false) }
                    return@launch
                }
                enqueued = batch
                submit(batch, fresh = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(SaveError(null, retry = true))
            }
        }
    }

    private fun fail(e: SaveError) = _state.update { it.copy(saving = false, error = e) }

    /** [fresh]: lô chưa có trong outbox, ghi và gửi trong cùng một lần giữ khóa của engine. */
    private suspend fun submit(batch: OutboxBatch, fresh: Boolean) {
        val key = batch.idempotencyKey
        val report = if (fresh) engine.enqueueAndFlush(batch) else engine.flush(own = key)
        enqueued = batch
        when (val o = report.outcomes[key]) {
            is BatchOutcome.Synced -> {
                val applied = applyResults(_state.value.rows, batch.mutations, o.result.results)
                if (applied.staleCount == 0) {
                    enqueued = null
                    val rejected = rejectedStudentIds(o.result.results).toSet()
                    val names = _state.value.rows.filter { it.studentId in rejected }.map { it.name }
                    notify(AttendanceNotice.Saved(o.result.parentsToNotify, names))
                    _state.update { it.copy(saving = false, done = true) }
                } else {
                    // Server có bản mới hơn cho một số học sinh: hiện bản của server, ở lại để giáo viên xem.
                    enqueued = null
                    _state.update { it.copy(saving = false, rows = applied.rows, editMode = true, staleNotice = true) }
                }
            }
            is BatchOutcome.Kept -> if (o.network) finishLocal() else fail(SaveError(null, retry = true))
            is BatchOutcome.Failed -> _state.update { it.copy(saving = false, failedCode = o.code, error = SaveError(o.code, retry = false)) }
            is BatchOutcome.Deleted -> {
                enqueued = null
                _state.update { s ->
                    // Chỉ xem: buổi đã hủy hoặc quá hạn sửa; các mục server đã nhận trước khi từ chối vẫn được áp.
                    val rows = o.result?.let { applyResults(s.rows, batch.mutations, it.results).rows } ?: s.rows
                    s.copy(
                        saving = false, rows = rows, expandedId = null,
                        readOnly = when (o.code) {
                            EDIT_WINDOW_CLOSED -> ReadOnly.Expired
                            "SESSION_CANCELLED" -> ReadOnly.Cancelled
                            else -> s.readOnly
                        },
                        error = SaveError(o.code, retry = false),
                    )
                }
            }
            // Engine không báo gì cho lô này (chủ đổi, lô bị bỏ…): đọc lại outbox để biết lô đang ở đâu.
            null -> when (val now = outbox.all().firstOrNull { it.idempotencyKey == key }) {
                null -> {
                    enqueued = null
                    notify(AttendanceNotice.Saved(0))
                    _state.update { it.copy(saving = false, done = true) }
                }
                else -> if (now.state == OutboxState.Failed) {
                    _state.update { it.copy(saving = false, failedCode = now.errorCode, error = SaveError(now.errorCode, retry = false)) }
                } else {
                    finishLocal()
                }
            }
        }
    }

    private fun finishLocal() {
        onEnqueued()
        notify(AttendanceNotice.SavedLocal)
        _state.update { it.copy(saving = false, done = true) }
    }

    private suspend fun buildBatch(owner: OutboxOwner, s: AttendanceUiState): OutboxBatch {
        val now = formatIso(clock.instant(), clock.zone)
        return OutboxBatch(
            idempotencyKey = newId(),
            sessionId = sessionId,
            owner = owner,
            deviceId = device.get(),
            deviceTime = now,
            mutations = buildMutations(s.rows, s.editMode, newId, now),
            markSessionTaught = true,
            createdAt = clock.millis(),
        )
    }

    /** Lô cũ bị thay (đổi trạng thái sau lỗi) và lô lỗi của buổi này: bỏ để không gửi lại nội dung cũ. */
    private suspend fun discardSuperseded(owner: OutboxOwner) {
        val failed = outbox.all().filter { it.owner == owner && it.sessionId == sessionId && it.state == OutboxState.Failed }
        engine.exclusive { (superseded + failed.map { it.idempotencyKey }).forEach { outbox.remove(it) } }
        superseded.clear()
    }

    fun staleNoticeShown() = _state.update { it.copy(staleNotice = false) }
    fun errorShown() = _state.update { it.copy(error = null) }
    fun askDiscard() = _state.update { if (it.saving) it else it.copy(confirmDiscard = true) }
    fun stay() = _state.update { it.copy(confirmDiscard = false) }

    /** Bỏ mọi lô của màn (lô vừa lưu và lô bị thay khi đổi trạng thái); xóa lỗi thì ở lại và báo, không nuốt. */
    fun discard() {
        if (_state.value.saving) return
        _state.update { it.copy(confirmDiscard = false, saving = true) }
        viewModelScope.launch {
            try {
                val keys = superseded + listOfNotNull(enqueued?.idempotencyKey)
                engine.exclusive { keys.forEach { outbox.remove(it) } }
                superseded.clear()
                enqueued = null
                _state.update { it.copy(saving = false, done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(SaveError(DISCARD_FAILED, retry = false))
            }
        }
    }

    companion object {
        const val OWNER_MISSING = "OWNER_MISSING"
        const val DISCARD_FAILED = "DISCARD_FAILED"
    }
}
