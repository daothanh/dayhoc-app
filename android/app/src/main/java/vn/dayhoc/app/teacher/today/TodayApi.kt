package vn.dayhoc.app.teacher.today

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import vn.dayhoc.app.teacher.onboarding.OnboardingDto

// DTO viết tay theo teachhub-backend/contracts/openapi.json (sessions, classes, receivables, notifications).
// Chỉ khai trường màn Hôm nay dùng; Json của app bỏ qua trường lạ.

interface TodayApi {
    @GET("onboarding")
    suspend fun onboarding(): OnboardingDto

    @GET("sessions")
    suspend fun sessions(@Query("from") from: String, @Query("to") to: String): SessionList

    @GET("classes")
    suspend fun classes(@Query("status") status: String = "active", @Query("pageSize") pageSize: Int = 100): ClassInfoPage

    @GET("receivables")
    suspend fun receivables(@Query("pageSize") pageSize: Int = 100): ReceivablePage

    @GET("notifications")
    suspend fun notifications(@Query("limit") limit: Int = 1): NotificationInfo

    @GET("sessions/{id}/attendance")
    suspend fun attendance(@Path("id") sessionId: String): AttendanceInfo
}

@Serializable
data class SessionList(val items: List<SessionDto>)

/** `startsAt`, `endsAt`: ISO 8601 có offset. `status`: planned | taught | cancelled. */
@Serializable
data class SessionDto(
    val id: String,
    val classId: String,
    val className: String,
    val startsAt: String,
    val endsAt: String,
    val status: String,
)

@Serializable
data class ClassInfoPage(val items: List<ClassInfoDto>)

@Serializable
data class ClassInfoDto(
    val id: String,
    val name: String,
    val mode: String? = null,
    val location: String? = null,
    val enrolledCount: Int = 0,
)

@Serializable
data class ReceivablePage(val items: List<ReceivableRow>)

@Serializable
data class ReceivableRow(val outstandingAmount: Long, val overdueDays: Int)

@Serializable
data class NotificationInfo(val unreadCount: Int = 0)

/** Sĩ số = số học viên của buổi; có mặt = present + late (đi muộn vẫn có mặt). */
@Serializable
data class AttendanceInfo(val items: List<AttendanceRow>, val summary: AttendanceSummary)

@Serializable
data class AttendanceRow(val studentId: String)

@Serializable
data class AttendanceSummary(val present: Int = 0, val late: Int = 0)
