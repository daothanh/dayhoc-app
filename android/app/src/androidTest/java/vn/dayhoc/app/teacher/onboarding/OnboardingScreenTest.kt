package vn.dayhoc.app.teacher.onboarding

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import vn.dayhoc.app.core.designsystem.TeachHubTheme
import vn.dayhoc.app.core.network.ApiError

/**
 * Test UI/E2E luồng onboarding giáo viên (T02, Figma B07–B09). Hermetic: dựng trực tiếp
 * [OnboardingScreen] với [OnboardingViewModel] chạy trên [FakeOnboardingApi] và ngày cố định,
 * không cần backend thật. Chờ trạng thái bằng `waitUntil` theo chuỗi thật trong `strings.xml`,
 * không dùng `Thread.sleep`.
 *
 * Tên hàm test viết ASCII kiểu snake_case (không dấu) vì DEX của androidTest không nhận khoảng
 * trắng trong tên phương thức khi minSdk < 30.
 */
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var api: FakeOnboardingApi
    private val fees = PendingFeeStore(MemoryDataStore())
    private val today = LocalDate.of(2026, 11, 23)

    @Before
    fun setUp() {
        api = FakeOnboardingApi()
    }

    private fun launch(onFinished: () -> Unit = {}): OnboardingViewModel {
        val vm = OnboardingViewModel(api, fees) { today }
        compose.setContent { TeachHubTheme { OnboardingScreen(vm, onFinished) } }
        return vm
    }

    /** Chờ tới khi màn hình có node mang đúng chuỗi [text]; auto-wait của Compose test. */
    private fun waitForText(text: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Gõ tên lớp qua UI; môn/khối/học phí đặt qua ViewModel cho gọn và ổn định. */
    private fun fillClassForm(vm: OnboardingViewModel) {
        compose.onNodeWithText("Tên lớp *").performTextInput("Toán 9A")
        compose.runOnIdle { vm.updateClassForm { it.copy(subject = "Toán", grade = 9, amount = "150000") } }
    }

    // ───────────────────────────── Bước 1 ─────────────────────────────

    @Test
    fun buoc_1_hien_tien_do_1_tren_3_va_nut_tiep_tuc_vo_hieu_khi_thieu_du_lieu() {
        api.onboarding = onboardingDto("create_class")
        launch()
        waitForText("Tạo lớp đầu tiên")

        compose.onNodeWithText("Thiết lập lớp học").assertIsDisplayed()
        compose.onNodeWithText("Bước 1/3").assertIsDisplayed()
        compose.onNodeWithText("Tiếp tục").assertIsNotEnabled()
    }

    @Test
    fun thieu_ten_lop_thi_nut_tiep_tuc_van_vo_hieu_du_da_dien_cac_muc_khac() {
        api.onboarding = onboardingDto("create_class")
        val vm = launch()
        waitForText("Tạo lớp đầu tiên")

        compose.runOnIdle { vm.updateClassForm { it.copy(subject = "Toán", grade = 9, amount = "150000") } }
        compose.onNodeWithText("Tiếp tục").assertIsNotEnabled()
    }

    @Test
    fun dien_du_thong_tin_bat_buoc_buoc_1_thi_nut_tiep_tuc_duoc_bat() {
        api.onboarding = onboardingDto("create_class")
        val vm = launch()
        waitForText("Tạo lớp đầu tiên")

        compose.onNodeWithText("Tiếp tục").assertIsNotEnabled()
        fillClassForm(vm)
        compose.onNodeWithText("Tiếp tục").assertIsEnabled()
    }

    @Test
    fun chon_tron_khoa_ma_chua_chon_ngay_ket_thuc_thi_nut_tiep_tuc_vo_hieu() {
        api.onboarding = onboardingDto("create_class")
        val vm = launch()
        waitForText("Tạo lớp đầu tiên")

        compose.runOnIdle {
            vm.updateClassForm {
                it.copy(name = "Toán 9A", subject = "Toán", grade = 9, amount = "3000000", feeType = FeeTypes.COURSE)
            }
        }
        compose.onNodeWithText("Ngày kết thúc khóa *").assertIsDisplayed()
        compose.onNodeWithText("Tiếp tục").assertIsNotEnabled()
    }

    @Test
    fun bam_tiep_tuc_tao_lop_roi_ghi_buoc_va_chuyen_sang_buoc_2() {
        api.onboarding = onboardingDto("create_class")
        val vm = launch()
        waitForText("Tạo lớp đầu tiên")

        fillClassForm(vm)
        compose.onNodeWithText("Tiếp tục").performClick()

        waitForText("Thêm học sinh")
        compose.onNodeWithText("Bước 2/3").assertIsDisplayed()
        compose.runOnIdle { assertTrue(api.stepCalls.contains("create_class" to "done")) }
    }

    // ───────────────────────────── Bước 2 ─────────────────────────────

    @Test
    fun buoc_2_chua_them_hoc_vien_thi_nut_tiep_tuc_vo_hieu() {
        api.onboarding = onboardingDto("add_students")
        api.classes += classDto("c1")
        launch()
        waitForText("Thêm học sinh")

        compose.onNodeWithText("Bước 2/3").assertIsDisplayed()
        compose.onNodeWithText("Tiếp tục (0 học sinh)").assertIsNotEnabled()
    }

    @Test
    fun them_hoc_vien_bang_form_nhap_tay_thi_hien_so_luong_va_bat_nut_tiep_tuc() {
        api.onboarding = onboardingDto("add_students")
        api.classes += classDto("c1")
        launch()
        waitForText("Thêm học sinh")

        compose.onNodeWithText("Họ tên học sinh *").performTextInput("Nguyễn Văn Nam")
        compose.onNodeWithText("Số điện thoại phụ huynh").performTextInput("0912345678")
        compose.onNodeWithText("Thêm học sinh").performClick()

        waitForText("ĐÃ THÊM (1)")
        compose.onNodeWithText("Nguyễn Văn Nam").assertIsDisplayed()
        compose.onNodeWithText("Tiếp tục (1 học sinh)").assertIsEnabled()
    }

    @Test
    fun hoc_vien_co_the_trung_thi_hien_dialog_hoi_va_cho_chon() {
        api.onboarding = onboardingDto("add_students")
        api.classes += classDto("c1")
        api.createStudentError = duplicateError("s9", "Nguyễn Văn Nam")
        launch()
        waitForText("Thêm học sinh")

        compose.onNodeWithText("Họ tên học sinh *").performTextInput("Nguyễn Văn Nam")
        compose.onNodeWithText("Thêm học sinh").performClick()

        waitForText("Có phải cùng một em?")
        compose.onNodeWithText("Đúng, là em này").assertIsDisplayed()
        compose.onNodeWithText("Không, thêm em mới").assertIsDisplayed()
        compose.onNodeWithText("Quay lại sửa").assertIsDisplayed()
    }

    @Test
    fun chon_dung_em_co_san_thi_ghi_danh_va_them_vao_danh_sach() {
        api.onboarding = onboardingDto("add_students")
        api.classes += classDto("c1")
        api.createStudentError = duplicateError("s9", "Nguyễn Văn Nam")
        launch()
        waitForText("Thêm học sinh")

        compose.onNodeWithText("Họ tên học sinh *").performTextInput("Nguyễn Văn Nam")
        compose.onNodeWithText("Thêm học sinh").performClick()
        waitForText("Có phải cùng một em?")

        compose.onNodeWithText("Đúng, là em này").performClick()
        waitForText("ĐÃ THÊM (1)")
        compose.onNodeWithText("Tiếp tục (1 học sinh)").assertIsEnabled()
    }

    @Test
    fun nut_bo_qua_khong_hien_o_buoc_1_vi_bat_buoc() {
        api.onboarding = onboardingDto("create_class")
        launch()
        waitForText("Tạo lớp đầu tiên")

        compose.onAllNodesWithText("Bỏ qua").assertCountEquals(0)
    }

    @Test
    fun nut_bo_qua_bat_o_buoc_2_vi_khong_bat_buoc() {
        api.onboarding = onboardingDto("add_students")
        api.classes += classDto("c1")
        launch()
        waitForText("Thêm học sinh")

        compose.onNodeWithText("Bỏ qua").assertIsEnabled()
    }

    // ───────────────────────────── Bước 3 ─────────────────────────────

    @Test
    fun buoc_3_hien_lich_mac_dinh_va_nut_hoan_tat_duoc_bat() {
        api.onboarding = onboardingDto("set_schedule")
        api.classes += classDto("c1")
        launch()
        waitForText("Lịch học hằng tuần")

        compose.onNodeWithText("Bước 3/3").assertIsDisplayed()
        compose.onNodeWithText("T2").assertIsDisplayed()
        compose.onNodeWithText("17:30").assertIsDisplayed()
        compose.onNodeWithText("90 phút").assertIsDisplayed()
        compose.onNodeWithText("Hoàn tất").assertIsEnabled()
    }

    @Test
    fun buoc_3_hai_buoi_chong_gio_thi_bao_loi_va_khoa_nut_hoan_tat() {
        api.onboarding = onboardingDto("set_schedule")
        api.classes += classDto("c1")
        val vm = launch()
        waitForText("Lịch học hằng tuần")

        compose.runOnIdle {
            vm.addRow()
            vm.updateRow(1) { it.copy(weekday = 1, start = "18:00") }
        }

        waitForText("Trùng giờ với buổi T2 17:30")
        compose.onNodeWithText("Hoàn tất").assertIsNotEnabled()
    }

    // ─────────────────────── Lỗi mạng và thử lại ───────────────────────

    @Test
    fun loi_tai_tien_do_onboarding_thi_hien_man_thu_lai_va_tai_lai_duoc() {
        api.getError = ApiError.Network
        launch()
        waitForText("Không tải được tiến độ. Thử lại.")

        api.getError = null
        api.onboarding = onboardingDto("create_class")
        compose.onNodeWithText("Thử lại").performClick()

        waitForText("Tạo lớp đầu tiên")
    }

    @Test
    fun loi_tai_du_lieu_buoc_thi_hien_man_thu_lai_va_tai_lai_duoc() {
        api.onboarding = onboardingDto("add_students")
        api.classes += classDto("c1")
        api.classesError = ApiError.Network
        launch()
        waitForText("Không tải được tiến độ. Thử lại.")

        api.classesError = null
        compose.onNodeWithText("Thử lại").performClick()

        waitForText("Thêm học sinh")
    }

    @Test
    fun ghi_buoc_loi_sau_khi_da_luu_thi_hien_thong_bao_va_thu_lai_duoc() {
        api.onboarding = onboardingDto("create_class")
        api.stepError = ApiError.Network
        val vm = launch()
        waitForText("Tạo lớp đầu tiên")

        fillClassForm(vm)
        compose.onNodeWithText("Tiếp tục").performClick()

        waitForText("Đã lưu. Chưa ghi nhận được bước này.")
        api.stepError = null
        compose.onNodeWithText("Thử lại").performClick()

        waitForText("Thêm học sinh")
    }
}

