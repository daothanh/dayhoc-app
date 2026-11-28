package vn.dayhoc.app.teacher.attendance

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// DTO viết tay theo teachhub-backend/contracts/openapi.json (attendance_get, attendance_sync, Class).
// Json của app bỏ qua trường lạ; trường tùy chọn của server có giá trị mặc định null.

interface AttendanceApi {
    @GET("sessions/{id}/attendance")
    suspend fun get(@Path("id") sessionId: String): SessionAttendanceDto

    /** [key] = `Idempotency-Key`: UUID mỗi lô, giữ nguyên khi thử lại hoặc phát lại từ outbox. */
    @POST("attendance/sync")
    suspend fun sync(@Header("Idempotency-Key") key: String, @Body body: SyncRequestDto): SyncResultDto

    @GET("classes/{id}")
    suspend fun classDetail(@Path("id") classId: String): ClassDetailDto
}

@Serializable
data class SessionAttendanceDto(
    val sessionId: String,
    val classId: String,
    val className: String,
    val sessionDate: String,
    val startsAt: String,
    /** Không có `endsAt` trong hợp đồng: giờ kết thúc lấy từ buổi ở T03 (xem [AttendanceRoute]). */
    val sessionStatus: String,
    val canEdit: Boolean,
    val editableUntil: String? = null,
    val items: List<AttendanceItemDto>,
)

/** `status == null`: chưa điểm danh, app hiểu là "Có mặt". */
@Serializable
data class AttendanceItemDto(
    val studentId: String,
    val fullName: String,
    val status: String? = null,
    val note: String? = null,
    val clientUpdatedAt: String? = null,
    val syncedAt: String? = null,
)

@Serializable
data class SyncRequestDto(
    val deviceId: String,
    val deviceTime: String,
    val sessionId: String,
    val markSessionTaught: Boolean,
    val mutations: List<MutationDto>,
)

@Serializable
data class MutationDto(
    val clientMutationId: String,
    val studentId: String,
    val status: String,
    val note: String?,
    val clientUpdatedAt: String,
)

@Serializable
data class SyncResultDto(
    val sessionId: String,
    val sessionStatus: String,
    val results: List<MutationResultDto>,
    val parentsToNotify: Int = 0,
)

/** `result`: applied | duplicate | stale | rejected. */
@Serializable
data class MutationResultDto(
    val clientMutationId: String,
    val studentId: String,
    val result: String,
    val errorCode: String? = null,
    val current: CurrentDto? = null,
)

@Serializable
data class CurrentDto(val status: String, val note: String? = null, val clientUpdatedAt: String? = null, val recordedByName: String? = null)

@Serializable
data class ClassDetailDto(val scheduleRules: List<ScheduleRuleDto> = emptyList())

/** `weekday` theo ISO 8601: 1 = Thứ Hai … 7 = Chủ Nhật. */
@Serializable
data class ScheduleRuleDto(val weekday: Int)
