import Foundation
import Observation

struct ClassForm: Equatable {
    var name = ""
    var subject = ""
    var grade: Int?
    var feeType = FeeTypes.perSession
    var amount = ""
    var endDate: Date?

    var valid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && !subject.isEmpty && grade != nil
            && (1...1_000_000_000).contains(parseVnd(amount) ?? 0)
            && (feeType != FeeTypes.course || endDate != nil)
    }
}

struct AddedStudent: Equatable {
    let name: String
    let phone: String?
}

/// [T02] Onboarding giáo viên 3 bước (US-0.2, Figma B07–B09). Server là nguồn sự thật: mỗi bước lưu
/// khi bấm nút chính rồi ghi `PATCH /onboarding/steps`; mở lại app thì `GET /onboarding` trả bước dở
/// và dữ liệu đã lưu được tải lại. Phần đang gõ mà chưa gửi thì mất (Owner chốt 23/11/2026).
/// Lệnh tạo lớp, học viên gửi `id` do app sinh và giữ `Idempotency-Key` khi thử lại: không tạo trùng.
@Observable @MainActor
final class OnboardingModel {
    static let maxRules = 14

    private(set) var loading = true
    private(set) var loadFailed = false
    private(set) var step: OnboardingStep?
    /// Đã xử lý hết 3 bước: màn đóng và vào không gian giáo viên.
    private(set) var finished = false
    /// Đang ghi bước hoặc đang lưu dữ liệu của bước.
    private(set) var saving = false
    /// Dữ liệu đã lưu nhưng ghi bước lỗi: "Đã lưu. Chưa ghi nhận được bước này." + Thử lại.
    private(set) var recordFailed = false
    /// Thông báo một lần (toast).
    var message: String?
    private(set) var stepLoading = false
    private(set) var stepLoadFailed = false
    /// Lớp đang làm. Bước 1: khác `nil` = lớp đã có, chỉ còn ghi bước.
    /// Bước 2, 3: nhiều lớp mà chưa chọn thì `classChoices` khác rỗng.
    private(set) var classItem: ClassDTO?
    private(set) var classChoices: [ClassDTO] = []
    private(set) var noClass = false

    var classForm = ClassForm()

    // Bước 2
    var studentName = ""
    var studentPhone = ""
    private(set) var showStudentErrors = false
    private(set) var added: [AddedStudent] = []
    private(set) var studentBusy = false
    private(set) var enrollFailed = false
    var duplicates: [StudentCandidate]?

    // Bước 3
    var rows: [ScheduleRow] = []
    /// Học phí Trọn khóa đang chờ lịch của lớp này (nhắc giáo viên: bỏ qua thì chưa ghi học phí).
    private(set) var pendingCourseFee: Int?

    private let api: OnboardingAPI
    private let pendingFee: PendingFeeStore
    private let today: () -> Date
    /// Mở thẳng một bước đã bỏ qua (banner ở T03); chỉ áp cho lần tải đầu, sau đó theo server.
    private let startStep: OnboardingStep?
    @ObservationIgnored private let keys = IdempotencyKeys()
    @ObservationIgnored private var createdClassId: String?
    @ObservationIgnored private var draftClassId: String?
    @ObservationIgnored private var draftStudentId: String?
    @ObservationIgnored private var pendingStudentId: String?
    @ObservationIgnored private var pendingStudent: AddedStudent?
    @ObservationIgnored private var guardianName = ""
    @ObservationIgnored private var nextRowId = 0

    init(
        api: OnboardingAPI,
        pendingFee: PendingFeeStore = PendingFeeStore(),
        startStep: OnboardingStep? = nil,
        today: @escaping () -> Date = { .now }
    ) {
        self.api = api
        self.startStep = startStep
        self.pendingFee = pendingFee
        self.today = today
    }

    func load() async {
        loading = true
        loadFailed = false
        do {
            await show(try await api.get(), override: startStep)
        } catch is CancellationError {
            // View dựng lại hủy `.task`: lần chạy mới sẽ tải, không báo lỗi.
        } catch {
            loading = false
            loadFailed = true
        }
    }

    func skip() async {
        // Đang thêm học viên dở: chờ xong, kẻo kết quả về sau ghi vào bước kế.
        guard let step, step.skippable, !saving, !studentBusy, pendingStudentId == nil else { return }
        await mark(step, "skipped")
    }

    /// "Thử lại" khi dữ liệu đã lưu mà ghi bước lỗi.
    func retryRecord() async {
        guard !saving, let step else { return }
        await mark(step, "done")
    }

