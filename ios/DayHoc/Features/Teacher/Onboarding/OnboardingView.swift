import ContactsUI
import SwiftUI

private let weekdays = ["T2", "T3", "T4", "T5", "T6", "T7", "CN"]
private let durations = [60, 90, 120]
private let subjects = ["Toán", "Ngữ văn", "Tiếng Anh", "Vật lý", "Hóa học", "Sinh học", "Năng khiếu", "Khác"]

/// [T02] Thiết lập lớp học 3 bước (Figma B07–B09).
struct OnboardingView: View {
    @Bindable var model: OnboardingModel
    let onFinished: () -> Void
    let onExit: () -> Void

    @State private var confirmExit = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if model.loading {
                    ProgressView().frame(maxHeight: .infinity)
                } else if model.loadFailed {
                    RetryView(message: "onboarding.loadFailed") { await model.load() }
                } else if let step = model.step {
                    ProgressHeader(step: step)
                    StepBody(model: model, step: step)
                }
            }
            .dhScreen()
            .navigationTitle("onboarding.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("onboarding.exit", systemImage: "xmark") { confirmExit = true }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if let step = model.step, !model.loading {
                        // Bước 1 bắt buộc (US-0.2 AC2).
                        Button("onboarding.skip") { Task { await model.skip() } }
                            .disabled(!step.skippable || model.saving)
                    }
                }
            }
            .alert("onboarding.exitTitle", isPresented: $confirmExit) {
                Button("onboarding.exitStay", role: .cancel) {}
                Button("onboarding.exitLeave", action: onExit)
            } message: {
                Text("onboarding.exitBody")
            }
            .overlay(alignment: .bottom) { Toast(text: $model.message) }
        }
        .task { await model.load() }
        .onChange(of: model.finished) { _, done in if done { onFinished() } }
    }
}

/// "Bước N/3" + thanh 3 đoạn, tô tới bước đang làm.
private struct ProgressHeader: View {
    let step: OnboardingStep
    @Environment(\.dhColors) private var colors

    var body: some View {
        let total = OnboardingStep.allCases.count
        VStack(alignment: .leading, spacing: 8) {
            Text("onboarding.progress \(step.number) \(total)").font(.caption.weight(.medium))
            HStack(spacing: 4) {
                ForEach(1...total, id: \.self) { i in
                    Capsule()
                        .fill(i <= step.number ? colors.primary : colors.surfaceContainerHighest)
                        .frame(height: 4)
                }
            }
        }
        .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
        .padding(.top, 8)
    }
}

private struct StepBody: View {
    @Bindable var model: OnboardingModel
    let step: OnboardingStep

    var body: some View {
        if model.stepLoading {
            ProgressView().frame(maxHeight: .infinity)
        } else if model.stepLoadFailed {
            RetryView(message: "onboarding.loadFailed") { await model.retryStep() }
        } else if step == .createClass, model.classItem != nil {
            // Lớp đã có, đang ghi bước (ghi lỗi thì có thông báo + Thử lại ở dưới).
            VStack {
                Spacer()
                if !model.recordFailed { ProgressView() }
                Spacer()
                RecordNotice(model: model)
            }
        } else if step == .createClass {
            CreateClassStep(model: model)
        } else if model.noClass {
            RetryView(message: "onboarding.scheduleNoClass") { await model.retryStep() }
        } else if !model.classChoices.isEmpty {
            ChooseClass(classes: model.classChoices) { c in Task { await model.chooseClass(c) } }
        } else if model.classItem == nil {
            ProgressView().frame(maxHeight: .infinity)
        } else if step == .addStudents {
            AddStudentsStep(model: model)
        } else {
            ScheduleStep(model: model)
        }
    }
}

// MARK: - Bước 1 (Figma B07)

private struct CreateClassStep: View {
    @Bindable var model: OnboardingModel
    @Environment(\.dhColors) private var colors

    private static let feeOptions: [(type: String, label: LocalizedStringKey)] = [
        (FeeTypes.perSession, "feeType.perSession"),
        (FeeTypes.monthly, "feeType.monthly"),
        (FeeTypes.course, "feeType.course"),
    ]

