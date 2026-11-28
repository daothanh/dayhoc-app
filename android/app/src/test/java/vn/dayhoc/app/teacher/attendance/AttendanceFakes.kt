package vn.dayhoc.app.teacher.attendance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.dayhoc.app.core.network.ConnectivityMonitor

val OWNER = OutboxOwner("u1", "t1")

class InMemoryOutbox(initial: List<OutboxBatch> = emptyList()) : OutboxStore {
    private val state = MutableStateFlow(initial)
    var failOnAll = false
    override val batches: Flow<List<OutboxBatch>> = state
    override suspend fun enqueue(batch: OutboxBatch) { state.value = state.value.filter { it.idempotencyKey != batch.idempotencyKey } + batch }
    override suspend fun all(): List<OutboxBatch> { if (failOnAll) error("outbox hỏng"); return state.value }
    override suspend fun remove(idempotencyKey: String) { state.value = state.value.filter { it.idempotencyKey != idempotencyKey } }
    override suspend fun addAttempt(idempotencyKey: String) = update(idempotencyKey) { it.copy(attempts = it.attempts + 1) }
    override suspend fun markFailed(idempotencyKey: String, code: String) =
        update(idempotencyKey) { it.copy(state = OutboxState.Failed, errorCode = code) }
    override suspend fun retryFailed(sessionId: String) {
        state.value = state.value.map {
            if (it.sessionId == sessionId && it.state == OutboxState.Failed && it.errorCode != "CORRUPT") it.copy(state = OutboxState.Pending, errorCode = null, attempts = 0) else it
        }
    }

    private fun update(key: String, f: (OutboxBatch) -> OutboxBatch) {
        state.value = state.value.map { if (it.idempotencyKey == key) f(it) else it }
    }
}

class InMemoryRoster : RosterCache {
    val map = mutableMapOf<String, String>()
    override suspend fun get(sessionId: String, owner: OutboxOwner) = map[sessionId]
    override suspend fun put(sessionId: String, owner: OutboxOwner, json: String) { map[sessionId] = json }
    override suspend fun removeRoster(sessionId: String) { map.remove(sessionId) }
}

fun batch(
    key: String,
    session: String = "sess-1",
    attempts: Int = 0,
    owner: OutboxOwner = OWNER,
    state: OutboxState = OutboxState.Pending,
    errorCode: String? = null,
) = OutboxBatch(
    key, session, owner, "device-1", "2026-11-09T17:40:00.000+07:00",
    listOf(OutboxMutation("m-$key", "s1", "unexcused", null, "2026-11-09T17:40:00.000+07:00")), true, 1, attempts, state, errorCode,
)

class FixedDevice(private val id: String = "device-1") : DeviceIdProvider {
    override suspend fun get() = id
}

class FakeConnectivity(online: Boolean = true) : ConnectivityMonitor {
    private val _online = MutableStateFlow(online)
    override val online: StateFlow<Boolean> = _online.asStateFlow()
    fun set(on: Boolean) { _online.value = on }
}

/** Gọi `get`/`sync` theo kịch bản; `sync` ghi lại (khóa, yêu cầu) để test kiểm. */
class FakeAttendanceApi : AttendanceApi {
    var attendance: () -> SessionAttendanceDto = { error("chưa đặt kịch bản get") }
    var syncHandler: suspend (String, SyncRequestDto) -> SyncResultDto = { _, req -> okResult(req) }
    val syncCalls = mutableListOf<Pair<String, SyncRequestDto>>()
    val getCalls = mutableListOf<String>()

    override suspend fun get(sessionId: String): SessionAttendanceDto { getCalls += sessionId; return attendance() }
    override suspend fun sync(key: String, body: SyncRequestDto): SyncResultDto {
        syncCalls += key to body
        return syncHandler(key, body)
    }
    override suspend fun classDetail(classId: String) = ClassDetailDto()

    companion object {
        fun okResult(req: SyncRequestDto, parents: Int = 0, results: List<MutationResultDto>? = null) = SyncResultDto(
            req.sessionId, "taught",
            results ?: req.mutations.map { MutationResultDto(it.clientMutationId, it.studentId, "applied") },
            parents,
        )
    }
}

fun item(id: String, name: String, status: String? = null, note: String? = null, syncedAt: String? = null) =
    AttendanceItemDto(id, name, status, note, null, syncedAt)

fun attendanceDto(
    items: List<AttendanceItemDto>,
    status: String = "planned",
    canEdit: Boolean = true,
) = SessionAttendanceDto("sess-1", "class-1", "Toán 9A", "2026-11-09", "2026-11-09T17:30:00+07:00", status, canEdit, null, items)