    func retryStep() async {
        if let step { await enter(step) }
    }

    // MARK: Bước 1

    func createClass() async {
        let f = classForm
        guard f.valid, !saving, let amount = parseVnd(f.amount), let grade = f.grade else { return }
        let course = f.feeType == FeeTypes.course
        saving = true
        let id: String
        do {
            if let createdClassId {
                id = createdClassId
            } else {
                let retry = draftClassId != nil
                let draftId = draftClassId ?? UUID().uuidString.lowercased()
                draftClassId = draftId
                let body = CreateClassRequest(
                    id: draftId,
                    name: f.name.trimmingCharacters(in: .whitespaces),
                    subject: f.subject,
                    grade: grade,
                    mode: "offline",
                    startDate: isoDate(today()),
                    endDate: course ? f.endDate.map(isoDate) : nil
                )
                let api = api
                do {
                    try await keys.run("class", body) { try await api.createClass(body, key: $0) }
                } catch let error as APIError where retry && error.code == "CONFLICT_STALE" {
                    // Lần trước lỗi mạng nhưng server đã tạo lớp với id này; giáo viên sửa form nên body khác
                    // và server trả CONFLICT_STALE: coi như đã tạo, không sinh lớp thứ hai.
                }
                createdClassId = draftId
                id = draftId
            }
            if course {
                // Trọn khóa cần lịch để server tính tổng buổi: ghi ở bước 3.
                pendingFee.set(classId: id, amount: amount)
            } else {
                let fee = FeePatch(feeType: f.feeType, feeAmount: amount)
                let api = api
                try await keys.run("fee", "\(id)|\(fee)") { try await api.setFee(classId: id, fee, key: $0) }
            }
        } catch {
            // Server từ chối hẳn lệnh tạo: lần bấm sau là lớp mới (id mới).
            if createdClassId == nil, (error as? APIError)?.retryable != true { draftClassId = nil }
            saving = false
            message = Self.classError(error)
            return
        }
        // Lớp đã có: ẩn form để không tạo lần nữa, chỉ còn ghi bước.
        classItem = ClassDTO(id: id, name: f.name.trimmingCharacters(in: .whitespaces), startDate: isoDate(today()))
        await mark(.createClass, "done")
    }

    // MARK: Bước 2

    var normalizedPhone: String? { normalizePhone(studentPhone) }
    var nameError: Bool { showStudentErrors && studentName.trimmingCharacters(in: .whitespaces).isEmpty }
    var phoneError: Bool {
        showStudentErrors && !studentPhone.trimmingCharacters(in: .whitespaces).isEmpty
            && normalizedPhone.map(isValidPhone) != true
    }

    /// Số chọn từ danh bạ; liên hệ không có số dùng được thì báo, vẫn gõ tay được.
    func pickedPhone(_ raw: String?) {
        if let phone = normalizePhone(raw) {
            studentPhone = phone
        } else {
            message = String(localized: "contacts.noPhone")
        }
    }

    /// `guardian` = "Phụ huynh của <tên>": bước 2 chỉ hỏi SĐT (Figma B08).
    func addStudent(guardian: String, confirmNew: Bool = false) async {
        // Còn học viên đã tạo mà chưa ghi danh: xử lý xong (Thử lại) rồi mới thêm em khác.
        guard !studentBusy, classItem != nil, pendingStudentId == nil else { return }
        let name = studentName.trimmingCharacters(in: .whitespaces)
        let typed = !studentPhone.trimmingCharacters(in: .whitespaces).isEmpty
        let phone = typed ? normalizedPhone : nil
        guard !name.isEmpty, !typed || phone.map(isValidPhone) == true else {
            showStudentErrors = true
            return
        }
        guardianName = guardian
        let retry = draftStudentId != nil
        let id = draftStudentId ?? UUID().uuidString.lowercased()
        draftStudentId = id
        let body = CreateStudentRequest(
            id: id,
            fullName: name,
            guardians: phone.map { [GuardianInput(fullName: guardian, relation: "guardian", phone: $0)] },
            confirmNew: confirmNew ? true : nil
        )
        studentBusy = true
        showStudentErrors = false
        duplicates = nil
        let created: StudentDTO
        do {
            let api = api
            created = try await keys.run("student", body) { try await api.createStudent(body, key: $0) }
        } catch let error as APIError where retry && error.code == "CONFLICT_STALE" {
            // Lần trước lỗi mạng nhưng server đã tạo học viên với id này: ghi danh tiếp, không tạo thêm.
            created = StudentDTO(id: id, fullName: name, guardians: nil)
        } catch let error as APIError where error.code == "STUDENT_POSSIBLE_DUPLICATE" {
            studentBusy = false
            duplicates = Self.candidates(error)
            return
        } catch {
            if (error as? APIError)?.retryable != true { draftStudentId = nil }
            studentBusy = false
            message = Self.studentError(error)
            return
        }
        draftStudentId = nil
        pendingStudentId = created.id
        pendingStudent = AddedStudent(name: created.fullName, phone: phone)
        await enroll()
    }

