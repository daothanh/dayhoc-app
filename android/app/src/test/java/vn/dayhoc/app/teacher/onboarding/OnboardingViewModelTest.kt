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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `mo tu banner thi vao dung buoc da bo qua`() = runTest {
        api.onboarding = OnboardingDto(
            listOf(OnboardingDto.StepDto("create_class", "done"), OnboardingDto.StepDto("add_students", "skipped"), OnboardingDto.StepDto("set_schedule", "skipped")),
        )
        val vm = OnboardingViewModel(api, fees, OnboardingStep.SetSchedule) { today }
        advanceUntilIdle()
        assertEquals(OnboardingStep.SetSchedule, vm.state.value.step)
    }

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
        // PATCH /onboarding/steps bắt buộc Idempotency-Key.
        assertTrue(api.stepKeys.single().isNotBlank())
    }

    @Test
    fun `mat phan hoi sau khi server da tao lop, giao vien sua ten roi bam lai thi khong tao lop thu hai`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.updateClassForm { it.copy(name = "Toán 9A", subject = "Toán", grade = 9, amount = "150000") }
        api.failCreate = 1
        api.createdButLost = true

        vm.createClass()
        advanceUntilIdle()
        vm.updateClassForm { it.copy(name = "Toán 9B") }
        vm.createClass()
        advanceUntilIdle()

        assertEquals(1, api.classes.size)
        assertEquals(api.createCalls[0].second.id, api.createCalls[1].second.id)
        assertEquals(listOf(api.classes.single().id), api.feeCalls.map { it.first })
        assertEquals(OnboardingStep.AddStudents, vm.state.value.step)
    }

    @Test
    fun `ghi danh bi tu choi het han muc thi bao ly do va cho them em khac`() = runTest {
        api.onboarding = onboarding("add_students")
        api.classes += ClassDto("c1", "Toán 9A", "2026-11-23")
        api.enrollError = ApiError.Http(ApiError.PLAN_LIMIT_EXCEEDED, 402)
        val vm = vm()
        advanceUntilIdle()

        vm.updateStudent(name = "Nguyễn Văn Nam")
        vm.addStudent("Phụ huynh của Nguyễn Văn Nam")
        advanceUntilIdle()

        assertEquals(R.string.students_error_limit, vm.state.value.snack?.res)
        assertEquals(false, vm.state.value.students.enrollFailed)
        assertEquals(false, vm.state.value.students.busy)
        assertTrue(vm.state.value.students.added.isEmpty())
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

    @Test
    fun `hoc vien co the trung thi cho chon ho so co san`() = runTest {
        api.onboarding = onboarding("add_students")
        api.classes += ClassDto("c1", "Toán 9A", "2026-11-23")
        api.createStudentError = ApiError.Http(
            ApiError.STUDENT_POSSIBLE_DUPLICATE,
            422,
            details = buildJsonObject {
                put(
                    "candidates",
                    buildJsonArray {
                        add(buildJsonObject { put("id", "s9"); put("fullName", "Nguyễn Văn Nam") })
                    },
                )
            },
        )
        val vm = vm()
        advanceUntilIdle()

        vm.updateStudent(name = "Nguyễn Văn Nam")
        vm.addStudent("Phụ huynh của Nguyễn Văn Nam")
        advanceUntilIdle()

        assertEquals(listOf(StudentCandidate("s9", "Nguyễn Văn Nam")), vm.state.value.students.duplicates)
        assertFalse(vm.state.value.students.busy)

        vm.pickExisting(StudentCandidate("s9", "Nguyễn Văn Nam"))
        advanceUntilIdle()

        assertEquals(listOf("c1" to "s9"), api.enrollCalls)
        assertEquals(listOf(AddedStudent("Nguyễn Văn Nam", null)), vm.state.value.students.added)
    }

    @Test
    fun `ghi buoc loi thi giu du lieu va cho thu lai`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.updateClassForm { it.copy(name = "Toán 9A", subject = "Toán", grade = 9, amount = "150000") }
        api.stepError = ApiError.Network

        vm.createClass()
        advanceUntilIdle()

        assertEquals(1, api.classes.size)
        assertTrue(vm.state.value.recordFailed)
        assertFalse(vm.state.value.saving)
        assertEquals(OnboardingStep.CreateClass, vm.state.value.step)

        api.stepError = null
        vm.retryRecord()
        advanceUntilIdle()

        assertFalse(vm.state.value.recordFailed)
        assertEquals(OnboardingStep.AddStudents, vm.state.value.step)
    }

    @Test
    fun `bo qua buoc ghi danh khi chua them hoc vien`() = runTest {
        api.onboarding = onboarding("add_students")
        api.classes += ClassDto("c1", "Toán 9A", "2026-11-23")
        val vm = vm()
        advanceUntilIdle()

        assertEquals(OnboardingStep.AddStudents, vm.state.value.step)
        vm.skip()
        advanceUntilIdle()

        assertEquals(listOf("add_students" to "skipped"), api.stepCalls)
        assertEquals(OnboardingStep.SetSchedule, vm.state.value.step)
    }

    @Test
    fun `mo lai app voi nhieu lop thi cho chon lop`() = runTest {
        api.onboarding = onboarding("set_schedule")
        api.classes += ClassDto("c1", "Toán 9A", "2026-11-23")
        api.classes += ClassDto("c2", "Toán 9B", "2026-11-23")
        val vm = vm()
        advanceUntilIdle()

        assertEquals(listOf("c1", "c2"), vm.state.value.classChoices.map { it.id })
        assertFalse(vm.state.value.stepLoading)
        assertNull(vm.state.value.classItem)

        vm.chooseClass(ClassDto("c2", "Toán 9B", "2026-11-23"))
        advanceUntilIdle()

        assertEquals("c2", vm.state.value.classItem?.id)
        assertTrue(vm.state.value.classChoices.isEmpty())
    }

    @Test
    fun `chua co lop thi bao chua co lop`() = runTest {
        api.onboarding = onboarding("add_students")
        val vm = vm()
        advanceUntilIdle()

        assertTrue(vm.state.value.noClass)
        assertNull(vm.state.value.classItem)
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
    /** Lỗi mạng xảy ra sau khi server đã tạo lớp (mất phản hồi). */
    var createdButLost = false
    var enrollError: ApiError? = null
    var stepError: ApiError? = null
    var createStudentError: ApiError? = null
    val createCalls = mutableListOf<Pair<String, CreateClassRequest>>()
    val feeCalls = mutableListOf<Triple<String, FeePatch, String>>()
    val stepCalls = mutableListOf<Pair<String, String>>()
    val rulesCalls = mutableListOf<RulesRequest>()
    val studentCalls = mutableListOf<CreateStudentRequest>()
    val enrollCalls = mutableListOf<Pair<String, String>>()

    override suspend fun get() = onboarding

    val stepKeys = mutableListOf<String>()

    override suspend fun setStep(step: String, key: String, body: StepStatusRequest): OnboardingDto {
        stepKeys += key
        stepError?.let { throw it }
        stepCalls += step to body.status
        onboarding = onboarding(ORDER.getOrNull(ORDER.indexOf(step) + 1))
        return onboarding
    }

    override suspend fun classes(status: String, pageSize: Int) = ClassPage(classes.toList())

    override suspend fun createClass(key: String, body: CreateClassRequest): ClassDto {
        createCalls += key to body
        if (classes.any { it.id == body.id }) throw ApiError.Http(ApiError.CONFLICT_STALE, 409)
        if (failCreate-- > 0) {
            if (createdButLost) classes += ClassDto(body.id, body.name, body.startDate)
            throw ApiError.Network
        }
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
        createStudentError?.let { throw it }
        return StudentDto(body.id, body.fullName)
    }

    override suspend fun enroll(classId: String, key: String, body: EnrollRequest) {
        enrollError?.let { throw it }
        enrollCalls += classId to body.studentId
    }
}

private class MemoryDataStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}
