package vn.dayhoc.app.teacher.attendance

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Chủ của dữ liệu ngoại tuyến: chỉ lô của đúng người dùng và tenant này mới được gửi (US-3.5). */
@Serializable
data class OutboxOwner(val userId: String, val tenantId: String)

/** Một mutation đã dựng xong, giữ nguyên `clientMutationId` và `clientUpdatedAt` khi gửi lại. */
@Serializable
data class OutboxMutation(
    val clientMutationId: String,
    val studentId: String,
    val status: String,
    val note: String?,
    val clientUpdatedAt: String,
)

enum class OutboxState(val wire: String) {
    Pending("pending"), Failed("failed");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: Failed
    }
}

/**
 * Một lô điểm danh của một buổi chưa gửi được. Lưu nguyên body đã dựng lần đầu (gồm [deviceId], [deviceTime]):
 * backend băm cả body vào khóa idempotency nên phát lại phải y nguyên. [OutboxState.Failed] = server từ chối vĩnh viễn,
 * giữ lại để giáo viên thấy và gửi lại (không tự xóa).
 */
data class OutboxBatch(
    val idempotencyKey: String,
    val sessionId: String,
    val owner: OutboxOwner,
    val deviceId: String,
    val deviceTime: String,
    val mutations: List<OutboxMutation>,
    val markSessionTaught: Boolean,
    val createdAt: Long,
    val attempts: Int = 0,
    val state: OutboxState = OutboxState.Pending,
    val errorCode: String? = null,
) {
    fun request() = SyncRequestDto(
        deviceId, deviceTime, sessionId, markSessionTaught,
        mutations.map { MutationDto(it.clientMutationId, it.studentId, it.status, it.note, it.clientUpdatedAt) },
    )
}

/** Outbox lô điểm danh chưa gửi (Technical Spec 6.4). Bản thật là Room + SQLCipher; test dùng bản trong bộ nhớ. */
interface OutboxStore {
    /** Mọi lô (của mọi chủ), theo thứ tự tạo. */
    val batches: Flow<List<OutboxBatch>>

    suspend fun enqueue(batch: OutboxBatch)
    suspend fun all(): List<OutboxBatch>
    suspend fun remove(idempotencyKey: String)
    suspend fun addAttempt(idempotencyKey: String)
    suspend fun markFailed(idempotencyKey: String, code: String)

    /** "Gửi lại": lô lỗi của buổi về chờ gửi, đếm lại từ 0. */
    suspend fun retryFailed(sessionId: String)
}

/** Bản chụp danh sách học sinh của buổi (JSON của [SessionAttendanceDto]) để mở T04 khi mất mạng. */
interface RosterCache {
    suspend fun get(sessionId: String, owner: OutboxOwner): String?
    suspend fun put(sessionId: String, owner: OutboxOwner, json: String)
    suspend fun removeRoster(sessionId: String)
}

/** UUID cố định mỗi lần cài đặt, gửi kèm mọi lô đồng bộ. */
interface DeviceIdProvider {
    suspend fun get(): String
}