    /// "Đúng, là em này": ghi danh hồ sơ có sẵn, không tạo mới.
    func pickExisting(_ candidate: StudentCandidate) async {
        guard !studentBusy, pendingStudentId == nil else { return }
        draftStudentId = nil
        pendingStudentId = candidate.id
        pendingStudent = AddedStudent(name: candidate.fullName, phone: normalizedPhone)
        duplicates = nil
        studentBusy = true
        await enroll()
    }

    func confirmNewStudent() async { await addStudent(guardian: guardianName, confirmNew: true) }

    func retryEnroll() async {
        guard pendingStudentId != nil, !studentBusy else { return }
        studentBusy = true
        enrollFailed = false
        await enroll()
    }

    private func enroll() async {
        guard let classId = classItem?.id, let studentId = pendingStudentId else {
            studentBusy = false
            return
        }
        do {
            let api = api
            try await keys.run("enroll", "\(classId)|\(studentId)") {
                try await api.enroll(classId: classId, studentId: studentId, key: $0)
            }
        } catch {
            studentBusy = false
            if (error as? APIError)?.retryable == false {
                // Server từ chối hẳn (hết hạn mức gói, lớp đã lưu trữ…): báo lý do, thôi giữ em này để thêm em khác.
                pendingStudentId = nil
                pendingStudent = nil
                message = Self.studentError(error)
            } else {
                enrollFailed = true
            }
            return
        }
        if let pendingStudent { added.append(pendingStudent) }
        pendingStudentId = nil
        pendingStudent = nil
        // Thêm xong: form trống cho học viên tiếp theo.
        studentName = ""
        studentPhone = ""
        studentBusy = false
    }

    func finishStudents() async {
        guard !added.isEmpty, !studentBusy, pendingStudentId == nil, !saving else { return }
        await mark(.addStudents, "done")
    }

    // MARK: Bước 3

    func chooseClass(_ c: ClassDTO) async {
        guard let step else { return }
        classChoices = []
        await use(c, for: step)
    }

    /// Figma: hàng mới chép giờ và thời lượng của hàng cuối, thứ để trống; tối đa 14 buổi một tuần.
    func addRow() {
        guard rows.count < Self.maxRules else { return }
        rows.append(ScheduleRow(id: nextId(), weekday: nil, start: rows.last?.start ?? "17:30", duration: rows.last?.duration ?? 90))
    }

    func removeRow(_ id: Int) {
        guard rows.count > 1 else { return }
        rows.removeAll { $0.id == id }
    }

    func saveSchedule() async {
        guard let cls = classItem, !saving, rowsValid(rows) else { return }
        let rules = rows.map { RuleDTO(weekday: $0.weekday ?? 1, startTime: $0.start, durationMin: $0.duration) }
        saving = true
        do {
            let api = api
            try await keys.run("rules", "\(cls.id)|\(rules)") { try await api.putRules(classId: cls.id, rules, key: $0) }
            // Học phí Trọn khóa chọn ở bước 1 ghi cùng lịch (cần lịch để tính).
            if let amount = pendingFee.get(classId: cls.id) {
                let fee = FeePatch(feeType: FeeTypes.course, feeAmount: amount)
                try await keys.run("fee", "\(cls.id)|\(fee)") { try await api.setFee(classId: cls.id, fee, key: $0) }
                pendingFee.clear()
            }
        } catch {
            saving = false
            message = Self.classError(error)
            return
        }
        await mark(.setSchedule, "done")
    }

    // MARK: Chung

    private func mark(_ step: OnboardingStep, _ status: String) async {
        saving = true
        recordFailed = false
        do {
            // `PATCH /onboarding/steps` bắt buộc `Idempotency-Key`; thử lại cùng bước, cùng trạng thái dùng lại khóa.
            let api = api
            await show(try await keys.run("step", "\(step.rawValue)|\(status)") { try await api.setStep(step, status, key: $0) })
        } catch {
            saving = false
            // Bỏ qua lỗi: toast. Ghi `done` lỗi (dữ liệu đã lưu): thông báo kèm Thử lại.
            if status == "done" { recordFailed = true } else { message = Self.stepError(error) }
        }
    }