    var body: some View {
        let f = model.classForm
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Heading(title: "onboarding.stepCreateClass", caption: "onboarding.classCaption")
                DhTextField(label: "onboarding.className", text: $model.classForm.name, prompt: "onboarding.classNameHint")
                HStack(alignment: .top, spacing: 12) {
                    MenuField(
                        label: "onboarding.subject",
                        value: f.subject.isEmpty ? nil : f.subject,
                        placeholder: "onboarding.subjectHint",
                        options: subjects
                    ) { model.classForm.subject = subjects[$0] }
                    MenuField(
                        label: "onboarding.grade",
                        value: f.grade.map(String.init),
                        placeholder: "onboarding.gradeHint",
                        options: (1...12).map(String.init)
                    ) { model.classForm.grade = $0 + 1 }
                }
                Text("onboarding.feeSection").font(.subheadline.bold()).padding(.top, 4)
                ForEach(Self.feeOptions, id: \.type) { option in
                    Button {
                        model.classForm.feeType = option.type
                        // Trọn khóa cần ngày kết thúc: gợi ý sau 90 ngày, giáo viên sửa được.
                        if option.type == FeeTypes.course, model.classForm.endDate == nil {
                            model.classForm.endDate = Calendar.current.date(byAdding: .day, value: 90, to: .now)
                        }
                    } label: {
                        HStack(spacing: 12) {
                            // DS v2: chỉ báo đang chọn màu mực, không dùng lime.
                            Image(systemName: f.feeType == option.type ? "largecircle.fill.circle" : "circle")
                            Text(option.label)
                            Spacer()
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(f.feeType == option.type ? [.isSelected] : [])
                }
                if f.feeType == FeeTypes.course {
                    DatePicker(
                        "onboarding.endDate",
                        selection: Binding(get: { model.classForm.endDate ?? .now }, set: { model.classForm.endDate = $0 }),
                        in: Calendar.current.startOfDay(for: .now)...,
                        displayedComponents: .date
                    )
                }
                DhTextField(label: amountLabel(f.feeType), text: amountBinding, prompt: "0", helper: "onboarding.feeCaption")
                    .keyboardType(.numberPad)
                    .overlay(alignment: .trailing) {
                        Text(verbatim: "đ").foregroundStyle(colors.onSurfaceVariant).padding(.trailing, 12)
                    }
            }
            .padding(TeachHubTokens.Dimens.spacingPage)
        }
        .safeAreaInset(edge: .bottom) {
            ActionBar(title: model.saving ? "onboarding.saving" : "onboarding.continue", enabled: f.valid && !model.saving) {
                await model.createClass()
            }
        }
    }

    private var amountBinding: Binding<String> {
        Binding(
            get: { model.classForm.amount },
            set: { model.classForm.amount = String($0.filter { $0.isASCII && $0.isNumber }.prefix(10)) }
        )
    }

    private func amountLabel(_ type: String) -> LocalizedStringKey {
        switch type {
        case FeeTypes.monthly: "onboarding.feeAmountMonthly"
        case FeeTypes.course: "onboarding.feeAmountCourse"
        default: "onboarding.feeAmountPerSession"
        }
    }
}

// MARK: - Bước 2 (Figma B08)

