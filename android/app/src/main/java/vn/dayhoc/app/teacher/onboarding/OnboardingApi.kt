package vn.dayhoc.app.teacher.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// DTO viết tay theo teachhub-backend/contracts/openapi.json (tag onboarding, classes, students).
// Json của app không ghi giá trị mặc định: trường bắt buộc không được có default.

interface OnboardingApi {
    @GET("onboarding")
    suspend fun get(): OnboardingDto

    @PATCH("onboarding/steps/{step}")
    suspend fun setStep(
        @Path("step") step: String,
        @Header("Idempotency-Key") key: String,
        @Body body: StepStatusRequest,
    ): OnboardingDto

    @GET("classes")
    suspend fun classes(@Query("status") status: String = "active", @Query("pageSize") pageSize: Int = 100): ClassPage

    /** `id` do app sinh: gửi lại sau khi mất mạng hoặc kill app không tạo lớp thứ hai. */
    @POST("classes")
    suspend fun createClass(@Header("Idempotency-Key") key: String, @Body body: CreateClassRequest): ClassDto

    /** `POST /classes` chưa nhận học phí; đặt bằng `PATCH`, Trọn khóa cần lịch đã lưu. */
    @PATCH("classes/{id}")
    suspend fun setFee(@Path("id") id: String, @Header("Idempotency-Key") key: String, @Body body: FeePatch): ClassDto

    @PUT("classes/{id}/schedule-rules")
    suspend fun putRules(@Path("id") id: String, @Header("Idempotency-Key") key: String, @Body body: RulesRequest)

    @GET("students")
    suspend fun students(@Query("classId") classId: String, @Query("pageSize") pageSize: Int = 100): StudentPage

    @POST("students")
    suspend fun createStudent(@Header("Idempotency-Key") key: String, @Body body: CreateStudentRequest): StudentDto

    @POST("classes/{id}/enrollments")
    suspend fun enroll(@Path("id") classId: String, @Header("Idempotency-Key") key: String, @Body body: EnrollRequest)
}

/** Ba bước còn lại từ 22/11/2026; server vẫn trả `payout_account`, `compliance_checklist` và app bỏ qua. */
enum class OnboardingStep(val wire: String) {
    CreateClass("create_class"),
    AddStudents("add_students"),
    SetSchedule("set_schedule"),
    ;

    /** Bước 1 không được bỏ qua (US-0.2 AC2). */
    val skippable get() = this != CreateClass

    companion object {
        fun fromWire(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

@Serializable
data class OnboardingDto(val steps: List<StepDto>, val currentStep: String? = null) {
    @Serializable
    data class StepDto(val step: String, val status: String)

    /** Bước đang dở trong 3 bước app biết; `null` khi đã xử lý hết. */
    val current: OnboardingStep?
        get() = OnboardingStep.fromWire(currentStep)
            ?: steps.firstOrNull { it.status == "pending" }?.let { OnboardingStep.fromWire(it.step) }
}

@Serializable
data class StepStatusRequest(val status: String)

@Serializable
data class CreateClassRequest(
    val id: String,
    val name: String,
    val subject: String,
    val grade: Int,
    val mode: String,
    val startDate: String,
    val endDate: String? = null,
)

@Serializable
data class FeePatch(val feeType: String, val feeAmount: Long)

@Serializable
data class RuleDto(val weekday: Int, val startTime: String, val durationMin: Int)

@Serializable
data class ClassDto(
    val id: String,
    val name: String,
    val startDate: String,
    val endDate: String? = null,
    val feeType: String? = null,
    val feeAmount: Long? = null,
    val scheduleRules: List<RuleDto> = emptyList(),
)

@Serializable
data class ClassPage(val items: List<ClassDto>)

@Serializable
data class RulesRequest(val rules: List<RuleDto>)

@Serializable
data class GuardianInput(val fullName: String, val relation: String, val phone: String)

@Serializable
data class CreateStudentRequest(
    val id: String,
    val fullName: String,
    val guardians: List<GuardianInput>? = null,
    val confirmNew: Boolean? = null,
)

@Serializable
data class GuardianDto(val fullName: String, val phone: String? = null)

@Serializable
data class StudentDto(val id: String, val fullName: String, val guardians: List<GuardianDto> = emptyList())

@Serializable
data class StudentPage(val items: List<StudentDto>)

@Serializable
data class EnrollRequest(val studentId: String)

/** Hồ sơ trùng khả nghi trong `details.candidates` của lỗi `STUDENT_POSSIBLE_DUPLICATE`. */
@Serializable
data class StudentCandidate(val id: String, val fullName: String, val dob: String? = null, val school: String? = null)

object FeeTypes {
    const val PER_SESSION = "per_session"
    const val MONTHLY = "monthly"
    const val COURSE = "course"
}

/**
 * Học phí Trọn khóa chọn ở bước 1 chỉ ghi được khi lớp đã có lịch (server tính tổng buổi),
 * nên giữ trên máy tới bước 3. Chỉ có id lớp và số tiền, không có dữ liệu cá nhân.
 */
class PendingFeeStore(private val prefs: DataStore<Preferences>) {
    suspend fun get(classId: String): Long? = orNull {
        prefs.data.first()[KEY]?.split('|')?.takeIf { it.size == 2 && it[0] == classId }?.get(1)?.toLongOrNull()
    }

    /** `false` khi ghi đĩa lỗi: bước 1 báo lỗi thay vì đi tiếp rồi mất học phí. */
    suspend fun set(classId: String, amount: Long): Boolean =
        orNull { prefs.edit { it[KEY] = "$classId|$amount" } } != null

    suspend fun clear() {
        orNull { prefs.edit { it.remove(KEY) } }
    }

    private inline fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        val KEY = stringPreferencesKey("pending_course_fee")
    }
}