    private func show(_ onboarding: OnboardingDTO, override: OnboardingStep? = nil) async {
        let next = override ?? onboarding.current
        let changed = next != step
        loading = false
        loadFailed = false
        saving = false
        recordFailed = false
        step = next
        finished = next == nil
        if let next, changed { await enter(next) }
    }

    /// Vào một bước: tải lại phần server đã lưu cho bước đó.
    private func enter(_ step: OnboardingStep) async {
        stepLoading = true
        stepLoadFailed = false
        classItem = nil
        classChoices = []
        noClass = false
        added = []
        let classes: [ClassDTO]
        do {
            classes = try await api.classes()
        } catch {
            stepLoading = false
            stepLoadFailed = true
            return
        }
        switch step {
        case .createClass:
            // Lớp đã tạo mà chưa ghi được bước (kill app giữa chừng): chỉ ghi lại bước, không tạo lớp thứ hai.
            stepLoading = false
            classItem = classes.first
            if !classes.isEmpty { await mark(.createClass, "done") }
        default:
            if classes.isEmpty {
                stepLoading = false
                noClass = true
            } else if classes.count == 1 {
                await use(classes[0], for: step)
            } else {
                stepLoading = false
                classChoices = classes
            }
        }
    }

    private func use(_ cls: ClassDTO, for step: OnboardingStep) async {
        classItem = cls
        switch step {
        case .setSchedule:
            // Figma: mặc định "T2 · 17:30 · 90 phút".
            let rules = cls.scheduleRules.isEmpty ? [RuleDTO(weekday: 1, startTime: "17:30", durationMin: 90)] : cls.scheduleRules
            rows = rules.map { ScheduleRow(id: nextId(), weekday: $0.weekday, start: $0.startTime, duration: $0.durationMin) }
            pendingCourseFee = pendingFee.get(classId: cls.id)
            stepLoading = false
        case .addStudents:
            stepLoading = true
            do {
                added = try await api.students(classId: cls.id).map {
                    AddedStudent(name: $0.fullName, phone: $0.guardians?.compactMap(\.phone).first)
                }
                stepLoading = false
            } catch {
                stepLoading = false
                stepLoadFailed = true
            }
        case .createClass:
            stepLoading = false
        }
    }

    private func nextId() -> Int {
        nextRowId += 1
        return nextRowId
    }

    private static func candidates(_ error: APIError) -> [StudentCandidate] {
        struct Body: Decodable {
            struct Details: Decodable { let candidates: [StudentCandidate]? }
            let details: Details?
        }
        return error.body.flatMap { try? JSONDecoder().decode(Body.self, from: $0) }?.details?.candidates ?? []
    }

    private static func classError(_ error: any Error) -> String {
        (error as? APIError)?.firstFieldError ?? stepError(error)
    }

    private static func studentError(_ error: any Error) -> String {
        guard let e = error as? APIError else { return stepError(error) }
        if e.code == "PLAN_LIMIT_EXCEEDED" { return String(localized: "students.errorLimit") }
        if e.status == 422 { return e.firstFieldError ?? String(localized: "onboarding.errorValidation") }
        return stepError(error)
    }

    private static func stepError(_ error: any Error) -> String {
        let e = error as? APIError
        if e?.kind == .offline || e?.kind == .timeout { return String(localized: "error.offline") }
        switch e?.status {
        case 409: return String(localized: "onboarding.errorBusy")
        case 422: return String(localized: "onboarding.errorValidation")
        default: return String(localized: "onboarding.errorGeneric")
        }
    }
}

/// `Idempotency-Key` theo lệnh ghi: thử lại cùng nội dung dùng lại khóa (server phát lại kết quả, không ghi hai lần);
/// nội dung đổi, server từ chối hẳn, hoặc thành công thì lần sau là khóa mới.
@MainActor
final class IdempotencyKeys {
    private var pending: [String: (payload: String, key: String)] = [:]

    func run<T>(_ op: String, _ payload: Any, _ call: (String) async throws -> T) async throws -> T {
        let signature = String(describing: payload)
        let key = pending[op].flatMap { $0.payload == signature ? $0.key : nil } ?? UUID().uuidString.lowercased()
        pending[op] = (signature, key)
        do {
            let result = try await call(key)
            pending[op] = nil
            return result
        } catch {
            if (error as? APIError)?.retryable != true { pending[op] = nil }
            throw error
        }
    }
}