private struct AddStudentsStep: View {
    @Bindable var model: OnboardingModel
    @Environment(\.dhColors) private var colors

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Heading(title: "onboarding.studentsTitle", caption: "onboarding.studentsCaption")
                DhTextField(
                    label: "onboarding.studentName",
                    text: $model.studentName,
                    prompt: "onboarding.studentNameHint",
                    error: model.nameError ? "students.fullNameRequired" : nil
                )
                .disabled(model.studentBusy)
                HStack(alignment: .bottom, spacing: 8) {
                    DhTextField(
                        label: "onboarding.guardianPhone",
                        text: $model.studentPhone,
                        prompt: "09xx xxx xxx",
                        error: model.phoneError ? "students.phoneInvalid" : nil
                    )
                    .keyboardType(.phonePad)
                    .textContentType(.telephoneNumber)
                    // Chọn 1 số từ danh bạ hệ thống: không cần quyền danh bạ (US-0.2 AC5).
                    Button {
                        ContactPhonePicker.shared.present { model.pickedPhone($0) }
                    } label: {
                        Image(systemName: "person.crop.circle")
                            .font(.title3)
                            .frame(width: 52, height: 52)
                            .overlay(RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.fieldRadius).stroke(colors.outline))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("contacts.pickPhone"))
                    .padding(.bottom, model.phoneError ? 22 : 0)
                }
                .disabled(model.studentBusy)
                DhSecondaryButton(title: model.studentBusy ? "onboarding.saving" : "onboarding.addStudent") {
                    let name = model.studentName.trimmingCharacters(in: .whitespaces)
                    let guardian = String(localized: "onboarding.guardianDefaultName \(name)")
                    Task { await model.addStudent(guardian: guardian) }
                }
                .disabled(model.studentBusy || model.enrollFailed)
                if !model.added.isEmpty {
                    Text("onboarding.addedCount \(model.added.count)").font(.caption.weight(.medium)).padding(.top, 8)
                    ForEach(Array(model.added.enumerated()), id: \.offset) { _, s in AddedRow(student: s) }
                }
            }
            .padding(TeachHubTokens.Dimens.spacingPage)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if model.enrollFailed {
                    Notice(message: "onboarding.studentNotEnrolled") { await model.retryEnroll() }
                }
                RecordNotice(model: model)
                ActionBar(
                    title: "onboarding.studentsContinue \(model.added.count)",
                    enabled: !model.added.isEmpty && !model.studentBusy && !model.enrollFailed
                        && !model.saving && !model.recordFailed
                ) { await model.finishStudents() }
            }
        }
        .alert(
            "students.duplicateTitle",
            isPresented: Binding(get: { model.duplicates != nil }, set: { if !$0 { model.duplicates = nil } }),
            presenting: model.duplicates
        ) { candidates in
            ForEach(candidates) { c in
                Button(c.fullName + " · " + String(localized: "students.duplicateSame")) {
                    Task { await model.pickExisting(c) }
                }
            }
            Button("students.duplicateNew") { Task { await model.confirmNewStudent() } }
            Button("students.duplicateCancel", role: .cancel) {}
        } message: { _ in
            Text("students.duplicateBody")
        }
    }
}

