package vn.dayhoc.app.teacher.attendance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.dayhoc.app.core.network.ConnectivityMonitor

class InMemoryOutbox(initial: List<OutboxBatch> = emptyList()) : OutboxStore {
    private val state = MutableStateFlow(initial)
    override val batches: Flow<List<OutboxBatch>> = state
    override suspend fun enqueue(batch: OutboxBatch) { state.value = state.value + batch }
    override suspend fun all() = state.value
    override suspend fun remove(idempotencyKey: String) { state.value = state.value.filter { it.idempotencyKey != idempotencyKey } }
    override suspend fun addAttempt(idempotencyKey: String) {
        state.value = state.value.map { if (it.idempotencyKey == idempotencyKey) it.copy(attempts = it.attempts + 1) else it }
    }
}

class InMemoryRoster : RosterCache {
    val map = mutableMapOf<String, String>()
    override suspend fun get(sessionId: String) = map[sessionId]
    override suspend fun put(sessionId: String, json: String) { map[sessionId] = json }
}

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
