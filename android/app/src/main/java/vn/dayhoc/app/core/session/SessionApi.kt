package vn.dayhoc.app.core.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT

// DTO viết tay theo teachhub-backend/contracts/openapi.json (tag me, tenancy, app-config).

interface SessionApi {
    @GET("me/memberships")
    suspend fun memberships(): MembershipsResponse

    @PUT("me/active-membership")
    suspend fun setActive(@Body body: ActiveMembershipRequest)

    /** Tạo không gian giáo viên (tenant, chi nhánh, vai trò Chủ tenant) trong một giao dịch. */
    @POST("workspaces")
    suspend fun createWorkspace(
        @Header("Idempotency-Key") key: String,
        @Body body: JsonObject = JsonObject(emptyMap()),
    )

    /** Thêm vai trò `parent` hoặc `student` chưa có lời mời. */
    @POST("me/roles")
    suspend fun addRole(@Header("Idempotency-Key") key: String, @Body body: AddRoleRequest)

    @GET("app-config")
    suspend fun appConfig(): AppConfig

    @POST("center-waitlist")
    suspend fun joinCenterWaitlist(@Header("Idempotency-Key") key: String, @Body body: CenterWaitlistRequest)
}

@Serializable
data class MembershipDto(
    val id: String,
    /** [Roles.TEACHER] | [Roles.PARENT] | [Roles.STUDENT] */
    val role: String,
    /** "Giáo viên · Cô Lan", "Phụ huynh của Nam"… */
    val label: String,
    val tenantId: String? = null,
    val studentId: String? = null,
)

@Serializable
data class MembershipsResponse(val items: List<MembershipDto>, val lastMembershipId: String? = null)

@Serializable
data class ActiveMembershipRequest(val membershipId: String)

@Serializable
data class AddRoleRequest(val role: String)

@Serializable
data class AppConfig(val minSupportedVersion: MinVersion) {
    @Serializable
    data class MinVersion(val android: String, val ios: String)
}

@Serializable
data class CenterWaitlistRequest(val centerName: String, val teacherCount: Int)

object Roles {
    const val TEACHER = "tenant_owner"
    const val PARENT = "parent"
    const val STUDENT = "student"
    val ALL = listOf(TEACHER, PARENT, STUDENT)
}

/** `true` khi [current] thấp hơn [minimum] theo `major.minor.patch`; phần `-beta`, `+12` bị bỏ, số lạ coi là 0. */
fun isVersionBelow(current: String, minimum: String): Boolean {
    fun parts(v: String) = v.split('-', '+').first().split('.').let { p -> List(3) { p.getOrNull(it)?.toIntOrNull() ?: 0 } }
    val a = parts(current)
    val b = parts(minimum)
    for (i in 0 until 3) if (a[i] != b[i]) return a[i] < b[i]
    return false
}