private struct AddedRow: View {
    let student: AddedStudent
    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingContent) {
            Text(initials)
                .font(.subheadline.bold())
                .foregroundStyle(colors.onSecondaryContainer)
                .frame(width: 40, height: 40)
                .background(colors.secondaryContainer, in: Circle())
            VStack(alignment: .leading) {
                Text(student.name)
                if let phone = student.phone {
                    Text("onboarding.addedPhone \(phone)").font(.footnote).foregroundStyle(colors.onSurfaceVariant)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var initials: String {
        let parts = student.name.split(whereSeparator: \.isWhitespace)
        guard let first = parts.first?.first else { return "?" }
        let last = parts.count > 1 ? parts.last?.first.map(String.init) ?? "" : ""
        return (String(first) + last).uppercased()
    }
}

// MARK: - Bước 3 (Figma B09)

private struct ScheduleStep: View {
    @Bindable var model: OnboardingModel
    @Environment(\.dhColors) private var colors

    var body: some View {
        let overlap = findOverlap(model.rows)
        let valid = rowsValid(model.rows)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Heading(title: "onboarding.scheduleTitle", caption: "onboarding.scheduleCaption")
                Text("onboarding.rulesLabel").font(.subheadline.bold())
                HStack(spacing: 8) {
                    Text("onboarding.ruleWeekday").frame(maxWidth: .infinity, alignment: .leading)
                    Text("onboarding.ruleStart").frame(maxWidth: .infinity, alignment: .leading)
                    Text("onboarding.ruleDuration").frame(maxWidth: .infinity, alignment: .leading)
                    if model.rows.count > 1 { Spacer().frame(width: 32) }
                }
                .font(.caption.weight(.medium))
                ForEach(Array(model.rows.enumerated()), id: \.element.id) { index, row in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            CompactMenu(
                                text: row.weekday.map { weekdays[$0 - 1] } ?? String(localized: "onboarding.ruleWeekday"),
                                placeholder: row.weekday == nil,
                                options: weekdays
                            ) { model.rows[index].weekday = $0 + 1 }
                            DatePicker("onboarding.ruleStart", selection: timeBinding(index), displayedComponents: .hourAndMinute)
                                .labelsHidden()
                                .environment(\.locale, Locale(identifier: "vi_VN"))
                                .frame(maxWidth: .infinity, alignment: .leading)
                            CompactMenu(
                                text: String(localized: "onboarding.ruleDurationValue \(row.duration)"),
                                options: durations.map { String(localized: "onboarding.ruleDurationValue \($0)") }
                            ) { model.rows[index].duration = durations[$0] }
                            if model.rows.count > 1 {
                                Button { model.removeRow(row.id) } label: {
                                    Image(systemName: "xmark").frame(width: 32, height: 44)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Text("onboarding.ruleRemove \(index + 1)"))
                            }
                        }
                        if let overlap, overlap.index == index, let day = overlap.other.weekday {
                            Text("onboarding.ruleOverlap \(weekdays[day - 1]) \(overlap.other.start)")
                                .font(.footnote)
                                .foregroundStyle(colors.error)
                        }
                    }
                }
                // Nút phụ trung tính (DS v2); Figma: chép giờ và thời lượng của hàng cuối, thứ để trống.
                DhSecondaryButton(title: "onboarding.ruleAdd", fullWidth: false) { model.addRow() }
                    .disabled(model.rows.count >= OnboardingModel.maxRules)
                if valid, let cls = model.classItem, let first = firstSessionText(cls) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("onboarding.firstSession \(first)").font(.subheadline.bold())
                        Text(summary).font(.footnote)
                    }
                    .padding(TeachHubTokens.Dimens.spacingContent)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(
                        RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.cardRadius)
                            .stroke(colors.outlineVariant, lineWidth: 1.5)
                    )
                    .padding(.top, 8)
                }
            }
            .padding(TeachHubTokens.Dimens.spacingPage)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                RecordNotice(model: model)
                ActionBar(
                    title: model.saving ? "onboarding.saving" : "onboarding.finishSetup",
                    enabled: valid && !model.saving && !model.recordFailed
                ) { await model.saveSchedule() }
            }
        }
    }

    private var rules: [RuleDTO] {
        model.rows.compactMap { r in r.weekday.map { RuleDTO(weekday: $0, startTime: r.start, durationMin: r.duration) } }
    }

    /// "T4 11/11 · 17:30–19:00".
    private func firstSessionText(_ cls: ClassDTO) -> String? {
        guard let start = parseIsoDate(cls.startDate), let first = firstSession(rules, start: start, today: .now) else {
            return nil
        }
        let c = Calendar.current.dateComponents([.day, .month], from: first.date)
        let end = hhmm(minutesOf(first.rule.startTime) + first.rule.durationMin)
        return "\(weekdays[first.rule.weekday - 1]) \(c.day ?? 0)/\(c.month ?? 0) · \(first.rule.startTime)–\(end)"
    }

    private var summary: String {
        rules.sorted { ($0.weekday, minutesOf($0.startTime)) < ($1.weekday, minutesOf($1.startTime)) }
            .map { "\(weekdays[$0.weekday - 1]) \($0.startTime)" }
            .joined(separator: " · ")
    }

    private func timeBinding(_ index: Int) -> Binding<Date> {
        Binding(
            get: {
                let m = minutesOf(model.rows[index].start)
                return Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: .now) ?? .now
            },
            set: {
                let c = Calendar.current.dateComponents([.hour, .minute], from: $0)
                model.rows[index].start = hhmm((c.hour ?? 0) * 60 + (c.minute ?? 0))
            }
        )
    }
}

private struct ChooseClass: View {
    let classes: [ClassDTO]
    let onChoose: (ClassDTO) -> Void

    var body: some View {
        List {
            Section {
                ForEach(classes, id: \.id) { c in Button(c.name) { onChoose(c) } }
            } header: {
                Text("onboarding.scheduleChooseClass")
            }
        }
    }
}

// MARK: - Thành phần chung

private struct Heading: View {
    let title: LocalizedStringKey
    let caption: LocalizedStringKey
    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.title2.bold())
            Text(caption).foregroundStyle(colors.onSurfaceVariant)
        }
        .padding(.bottom, 4)
    }
}

/// Thanh hành động đáy: một nút chính (Figma Bottom Action Bar).
private struct ActionBar: View {
    let title: LocalizedStringKey
    let enabled: Bool
    let action: () async -> Void
    @Environment(\.dhColors) private var colors

