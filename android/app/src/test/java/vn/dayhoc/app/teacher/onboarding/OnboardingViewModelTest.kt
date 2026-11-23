package vn.dayhoc.app.teacher.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.dayhoc.app.R
import vn.dayhoc.app.core.network.ApiError

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val api = FakeOnboardingApi()
    private val fees = PendingFeeStore(MemoryDataStore())
    private val today = LocalDate.of(2026, 11, 23)

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = OnboardingViewModel(api, fees) { today }

    @Test
    fun `tao lop loi mang thi thu lai dung lai id va khoa, khong tao lop thu hai`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.updateClassForm {
            it.copy(name = " Toán 9A ", subject = "Toán", grade = 9, feeType = FeeTypes.PER_SESSION, amount = "150000")
        }
        api.failCreate = 1

        vm.createClass()
        advanceUntilIdle()
        assertEquals(R.string.error_network, vm.state.value.snack?.res)
        assertEquals(OnboardingStep.CreateClass, vm.state.value.step)

        vm.createClass()
        advanceUntilIdle()

        val (k1, b1) = api.createCalls[0]
        val (k2, b2) = api.createCalls[1]
        assertEquals(b1.id, b2.id)
        assertEquals(k1, k2)
        assertEquals("Toán 9A", b2.name)
        assertEquals("offline", b2.mode)
        assertEquals("2026-11-23", b2.startDate)
        assertEquals(listOf(b2.id to FeePatch(FeeTypes.PER_SESSION, 150_000)), api.feeCalls.map { it.first to it.second })
        assertEquals(listOf("create_class" to "done"), api.stepCalls)
        assertEquals(OnboardingStep.AddStudents, vm.state.value.step)
    }

    @Test
    fun `mo lai app khi lop da tao ma chua ghi buoc thi chi ghi buoc`() = runTest {
        api.classes += ClassDto("c1", "Toán 9A", "2026-11-23")
        vm()
        advanceUntilIdle()

        assertTrue(api.createCalls.isEmpty())
        assertEquals(listOf("create_class" to "done"), api.stepCalls)
    }

    @Test
    fun `hoc phi tron khoa cho den buoc 3 roi ghi cung lich`() = runTest {
        api.onboarding = onboarding("set_schedule")
        api.classes += ClassDto("c1", "Toán 9A", "2026-11-23", endDate = "2027-02-28")
        fees.set("c1", 3_000_000)
        val vm = vm()
        advanceUntilIdle()

        // Mặc định một hàng "T2 · 17:30 · 90 phút".
        assertEquals(listOf(1 to "17:30"), vm.state.value.rows.map { it.weekday to it.start })
        vm.saveSchedule()
        advanceUntilIdle()

        assertEquals(listOf(RulesRequest(listOf(RuleDto(1, "17:30", 90)))), api.rulesCalls)
        assertEquals(listOf("c1" to FeePatch(FeeTypes.COURSE, 3_000_000)), api.feeCalls.map { it.first to it.second })
        assertNull(fees.get("c1"))
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `them hoc vien roi ghi danh vao lop, so dien thoai duoc chuan hoa`() = runTest {
        api.onboarding = onboarding("add_students")
        api.classes += ClassDto("c1", "Toán 9A", "2026-11-23")
        val vm = vm()
        advanceUntilIdle()

        vm.updateStudent(name = "Nguyễn Văn Nam", phone = "+84 912 345 678")
        vm.addStudent("Phụ huynh của Nguyễn Văn Nam")
        advanceUntilIdle()

        val body = api.studentCalls.single()
        assertEquals(listOf(GuardianInput("Phụ huynh của Nguyễn Văn Nam", "guardian", "0912345678")), body.guardians)
        assertEquals(listOf("c1" to body.id), api.enrollCalls)
        assertEquals(listOf(AddedStudent("Nguyễn Văn Nam", "0912345678")), vm.state.value.students.added)
        assertEquals("", vm.state.value.students.name)

        vm.finishStudents()
        advanceUntilIdle()
        assertEquals(OnboardingStep.SetSchedule, vm.state.value.step)
    }
}

private val ORDER = listOf("create_class", "add_students", "set_schedule")

private fun onboarding(current: String?) = OnboardingDto(
    steps = ORDER.map { s ->
        val done = current == null || ORDER.indexOf(s) < ORDER.indexOf(current)
        OnboardingDto.StepDto(s, if (done) "done" else "pending")
    },
    currentStep = current,
)

private class FakeOnboardingApi : OnboardingApi {
    var onboarding = onboarding("create_class")
    val classes = mutableListOf<ClassDto>()
    var failCreate = 0
    val createCalls = mutableListOf<Pair<String, CreateClassRequest>>()
    val feeCalls = mutableListOf<Triple<String, FeePatch, String>>()
    val stepCalls = mutableListOf<Pair<String, String>>()
    val rulesCalls = mutableListOf<RulesRequest>()
    val studentCalls = mutableListOf<CreateStudentRequest>()
    val enrollCalls = mutableListOf<Pair<String, String>>()

    override suspend fun get() = onboarding

    override suspend fun setStep(step: String, body: StepStatusRequest): OnboardingDto {
        stepCalls += step to body.status
        onboarding = onboarding(ORDER.getOrNull(ORDER.indexOf(step) + 1))
        return onboarding
    }

    override suspend fun classes(status: String, pageSize: Int) = ClassPage(classes.toList())

    override suspend fun createClass(key: String, body: CreateClassRequest): ClassDto {
        createCalls += key to body
        if (failCreate-- > 0) throw ApiError.Network
        return ClassDto(body.id, body.name, body.startDate).also { classes += it }
    }

    override suspend fun setFee(id: String, key: String, body: FeePatch): ClassDto {
        feeCalls += Triple(id, body, key)
        return classes.first { it.id == id }
    }

    override suspend fun putRules(id: String, key: String, body: RulesRequest) {
        rulesCalls += body
    }

    override suspend fun students(classId: String, pageSize: Int) = StudentPage(emptyList())

    override suspend fun createStudent(key: String, body: CreateStudentRequest): StudentDto {
        studentCalls += body
        return StudentDto(body.id, body.fullName)
    }

    override suspend fun enroll(classId: String, key: String, body: EnrollRequest) {
        enrollCalls += classId to body.studentId
    }
}

private class MemoryDataStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}
