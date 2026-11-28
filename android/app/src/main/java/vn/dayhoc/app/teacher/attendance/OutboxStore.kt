package vn.dayhoc.app.teacher.attendance

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Một mutation đã dựng xong, giữ nguyên `clientMutationId` và `clientUpdatedAt` khi gửi lại. */
@Serializable
data class OutboxMutation(
    val clientMutationId: String,
    val studentId: String,
    val status: String,
    val note: String?,
    val clientUpdatedAt: String,
)

/** Một lô điểm danh của một buổi chưa gửi được. [idempotencyKey] cũng là khóa của lô trong outbox. */
data class OutboxBatch(
    val idempotencyKey: String,
    val sessionId: String,
    val mutations: List<OutboxMutation>,
    val markSessionTaught: Boolean,
    val createdAt: Long,
    val attempts: Int = 0,
)

/** Outbox lô điểm danh chưa gửi (Technical Spec 6.4). Bản thật là Room + SQLCipher; test dùng bản trong bộ nhớ. */
interface OutboxStore {
    /** Mọi lô đang chờ, theo thứ tự tạo. */
    val batches: Flow<List<OutboxBatch>>

    suspend fun enqueue(batch: OutboxBatch)
    suspend fun all(): List<OutboxBatch>
    suspend fun remove(idempotencyKey: String)
    suspend fun addAttempt(idempotencyKey: String)
}

/** Bản chụp danh sách học sinh của buổi (JSON của [SessionAttendanceDto]) để mở T04 khi mất mạng. */
interface RosterCache {
    suspend fun get(sessionId: String): String?
    suspend fun put(sessionId: String, json: String)
}

/** UUID cố định mỗi lần cài đặt, gửi kèm mọi lô đồng bộ. */
interface DeviceIdProvider {
    suspend fun get(): String
}