private const val TIMEOUT_MS = 10_000L
private val ORDER = listOf("create_class", "add_students", "set_schedule")

private fun classDto(id: String) = ClassDto(id, "Toán 9A", "2026-11-23")

private fun duplicateError(id: String, fullName: String) = ApiError.Http(
    ApiError.STUDENT_POSSIBLE_DUPLICATE,
    422,
    details = buildJsonObject {
        put(
            "candidates",
            buildJsonArray {
                add(buildJsonObject { put("id", id); put("fullName", fullName) })
            },
        )
    },
)

private fun onboardingDto(current: String?) = OnboardingDto(
    steps = ORDER.map { s ->
        val done = current == null || ORDER.indexOf(s) < ORDER.indexOf(current)
        OnboardingDto.StepDto(s, if (done) "done" else "pending")
    },
    currentStep = current,
)

/** DataStore trong bộ nhớ: đủ cho [PendingFeeStore], không chạm đĩa. */
private class MemoryDataStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}

/** Fake [OnboardingApi]: trả dữ liệu cố định và cho phép điều khiển lỗi để kiểm nhánh thử lại. */
private class FakeOnboardingApi : OnboardingApi {
    var onboarding: OnboardingDto = onboardingDto("create_class")
    var getError: ApiError? = null
    var classesError: ApiError? = null
    var stepError: ApiError? = null
    var createStudentError: ApiError? = null
    val classes = mutableListOf<ClassDto>()
    val stepCalls = mutableListOf<Pair<String, String>>()

    override suspend fun get(): OnboardingDto {
        getError?.let { throw it }
        return onboarding
    }

    override suspend fun setStep(step: String, key: String, body: StepStatusRequest): OnboardingDto {
        stepError?.let { throw it }
        stepCalls += step to body.status
        onboarding = onboardingDto(ORDER.getOrNull(ORDER.indexOf(step) + 1))
        return onboarding
    }

    override suspend fun classes(status: String, pageSize: Int): ClassPage {
        classesError?.let { throw it }
        return ClassPage(classes.toList())
    }

    override suspend fun createClass(key: String, body: CreateClassRequest): ClassDto =
        ClassDto(body.id, body.name, body.startDate).also { classes += it }

    override suspend fun setFee(id: String, key: String, body: FeePatch): ClassDto = classes.first { it.id == id }

    override suspend fun putRules(id: String, key: String, body: RulesRequest) = Unit

    override suspend fun students(classId: String, pageSize: Int): StudentPage = StudentPage(emptyList())

    override suspend fun createStudent(key: String, body: CreateStudentRequest): StudentDto {
        createStudentError?.let { throw it }
        return StudentDto(body.id, body.fullName)
    }

    override suspend fun enroll(classId: String, key: String, body: EnrollRequest) = Unit
}