    var body: some View {
        DhPrimaryButton(title: title) { Task { await action() } }
            .disabled(!enabled)
            .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
            .padding(.top, 8)
            .padding(.bottom, TeachHubTokens.Dimens.spacingPage)
            .background(colors.scaffoldBackground)
    }
}

private struct RecordNotice: View {
    let model: OnboardingModel

    var body: some View {
        if model.recordFailed {
            Notice(message: "onboarding.saved") { await model.retryRecord() }
        }
    }
}

/// Thông báo "đã lưu nhưng chưa xong" kèm Thử lại, nằm trên thanh hành động.
private struct Notice: View {
    let message: LocalizedStringKey
    let retry: () async -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(message)
            DhSecondaryButton(title: "retry") { Task { await retry() } }
        }
        .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
    }
}

private struct RetryView: View {
    let message: LocalizedStringKey
    let retry: () async -> Void

    var body: some View {
        VStack(spacing: TeachHubTokens.Dimens.spacingContent) {
            Text(message).multilineTextAlignment(.center)
            DhPrimaryButton(title: "retry") { Task { await retry() } }
        }
        .padding(TeachHubTokens.Dimens.spacingPage)
        .frame(maxHeight: .infinity)
    }
}

/// Ô có nhãn phía trên, chạm mở menu chọn (môn, khối).
private struct MenuField: View {
    let label: LocalizedStringKey
    let value: String?
    let placeholder: LocalizedStringKey
    let options: [String]
    let onPick: (Int) -> Void
    @Environment(\.dhColors) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.fieldRadius)
        VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingInline) {
            Text(label).font(.subheadline.weight(.medium))
            Menu {
                ForEach(options.indices, id: \.self) { i in Button(options[i]) { onPick(i) } }
            } label: {
                HStack {
                    if let value {
                        Text(value).foregroundStyle(colors.onSurface)
                    } else {
                        Text(placeholder).foregroundStyle(colors.placeholder)
                    }
                    Spacer()
                    Image(systemName: "chevron.down").foregroundStyle(colors.onSurfaceVariant)
                }
                .padding(TeachHubTokens.Dimens.spacingContent)
                .background(colors.surface, in: shape)
                .overlay(shape.stroke(colors.outline))
            }
        }
    }
}

/// Ô gọn cho hàng lịch (cao 44, viền outline), chạm mở menu.
private struct CompactMenu: View {
    let text: String
    var placeholder = false
    let options: [String]
    let onPick: (Int) -> Void
    @Environment(\.dhColors) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.fieldRadius)
        Menu {
            ForEach(options.indices, id: \.self) { i in Button(options[i]) { onPick(i) } }
        } label: {
            HStack(spacing: 4) {
                Text(text).lineLimit(1).foregroundStyle(placeholder ? colors.onSurfaceVariant : colors.onSurface)
                Spacer(minLength: 0)
                Image(systemName: "chevron.down").font(.caption).foregroundStyle(colors.onSurfaceVariant)
            }
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity, minHeight: 44)
            .overlay(shape.stroke(colors.outline))
        }
    }
}

/// Chọn 1 số điện thoại từ danh bạ hệ thống. `CNContactPickerViewController` chạy ngoài app nên
/// không cần quyền danh bạ; chỉ trả đúng số người dùng chạm.
@MainActor
final class ContactPhonePicker: NSObject, CNContactPickerDelegate {
    static let shared = ContactPhonePicker()
    private var completion: ((String?) -> Void)?

    func present(_ completion: @escaping (String?) -> Void) {
        self.completion = completion
        let picker = CNContactPickerViewController()
        picker.delegate = self
        picker.displayedPropertyKeys = [CNContactPhoneNumbersKey]
        picker.predicateForEnablingContact = NSPredicate(format: "phoneNumbers.@count > 0")
        picker.predicateForSelectionOfProperty = NSPredicate(format: "key == 'phoneNumbers'")
        Self.topController()?.present(picker, animated: true)
    }

    nonisolated func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
        let number = (contactProperty.value as? CNPhoneNumber)?.stringValue
        MainActor.assumeIsolated {
            completion?(number)
            completion = nil
        }
    }

    nonisolated func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
        MainActor.assumeIsolated { completion = nil }
    }

    private static func topController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
