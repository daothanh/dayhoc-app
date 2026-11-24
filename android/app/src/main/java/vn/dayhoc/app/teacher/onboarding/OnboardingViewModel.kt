package vn.dayhoc.app.teacher.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import vn.dayhoc.app.R
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.apiCall

/** Thông báo một lần (snackbar): chuỗi của app, hoặc lời server trả theo trường. */
data class Snack(@param:StringRes val res: Int? = null, val text: String? = null)

data class ClassForm(
    val name: String = "",
    val subject: String = "",
    val grade: Int? = null,
    val feeType: String = FeeTypes.PER_SESSION,
    val amount: String = "",
    val endDate: LocalDate? = null,
) {
    val valid
        get() = name.isNotBlank() && subject.isNotEmpty() && grade != null &&
            (parseVnd(amount) ?: 0) in 1..1_000_000_000 && (feeType != FeeTypes.COURSE || endDate != null)
}

data class AddedStudent(val name: String, val phone: String?)

data class StudentsForm(
    val name: String = "",
    val phone: String = "",
    val showErrors: Boolean = false,
    val added: List<AddedStudent> = emptyList(),
    /** Đang tạo hoặc ghi danh học viên. */
    val busy: Boolean = false,
    /** Học viên đã tạo nhưng ghi danh vào lớp lỗi: hiện "Thử lại". */
    val enrollFailed: Boolean = false,
    val duplicates: List<StudentCandidate>? = null,
) {
    val normalizedPhone get() = normalizePhone(phone)
    val nameError get() = showErrors && name.isBlank()
    val phoneError get() = showErrors && phone.isNotBlank() && normalizedPhone?.let(::isValidPhone) != true
}

data class OnboardingUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val step: OnboardingStep? = null,
    /** Đã xử lý hết 3 bước: màn đóng và vào không gian giáo viên. */
    val finished: Boolean = false,
    /** Đang ghi bước hoặc đang lưu dữ liệu của bước. */
    val saving: Boolean = false,
    /** Dữ liệu đã lưu nhưng ghi bước lỗi: "Đã lưu. Chưa ghi nhận được bước này." + Thử lại. */
    val recordFailed: Boolean = false,
    val snack: Snack? = null,
    /** Đang tải phần đã lưu của bước (lớp, học viên) trước khi hiện form. */
    val stepLoading: Boolean = false,
    val stepLoadFailed: Boolean = false,
    /** Lớp đang làm. Bước 1: khác `null` = lớp đã có, chỉ còn ghi bước. Bước 2, 3: nhiều lớp mà chưa chọn thì [classChoices] khác rỗng. */
    val classItem: ClassDto? = null,
    val classChoices: List<ClassDto> = emptyList(),
    val noClass: Boolean = false,
    val classForm: ClassForm = ClassForm(),
    val students: StudentsForm = StudentsForm(),
    val rows: List<ScheduleRow> = emptyList(),
    /** Bước 3: học phí Trọn khóa đang chờ lịch của lớp này (nhắc giáo viên: bỏ qua thì chưa ghi học phí). */
    val pendingCourseFee: Long? = null,
)

/**
 * [T02] Onboarding giáo viên 3 bước (US-0.2, Figma B07–B09). Server là nguồn sự thật: mỗi bước lưu
 * khi bấm nút chính rồi ghi `PATCH /onboarding/steps`; mở lại app thì `GET /onboarding` trả bước dở
 * và dữ liệu đã lưu được tải lại. Phần đang gõ mà chưa gửi thì mất (Owner chốt 23/11/2026).
 * Lệnh tạo lớp, học viên gửi `id` do app sinh và giữ `Idempotency-Key` khi thử lại: không tạo trùng.
 */
