package vn.dayhoc.app.teacher.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhInfoBanner
import vn.dayhoc.app.core.designsystem.DhPrimaryButton
import vn.dayhoc.app.core.designsystem.DhRadio
import vn.dayhoc.app.core.designsystem.DhSecondaryButton
import vn.dayhoc.app.core.designsystem.DhTextButton
import vn.dayhoc.app.core.designsystem.DhTextField
import vn.dayhoc.app.core.designsystem.LocalButtonBorderColor
import vn.dayhoc.app.core.designsystem.TeachHubText
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

private const val TOTAL_STEPS = 3
private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private const val DAY_MS = 86_400_000L
private val DURATIONS = listOf(60, 90, 120)

/** [T02] Thiết lập lớp học 3 bước (Figma B07–B09). Thiết lập bắt buộc, chỉ rời khi hoàn tất. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    val snackText = state.snack?.let { it.text ?: it.res?.let { res -> stringResource(res) } }
    LaunchedEffect(state.snack) {
        if (snackText != null) {
            snackbar.showSnackbar(snackText)
            viewModel.snackShown()
        }
    }
    // Sang bước khác: bỏ snackbar cũ để không che nút của bước mới.
    LaunchedEffect(state.step) { snackbar.currentSnackbarData?.dismiss() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.onboarding_title)) },
                    actions = {
                        val step = state.step
                        // Bước 1 bắt buộc (US-0.2 AC2) nên không có nút bỏ qua; bước 2, 3 mới có (Figma B08, B09).
                        if (step != null && !state.loading && step.skippable) {
                            DhTextButton(stringResource(R.string.onboarding_skip), viewModel::skip, enabled = !state.saving)
                        }
                    },
                )
                // Figma Navigation/App Bar: kẻ dưới 1.5px border/default.
                HorizontalDivider(thickness = 1.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val step = state.step
            when {
                state.loading -> Loading()
                state.loadFailed -> Retry(stringResource(R.string.onboarding_load_failed), viewModel::load)
                step == null -> Loading()
                else -> {
                    ProgressHeader(step)
                    StepBody(state, step, viewModel)
                }
            }
        }
    }
}

/** "Bước N/3" + thanh 3 đoạn, tô tới bước đang làm. */
@Composable
private fun ProgressHeader(step: OnboardingStep) {
    val n = step.ordinal + 1
    Column(Modifier.padding(start = Dimens.spacingPage, end = Dimens.spacingPage, top = 8.dp)) {
        Text(
            stringResource(R.string.onboarding_progress, n, TOTAL_STEPS),
            style = TeachHubText.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(TOTAL_STEPS) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < n) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.StepBody(state: OnboardingUiState, step: OnboardingStep, vm: OnboardingViewModel) {
    when {
        state.stepLoading -> Loading()
        state.stepLoadFailed -> Retry(stringResource(R.string.onboarding_load_failed), vm::retryStep)
        step == OnboardingStep.CreateClass && state.classItem != null -> {
            // Lớp đã có, đang ghi bước (ghi lỗi thì có thông báo + Thử lại ở dưới).
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                if (!state.recordFailed) CircularProgressIndicator()
            }
            RecordNotice(state, vm)
        }
        step == OnboardingStep.CreateClass -> CreateClassStep(state, vm)
        state.noClass -> Retry(stringResource(R.string.onboarding_schedule_no_class), vm::retryStep)
        state.classChoices.isNotEmpty() -> ChooseClass(step, state.classChoices, vm::chooseClass)
        state.classItem == null -> Loading()
        step == OnboardingStep.AddStudents -> AddStudentsStep(state, vm)
        else -> ScheduleStep(state, vm)
    }
}

// ───────────────────────────── Bước 1 (Figma B07) ─────────────────────────────

@Composable
private fun ColumnScope.CreateClassStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    val f = state.classForm
    var sheet by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val subjects = stringArrayResource(R.array.onboarding_subjects).toList()

    ScrollBody {
        Heading(stringResource(R.string.onboarding_step_create_class), stringResource(R.string.onboarding_class_caption))
        DhTextField(
            value = f.name,
            onValueChange = { v -> vm.updateClassForm { it.copy(name = v.take(120)) } },
            label = stringResource(R.string.onboarding_class_name) + " *",
            placeholder = stringResource(R.string.onboarding_class_name_hint),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PickerField(
                value = f.subject,
                label = stringResource(R.string.onboarding_subject) + " *",
                placeholder = stringResource(R.string.onboarding_subject_hint),
                onClick = { sheet = "subject" },
                modifier = Modifier.weight(1f),
            )
            PickerField(
                value = f.grade?.toString().orEmpty(),
                label = stringResource(R.string.onboarding_grade) + " *",
                placeholder = stringResource(R.string.onboarding_grade_hint),
                onClick = { sheet = "grade" },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.onboarding_fee_section) + " *",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(Modifier.selectableGroup()) {
            listOf(
                FeeTypes.PER_SESSION to R.string.fee_type_per_session,
                FeeTypes.MONTHLY to R.string.fee_type_monthly,
                FeeTypes.COURSE to R.string.fee_type_course,
            ).forEach { (type, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .selectable(f.feeType == type, role = Role.RadioButton) {
                            vm.updateClassForm { it.copy(feeType = type) }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DhRadio(selected = f.feeType == type)
                    Text(stringResource(label), style = TeachHubText.body, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        if (f.feeType == FeeTypes.COURSE) {
            PickerField(
                value = f.endDate?.format(DATE).orEmpty(),
                label = stringResource(R.string.onboarding_end_date) + " *",
                placeholder = null,
                onClick = { pickDate = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DhTextField(
            value = f.amount,
            onValueChange = { v -> vm.updateClassForm { it.copy(amount = v.filter(Char::isDigit).take(10)) } },
            label = stringResource(
                when (f.feeType) {
                    FeeTypes.MONTHLY -> R.string.onboarding_fee_amount_monthly
                    FeeTypes.COURSE -> R.string.onboarding_fee_amount_course
                    else -> R.string.onboarding_fee_amount_per_session
                },
            ),
            placeholder = "0",
            suffix = "đ",
            supportingText = stringResource(R.string.onboarding_fee_caption),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    ActionBar(
        text = stringResource(if (state.saving) R.string.onboarding_saving else R.string.onboarding_continue),
        enabled = f.valid && !state.saving,
        onClick = vm::createClass,
    )

    when (sheet) {
        "subject" -> OptionsSheet(stringResource(R.string.onboarding_subject), subjects, { sheet = null }) { v ->
            vm.updateClassForm { it.copy(subject = v) }
        }
        "grade" -> OptionsSheet(stringResource(R.string.onboarding_grade), (1..12).map(Int::toString), { sheet = null }) { v ->
            vm.updateClassForm { it.copy(grade = v.toInt()) }
        }
    }
    if (pickDate) {
        DateDialog(f.endDate, { pickDate = false }) { d -> vm.updateClassForm { it.copy(endDate = d) } }
    }
}

// ───────────────────────────── Bước 2 (Figma B08) ─────────────────────────────

@Composable
private fun ColumnScope.AddStudentsStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    val form = state.students
    val context = LocalContext.current
    // Chọn 1 số từ danh bạ hệ thống: không cần quyền READ_CONTACTS, máy chỉ cho đọc đúng số đã chọn (US-0.2 AC5).
    val pickPhone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        val number = runCatching {
            context.contentResolver.query(uri, arrayOf(Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        vm.pickedPhone(number)
    }
    val guardian = stringResource(R.string.onboarding_guardian_default_name, form.name.trim())

    ScrollBody {
        Heading(stringResource(R.string.onboarding_students_title), stringResource(R.string.onboarding_students_caption))
        DhTextField(
            value = form.name,
            onValueChange = { vm.updateStudent(name = it.take(120)) },
            label = stringResource(R.string.onboarding_student_name) + " *",
            placeholder = stringResource(R.string.onboarding_student_name_hint),
            isError = form.nameError,
            supportingText = if (form.nameError) stringResource(R.string.students_full_name_required) else null,
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DhTextField(
                value = form.phone,
                onValueChange = { vm.updateStudent(phone = it.take(20)) },
                label = stringResource(R.string.onboarding_guardian_phone),
                placeholder = "09xx xxx xxx",
                isError = form.phoneError,
                supportingText = if (form.phoneError) stringResource(R.string.students_phone_invalid) else null,
                enabled = !form.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
            )
            OutlinedIconButton(
                onClick = {
                    try {
                        pickPhone.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI))
                    } catch (e: ActivityNotFoundException) {
                        vm.pickedPhone(null)
                    }
                },
                enabled = !form.busy,
                shape = RoundedCornerShape(Dimens.fieldRadius),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                // Vuông bằng đúng ô nhập bên cạnh (Figma B08); OutlinedTextField có nhãn chừa 8dp phía trên.
                modifier = Modifier.padding(top = 8.dp).size(OutlinedTextFieldDefaults.MinHeight),
            ) {
                Icon(painterResource(R.drawable.ic_address_book), stringResource(R.string.contacts_pick_phone))
            }
        }
        // Đã nhập tên: viền đậm dần và nút nảy nhẹ một lần để báo đây là bước tiếp theo.
        val ready = form.name.isNotBlank() && !form.busy && !form.enrollFailed
        val addBorder by animateColorAsState(
            if (ready) MaterialTheme.colorScheme.onSurface else LocalButtonBorderColor.current,
            label = "addStudentBorder",
        )
        val addScale by animateFloatAsState(
            if (ready) 1f else 0.97f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "addStudentScale",
        )
        CompositionLocalProvider(LocalButtonBorderColor provides addBorder) {
            DhSecondaryButton(
                text = stringResource(if (form.busy) R.string.onboarding_saving else R.string.onboarding_add_student),
                onClick = { vm.addStudent(guardian) },
                enabled = !form.busy && !form.enrollFailed,
                icon = painterResource(R.drawable.ic_add),
                modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = addScale; scaleY = addScale },
            )
        }
        if (form.added.isNotEmpty()) {
            Text(
                stringResource(R.string.onboarding_added_count, form.added.size),
                style = TeachHubText.section,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            form.added.forEach { s -> AddedRow(s) }
        }
    }
    if (form.enrollFailed) Notice(stringResource(R.string.onboarding_student_not_enrolled), vm::retryEnroll)
    RecordNotice(state, vm)
    ActionBar(
        text = stringResource(R.string.onboarding_students_continue, form.added.size),
        enabled = form.added.isNotEmpty() && !form.busy && !form.enrollFailed && !state.saving && !state.recordFailed,
        onClick = vm::finishStudents,
    )

    form.duplicates?.let { candidates -> DuplicateDialog(candidates, vm) }
}

@Composable
private fun AddedRow(s: AddedStudent) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            Alignment.Center,
        ) {
            Text(
                initials(s.name),
                style = TeachHubText.bodyBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column {
            Text(s.name, style = TeachHubText.bodyBold)
            if (s.phone != null) {
                Text(
                    stringResource(R.string.onboarding_added_phone, s.phone),
                    style = TeachHubText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "?"
    return (if (parts.size == 1) parts[0].take(1) else parts.first().take(1) + parts.last().take(1)).uppercase()
}

/** Hồ sơ trùng họ tên (409 `STUDENT_POSSIBLE_DUPLICATE`): chọn đúng em, thêm em mới, hoặc quay lại sửa. */
@Composable
private fun DuplicateDialog(candidates: List<StudentCandidate>, vm: OnboardingViewModel) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.students_duplicate_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.students_duplicate_body))
                candidates.forEach { c ->
                    Text(c.fullName, style = MaterialTheme.typography.titleSmall)
                    listOfNotNull(c.dob, c.school).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · ")) }
                    DhSecondaryButton(
                        text = stringResource(R.string.students_duplicate_same),
                        onClick = { vm.pickExisting(c) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            DhTextButton(stringResource(R.string.students_duplicate_new), vm::confirmNewStudent)
        },
        dismissButton = {
            DhTextButton(stringResource(R.string.students_duplicate_cancel), vm::dismissDuplicates)
        },
    )
}

// ───────────────────────────── Bước 3 (Figma B09) ─────────────────────────────

@Composable
private fun ColumnScope.ScheduleStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    val days = stringArrayResource(R.array.weekday_short)
    val rows = state.rows
    val overlap = findOverlap(rows)
    val valid = rowsValid(rows)
    var timeRow by remember { mutableStateOf<Int?>(null) }
    val durationLabels = DURATIONS.map { stringResource(R.string.onboarding_rule_duration_value, it) }

    ScrollBody {
        Heading(stringResource(R.string.onboarding_schedule_title), stringResource(R.string.onboarding_schedule_caption))
        if (state.pendingCourseFee != null) {
            Text(
                stringResource(R.string.onboarding_course_fee_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.onboarding_rules_label) + " *", style = TeachHubText.label)
        // Figma Schedule/Rule Row: Thứ 80 · Giờ bắt đầu co giãn · Thời lượng 112 · nút xóa 44 (ẩn khi chỉ 1 hàng).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val style = TeachHubText.label
            Text(stringResource(R.string.onboarding_rule_weekday) + " *", style = style, modifier = Modifier.width(RULE_DAY_WIDTH))
            Text(stringResource(R.string.onboarding_rule_start) + " *", style = style, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.onboarding_rule_duration), style = style, modifier = Modifier.width(RULE_DURATION_WIDTH))
            if (rows.size > 1) Spacer(Modifier.width(44.dp))
        }
        rows.forEachIndexed { i, row ->
            // Key theo hàng: xóa hàng giữa không lẫn trạng thái menu của hàng khác.
            key(row.key) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MenuField(
                            text = row.weekday?.let { days[it - 1] } ?: stringResource(R.string.onboarding_rule_weekday),
                            placeholder = row.weekday == null,
                            options = days.toList(),
                            onSelect = { d -> vm.updateRow(i) { it.copy(weekday = d + 1) } },
                            modifier = Modifier.width(RULE_DAY_WIDTH),
                        )
                        CompactField(
                            row.start,
                            onClick = { timeRow = i },
                            modifier = Modifier.weight(1f),
                            leadingIcon = painterResource(R.drawable.ic_clock),
                        )
                        MenuField(
                            text = stringResource(R.string.onboarding_rule_duration_value, row.duration),
                            options = durationLabels,
                            onSelect = { k -> vm.updateRow(i) { it.copy(duration = DURATIONS[k]) } },
                            modifier = Modifier.width(RULE_DURATION_WIDTH),
                        )
                        if (rows.size > 1) {
                            IconButton(onClick = { vm.removeRow(i) }, modifier = Modifier.size(44.dp)) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.onboarding_rule_remove, i + 1))
                            }
                        }
                    }
                    if (overlap?.first == i) {
                        val other = overlap.second
                        Text(
                            stringResource(R.string.onboarding_rule_overlap, days[other.weekday!! - 1], other.start),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        // Nút phụ trung tính (Figma Actions/Button Secondary, size S): Figma chép giờ và thời lượng
        // của hàng cuối, thứ để trống.
        DhSecondaryButton(
            text = stringResource(R.string.onboarding_rule_add),
            onClick = vm::addRow,
            enabled = rows.size < OnboardingViewModel.MAX_RULES,
            icon = painterResource(R.drawable.ic_add),
            small = true,
        )
        val cls = state.classItem
        val rules = rows.mapNotNull { r -> r.weekday?.let { RuleDto(it, r.start, r.duration) } }
        val first = if (valid && cls != null) firstSession(rules, LocalDate.parse(cls.startDate), LocalDate.now()) else null
        if (first != null) {
            val (date, rule) = first
            val whenText = "${days[rule.weekday - 1]} ${date.dayOfMonth}/${date.monthValue} · " +
                "${rule.startTime}–${hhmm(minutesOf(rule.startTime) + rule.durationMin)}"
            DhInfoBanner(
                title = stringResource(R.string.onboarding_first_session, whenText),
                description = rules.sortedWith(compareBy({ it.weekday }, { minutesOf(it.startTime) }))
                    .joinToString(" · ") { "${days[it.weekday - 1]} ${it.startTime}" },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    RecordNotice(state, vm)
    ActionBar(
        text = stringResource(if (state.saving) R.string.onboarding_saving else R.string.onboarding_finish_setup),
        enabled = valid && !state.saving && !state.recordFailed,
        onClick = vm::saveSchedule,
    )

    timeRow?.let { i ->
        rows.getOrNull(i)?.let { row ->
            TimeDialog(row.start, { timeRow = null }) { t -> vm.updateRow(i) { it.copy(start = t) } }
        }
    }
}

// Độ rộng cột Thứ · Giờ · Thời lượng: "90 phút" cần chỗ hơn "T2".
private val RULE_DAY_WIDTH = 80.dp
private val RULE_DURATION_WIDTH = 112.dp

@Composable
private fun ColumnScope.ChooseClass(step: OnboardingStep, classes: List<ClassDto>, onChoose: (ClassDto) -> Unit) {
    ScrollBody {
        if (step == OnboardingStep.SetSchedule) {
            Heading(stringResource(R.string.onboarding_schedule_title), stringResource(R.string.onboarding_schedule_caption))
        } else {
            Heading(stringResource(R.string.onboarding_students_title), stringResource(R.string.onboarding_students_caption))
        }
        Text(stringResource(R.string.onboarding_schedule_choose_class))
        classes.forEach { c ->
            Text(
                c.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { onChoose(c) }.padding(vertical = 14.dp),
            )
        }
    }
}

// ───────────────────────────── Thành phần chung ─────────────────────────────

@Composable
private fun ColumnScope.ScrollBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.spacingPage),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun Heading(title: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Text(title, style = TeachHubText.h1)
        Text(caption, style = TeachHubText.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Thanh hành động đáy: một nút chính (Figma Bottom Action Bar), nền trắng, vạch kẻ trên. */
@Composable
private fun ActionBar(text: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        HorizontalDivider(thickness = 1.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        DhPrimaryButton(
            text = text,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun RecordNotice(state: OnboardingUiState, vm: OnboardingViewModel) {
    if (state.recordFailed) Notice(stringResource(R.string.onboarding_saved), vm::retryRecord)
}

/** Thông báo "đã lưu nhưng chưa xong" kèm Thử lại, nằm trên thanh hành động. */
@Composable
private fun Notice(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = Dimens.spacingPage), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message)
        DhSecondaryButton(stringResource(R.string.retry), onRetry, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ColumnScope.Loading() {
    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ColumnScope.Retry(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(Dimens.spacingPage),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        DhPrimaryButton(stringResource(R.string.retry), onRetry)
    }
}

/** Ô chỉ đọc, chạm thì mở bộ chọn. */
@Composable
private fun PickerField(value: String, label: String, placeholder: String?, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier) {
        DhTextField(
            value = value,
            onValueChange = {},
            label = label,
            placeholder = placeholder,
            readOnly = true,
            trailingIcon = { Icon(painterResource(R.drawable.ic_caret_down), null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().padding(top = 8.dp).clickable(role = Role.Button, onClick = onClick))
    }
}

/** Ô gọn cho hàng lịch (cao 44, viền outline). */
@Composable
private fun CompactField(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    placeholder: Boolean = false,
    leadingIcon: Painter? = null,
) {
    val shape = RoundedCornerShape(Dimens.fieldRadius)
    Surface(
        shape = shape,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.height(44.dp).clip(shape).clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text,
                maxLines = 1,
                style = TeachHubText.body,
                color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(painterResource(R.drawable.ic_caret_down), null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MenuField(
    text: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    placeholder: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        CompactField(text, onClick = { open = true }, modifier = Modifier.fillMaxWidth(), placeholder = placeholder)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = {
                    open = false
                    onSelect(i)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(title: String, options: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.spacingPage, vertical = Dimens.spacingControl),
            )
            options.forEach { o ->
                Text(
                    o,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPick(o)
                            onDismiss()
                        }
                        .padding(horizontal = Dimens.spacingPage, vertical = 14.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val st = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: today.plusDays(90)).toEpochDay() * DAY_MS,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today.toEpochDay() * DAY_MS
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            DhTextButton(stringResource(R.string.ok), {
                st.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / DAY_MS)) }
                onDismiss()
            })
        },
        dismissButton = { DhTextButton(stringResource(R.string.cancel), onDismiss) },
    ) {
        DatePicker(st)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val m = minutesOf(initial)
    val st = rememberTimePickerState(initialHour = m / 60, initialMinute = m % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            DhTextButton(stringResource(R.string.ok), {
                onPick(hhmm(st.hour * 60 + st.minute))
                onDismiss()
            })
        },
        dismissButton = { DhTextButton(stringResource(R.string.cancel), onDismiss) },
        text = { TimePicker(st) },
    )
}