class OnboardingViewModel(
    private val api: OnboardingApi,
    private val pendingFee: PendingFeeStore,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val keys = IdempotencyKeys()

    /** Lớp của bước 1 đã được server nhận (chỉ còn đặt học phí, ghi bước). */
    private var createdClassId: String? = null
    private var draftClassId: String? = null

    /** Bước 2: học viên đang tạo (id do app sinh) và học viên đã tạo chờ ghi danh. */
    private var draftStudentId: String? = null
    private var pendingStudent: AddedStudent? = null
    private var pendingStudentId: String? = null
    private var guardianName: String = ""

    private var nextRowKey = 0

    init {
        load()
    }

    fun load() {
        _state.update { OnboardingUiState() }
        viewModelScope.launch {
            try {
                show(apiCall { api.get() })
            } catch (e: ApiError) {
                _state.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun snackShown() = _state.update { it.copy(snack = null) }

    fun skip() {
        val step = _state.value.step ?: return
        // Đang thêm học viên dở: chờ xong, kẻo kết quả về sau ghi vào bước kế.
        if (!step.skippable || _state.value.saving || _state.value.students.busy || pendingStudentId != null) return
        mark(step, "skipped")
    }

    /** "Thử lại" khi dữ liệu đã lưu mà ghi bước lỗi. */
    fun retryRecord() {
        if (_state.value.saving) return
        _state.value.step?.let { mark(it, "done") }
    }

    fun retryStep() {
        _state.value.step?.let(::enter)
    }

    // ─────────────────────────── Bước 1 ───────────────────────────

    fun updateClassForm(change: (ClassForm) -> ClassForm) = _state.update { it.copy(classForm = change(it.classForm)) }

    fun createClass() {
        val s = _state.value
        val f = s.classForm
        if (!f.valid || s.saving) return
        val amount = parseVnd(f.amount)!!
        val course = f.feeType == FeeTypes.COURSE
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val id = createdClassId ?: run {
                    val retry = draftClassId != null
                    val draftId = draftClassId ?: UUID.randomUUID().toString().also { draftClassId = it }
                    val body = CreateClassRequest(
                        id = draftId,
                        name = f.name.trim(),
                        subject = f.subject,
                        grade = f.grade!!,
                        mode = "offline",
                        startDate = today().toString(),
                        endDate = if (course) f.endDate.toString() else null,
                    )
                    try {
                        keys.run("class", body) { apiCall { api.createClass(it, body) } }
                    } catch (e: ApiError) {
                        // Lần trước lỗi mạng nhưng server đã tạo lớp với id này; giáo viên sửa form nên body khác
                        // và server trả CONFLICT_STALE: coi như đã tạo, không sinh lớp thứ hai.
                        if (!(retry && e is ApiError.Http && e.code == ApiError.CONFLICT_STALE)) throw e
                    }
                    draftId.also { createdClassId = it }
                }
                if (course) {
                    // Trọn khóa cần lịch để server tính tổng buổi: ghi ở bước 3.
                    if (!pendingFee.set(id, amount)) {
                        _state.update { it.copy(saving = false, snack = Snack(R.string.onboarding_error_generic)) }
                        return@launch
                    }
                } else {
                    val fee = FeePatch(f.feeType, amount)
                    keys.run("fee", id to fee) { apiCall { api.setFee(id, it, fee) } }
                }
            } catch (e: ApiError) {
                // Server từ chối hẳn lệnh tạo: lần bấm sau là lớp mới (id mới).
                if (createdClassId == null && !e.retryable) draftClassId = null
                _state.update { it.copy(saving = false, snack = classError(e)) }
                return@launch
            }
            // Lớp đã có: ẩn form để không tạo lần nữa, chỉ còn ghi bước.
            _state.update { it.copy(classItem = ClassDto(createdClassId!!, f.name.trim(), today().toString())) }
            markNow(OnboardingStep.CreateClass)
        }
    }

    // ─────────────────────────── Bước 2 ───────────────────────────

    fun updateStudent(name: String? = null, phone: String? = null) = setStudents {
        it.copy(name = name ?: it.name, phone = phone ?: it.phone)
    }

    /** Số chọn từ danh bạ; liên hệ không có số dùng được thì báo, vẫn gõ tay được. */
    fun pickedPhone(raw: String?) {
        val phone = normalizePhone(raw)
        if (phone == null) {
            _state.update { it.copy(snack = Snack(R.string.contacts_no_phone)) }
        } else {
            updateStudent(phone = phone)
        }
    }

    /** [guardian] = "Phụ huynh của <tên>": bước 2 chỉ hỏi SĐT (Figma B08). */
    fun addStudent(guardian: String, confirmNew: Boolean = false) {
        val s = _state.value
        val form = s.students
        // Còn học viên đã tạo mà chưa ghi danh: xử lý xong (Thử lại) rồi mới thêm em khác.
        if (form.busy || s.classItem == null || pendingStudentId != null) return
        val phone = form.phone.takeIf { it.isNotBlank() }?.let(::normalizePhone)
        if (form.name.isBlank() || (form.phone.isNotBlank() && phone?.let(::isValidPhone) != true)) {
            setStudents { it.copy(showErrors = true) }
            return
        }
        guardianName = guardian
        val retry = draftStudentId != null
        val id = draftStudentId ?: UUID.randomUUID().toString().also { draftStudentId = it }
        val body = CreateStudentRequest(
            id = id,
            fullName = form.name.trim(),
            guardians = phone?.let { listOf(GuardianInput(guardian, "guardian", it)) },
            confirmNew = if (confirmNew) true else null,
        )
        setStudents { it.copy(busy = true, showErrors = false, duplicates = null) }
        viewModelScope.launch {
            val created = try {
                keys.run("student", body) { apiCall { api.createStudent(it, body) } }
            } catch (e: ApiError) {
                when {
                    // Lần trước lỗi mạng nhưng server đã tạo học viên với id này: ghi danh tiếp, không tạo thêm.
                    retry && e is ApiError.Http && e.code == ApiError.CONFLICT_STALE -> StudentDto(id, body.fullName)
                    e is ApiError.Http && e.code == ApiError.STUDENT_POSSIBLE_DUPLICATE -> {
                        setStudents { it.copy(busy = false, duplicates = candidates(e)) }
                        return@launch
                    }
                    else -> {
                        if (!e.retryable) draftStudentId = null
                        setStudents { it.copy(busy = false) }
                        _state.update { it.copy(snack = studentError(e)) }
                        return@launch
                    }
                }
            }
            draftStudentId = null
            pendingStudentId = created.id
            pendingStudent = AddedStudent(created.fullName, phone)
            enroll()
        }
    }

    /** "Đúng, là em này": ghi danh hồ sơ có sẵn, không tạo mới. */
    fun pickExisting(candidate: StudentCandidate) {
        if (_state.value.students.busy || pendingStudentId != null) return
        draftStudentId = null
        pendingStudentId = candidate.id
        pendingStudent = AddedStudent(candidate.fullName, _state.value.students.normalizedPhone)
        setStudents { it.copy(duplicates = null, busy = true) }
        viewModelScope.launch { enroll() }
    }

    fun confirmNewStudent() = addStudent(guardianName, confirmNew = true)

    fun dismissDuplicates() = setStudents { it.copy(duplicates = null) }

    fun retryEnroll() {
        if (pendingStudentId == null || _state.value.students.busy) return
        setStudents { it.copy(busy = true, enrollFailed = false) }
        viewModelScope.launch { enroll() }
    }

    private suspend fun enroll() {
        val classId = _state.value.classItem?.id
        val studentId = pendingStudentId
        if (classId == null || studentId == null) {
            setStudents { it.copy(busy = false) }
            return
        }
        val body = EnrollRequest(studentId)
        try {
            keys.run("enroll", classId to body) { apiCall { api.enroll(classId, it, body) } }
        } catch (e: ApiError) {
            if (e.retryable) {
                setStudents { it.copy(busy = false, enrollFailed = true) }
            } else {
                // Server từ chối hẳn (hết hạn mức gói, lớp đã lưu trữ…): báo lý do, thôi giữ em này để thêm em khác.
                pendingStudentId = null
                pendingStudent = null
                setStudents { it.copy(busy = false, enrollFailed = false) }
                _state.update { it.copy(snack = studentError(e)) }
            }
            return
        }
        val added = pendingStudent
        pendingStudentId = null
        pendingStudent = null
        // Thêm xong: form trống cho học viên tiếp theo.
        setStudents { StudentsForm(added = it.added + listOfNotNull(added)) }
    }

    fun finishStudents() {
        val s = _state.value
        if (s.students.added.isEmpty() || s.students.busy || pendingStudentId != null || s.saving) return
        mark(OnboardingStep.AddStudents, "done")
    }

    // ─────────────────────────── Bước 3 ───────────────────────────

    fun chooseClass(c: ClassDto) {
        val step = _state.value.step ?: return
        _state.update { it.copy(classChoices = emptyList()) }
        useClass(step, c)
    }

    fun updateRow(index: Int, change: (ScheduleRow) -> ScheduleRow) =
        _state.update { s -> s.copy(rows = s.rows.mapIndexed { i, r -> if (i == index) change(r) else r }) }

    /** Figma: hàng mới chép giờ và thời lượng của hàng cuối, thứ để trống; tối đa 14 buổi một tuần. */
    fun addRow() = _state.update { s ->
        if (s.rows.size >= MAX_RULES) return@update s
        val last = s.rows.lastOrNull()
        s.copy(rows = s.rows + ScheduleRow(nextRowKey++, null, last?.start ?: "17:30", last?.duration ?: 90))
    }

    fun removeRow(index: Int) = _state.update { s ->
        if (s.rows.size <= 1) s else s.copy(rows = s.rows.filterIndexed { i, _ -> i != index })
    }

    fun saveSchedule() {
        val s = _state.value
        val cls = s.classItem ?: return
        if (s.saving || !rowsValid(s.rows)) return
        val body = RulesRequest(s.rows.map { RuleDto(it.weekday!!, it.start, it.duration) })
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                keys.run("rules", cls.id to body) { apiCall { api.putRules(cls.id, it, body) } }
                // Học phí Trọn khóa chọn ở bước 1 ghi cùng lịch (cần lịch để tính).
                pendingFee.get(cls.id)?.let { amount ->
                    val fee = FeePatch(FeeTypes.COURSE, amount)
                    keys.run("fee", cls.id to fee) { apiCall { api.setFee(cls.id, it, fee) } }
                    pendingFee.clear()
                }
            } catch (e: ApiError) {
                _state.update { it.copy(saving = false, snack = classError(e)) }
                return@launch
            }
            markNow(OnboardingStep.SetSchedule)
        }
    }

    // ─────────────────────────── Chung ───────────────────────────

    private fun mark(step: OnboardingStep, status: String) {
        _state.update { it.copy(saving = true, recordFailed = false) }
        viewModelScope.launch {
            try {
                show(setStep(step, status))
            } catch (e: ApiError) {
                // Bỏ qua lỗi: snackbar. Ghi `done` lỗi (dữ liệu đã lưu): thông báo kèm Thử lại.
                _state.update {
                    if (status == "done") it.copy(saving = false, recordFailed = true) else it.copy(saving = false, snack = stepError(e))
                }
            }
        }
    }

    private suspend fun markNow(step: OnboardingStep) {
        _state.update { it.copy(saving = true, recordFailed = false) }
        try {
            show(setStep(step, "done"))
        } catch (e: ApiError) {
            _state.update { it.copy(saving = false, recordFailed = true) }
        }
    }

    /** `PATCH /onboarding/steps` bắt buộc `Idempotency-Key`; thử lại cùng bước, cùng trạng thái dùng lại khóa. */
    private suspend fun setStep(step: OnboardingStep, status: String): OnboardingDto {
        val body = StepStatusRequest(status)
        return keys.run("step", step to body) { apiCall { api.setStep(step.wire, it, body) } }
    }

    private fun show(onboarding: OnboardingDto) {
        val step = onboarding.current
        val changed = step != _state.value.step
        _state.update {
            it.copy(loading = false, loadFailed = false, saving = false, recordFailed = false, step = step, finished = step == null)
        }
        if (step != null && changed) enter(step)
    }

    /** Vào một bước: tải lại phần server đã lưu cho bước đó. */
    private fun enter(step: OnboardingStep) {
        _state.update {
            it.copy(
                stepLoading = true,
                stepLoadFailed = false,
                classItem = null,
                classChoices = emptyList(),
                noClass = false,
                students = StudentsForm(),
            )
        }
        viewModelScope.launch {
            val classes = try {
                apiCall { api.classes() }.items
            } catch (e: ApiError) {
                _state.update { it.copy(stepLoading = false, stepLoadFailed = true) }
                return@launch
            }
            when {
                step == OnboardingStep.CreateClass -> {
                    // Lớp đã tạo mà chưa ghi được bước (kill app giữa chừng): chỉ ghi lại bước, không tạo lớp thứ hai.
                    _state.update { it.copy(stepLoading = false, classItem = classes.firstOrNull()) }
                    if (classes.isNotEmpty()) markNow(OnboardingStep.CreateClass)
                }
                classes.isEmpty() -> _state.update { it.copy(stepLoading = false, noClass = true) }
                classes.size == 1 -> useClass(step, classes.single())
                else -> _state.update { it.copy(stepLoading = false, classChoices = classes) }
            }
        }
    }

    private fun useClass(step: OnboardingStep, cls: ClassDto) {
        when (step) {
            OnboardingStep.SetSchedule -> _state.update {
                // Figma: mặc định "T2 · 17:30 · 90 phút".
                val rows = cls.scheduleRules.ifEmpty { listOf(RuleDto(1, "17:30", 90)) }
                    .map { r -> ScheduleRow(nextRowKey++, r.weekday, r.startTime, r.durationMin) }
                it.copy(stepLoading = false, classItem = cls, rows = rows)
            }.also { viewModelScope.launch { _state.update { it.copy(pendingCourseFee = pendingFee.get(cls.id)) } } }
            OnboardingStep.AddStudents -> {
                _state.update { it.copy(stepLoading = true, classItem = cls) }
                viewModelScope.launch {
                    try {
                        val added = apiCall { api.students(cls.id) }.items.map { st ->
                            AddedStudent(st.fullName, st.guardians.firstNotNullOfOrNull { g -> g.phone })
                        }
                        _state.update { it.copy(stepLoading = false, students = StudentsForm(added = added)) }
                    } catch (e: ApiError) {
                        _state.update { it.copy(stepLoading = false, stepLoadFailed = true) }
                    }
                }
            }
            OnboardingStep.CreateClass -> Unit
        }
    }

    private fun setStudents(change: (StudentsForm) -> StudentsForm) =
        _state.update { it.copy(students = change(it.students)) }

    private fun candidates(e: ApiError.Http): List<StudentCandidate> = try {
        (e.details["candidates"] as? JsonArray)
            ?.map { candidateJson.decodeFromJsonElement(StudentCandidate.serializer(), it) }
            .orEmpty()
    } catch (ex: IllegalArgumentException) {
        emptyList()
    }

    private fun classError(e: ApiError): Snack =
        (e as? ApiError.Http)?.firstFieldError()?.let { Snack(text = it) } ?: stepError(e)

    private fun studentError(e: ApiError): Snack = when {
        e is ApiError.Http && e.code == ApiError.PLAN_LIMIT_EXCEEDED -> Snack(R.string.students_error_limit)
        e is ApiError.Http && e.httpStatus == 422 -> e.firstFieldError()?.let { Snack(text = it) }
            ?: Snack(R.string.onboarding_error_validation)
        else -> stepError(e)
    }

    private fun stepError(e: ApiError) = Snack(
        when {
            e == ApiError.Network || e == ApiError.Timeout -> R.string.error_network
            e is ApiError.Http && e.httpStatus == 409 -> R.string.onboarding_error_busy
            e is ApiError.Http && e.httpStatus == 422 -> R.string.onboarding_error_validation
            else -> R.string.onboarding_error_generic
        },
    )

    companion object {
        const val MAX_RULES = 14
        private val candidateJson = Json { ignoreUnknownKeys = true }
    }
}

/**
 * `Idempotency-Key` theo lệnh ghi: thử lại cùng nội dung dùng lại khóa (server phát lại kết quả, không ghi hai lần);
 * nội dung đổi, server từ chối hẳn, hoặc thành công thì lần sau là khóa mới.
 */
internal class IdempotencyKeys {
    private val pending = mutableMapOf<String, Pair<Any, String>>()

    suspend fun <T> run(op: String, payload: Any, call: suspend (key: String) -> T): T {
        val key = pending[op]?.takeIf { it.first == payload }?.second ?: UUID.randomUUID().toString()
        pending[op] = payload to key
        return try {
            call(key).also { pending.remove(op) }
        } catch (e: ApiError) {
            if (!e.retryable) pending.remove(op)
            throw e
        }
    }
}
