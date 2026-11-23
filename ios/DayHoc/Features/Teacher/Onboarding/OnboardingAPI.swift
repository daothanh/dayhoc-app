import Foundation

// DTO viết tay theo teachhub-backend/contracts/openapi.json (tag onboarding, classes, students).

/// Ba bước còn lại từ 22/11/2026; server vẫn trả `payout_account`, `compliance_checklist` và app bỏ qua.
enum OnboardingStep: String, CaseIterable, Sendable {
    case createClass = "create_class"
    case addStudents = "add_students"
    case setSchedule = "set_schedule"

    /// Bước 1 không được bỏ qua (US-0.2 AC2).
    var skippable: Bool { self != .createClass }
    var number: Int { (Self.allCases.firstIndex(of: self) ?? 0) + 1 }
}

struct OnboardingDTO: Decodable, Sendable {
    struct Step: Decodable, Sendable {
        let step: String
        let status: String
    }

    let steps: [Step]
    let currentStep: String?

    /// Bước đang dở trong 3 bước app biết; `nil` khi đã xử lý hết.
    var current: OnboardingStep? {
        currentStep.flatMap(OnboardingStep.init(rawValue:))
            ?? steps.first { $0.status == "pending" }.flatMap { OnboardingStep(rawValue: $0.step) }
    }
}

struct RuleDTO: Codable, Equatable, Sendable {
    let weekday: Int
    let startTime: String
    let durationMin: Int
}

struct ClassDTO: Decodable, Equatable, Sendable {
    let id: String
    let name: String
    let startDate: String
    var endDate: String?
    var scheduleRules: [RuleDTO] = []

    enum CodingKeys: String, CodingKey { case id, name, startDate, endDate, scheduleRules }

    init(id: String, name: String, startDate: String, endDate: String? = nil, scheduleRules: [RuleDTO] = []) {
        self.id = id
        self.name = name
        self.startDate = startDate
        self.endDate = endDate
        self.scheduleRules = scheduleRules
    }

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        startDate = try c.decode(String.self, forKey: .startDate)
        endDate = try c.decodeIfPresent(String.self, forKey: .endDate)
        scheduleRules = try c.decodeIfPresent([RuleDTO].self, forKey: .scheduleRules) ?? []
    }
}

struct CreateClassRequest: Encodable, Equatable, Sendable {
    let id: String
    let name: String
    let subject: String
    let grade: Int
    let mode: String
    let startDate: String
    let endDate: String?
}

struct FeePatch: Encodable, Equatable, Sendable {
    let feeType: String
    let feeAmount: Int
}

struct GuardianInput: Encodable, Equatable, Sendable {
    let fullName: String
    let relation: String
    let phone: String
}

struct CreateStudentRequest: Encodable, Equatable, Sendable {
    let id: String
    let fullName: String
    let guardians: [GuardianInput]?
    let confirmNew: Bool?
}

struct StudentDTO: Decodable, Sendable {
    struct Guardian: Decodable, Sendable { let phone: String? }
    let id: String
    let fullName: String
    let guardians: [Guardian]?
}

/// Hồ sơ trùng khả nghi trong `details.candidates` của lỗi `STUDENT_POSSIBLE_DUPLICATE`.
struct StudentCandidate: Decodable, Equatable, Identifiable, Sendable {
    let id: String
    let fullName: String
    let dob: String?
    let school: String?
}

enum FeeTypes {
    static let perSession = "per_session"
    static let monthly = "monthly"
    static let course = "course"
}

private struct Page<Item: Decodable & Sendable>: Decodable, Sendable { let items: [Item] }
private struct StepStatus: Encodable, Sendable { let status: String }
private struct RulesRequest: Encodable, Sendable { let rules: [RuleDTO] }
private struct EnrollRequest: Encodable, Sendable { let studentId: String }

/// Các lệnh của onboarding. Lệnh ghi nhận `Idempotency-Key` do model giữ khi thử lại.
struct OnboardingAPI: Sendable {
    let api: APIClient

    func get() async throws -> OnboardingDTO { try await api.request("GET", "onboarding") }

    func setStep(_ step: OnboardingStep, _ status: String, key: String) async throws -> OnboardingDTO {
        try await api.request(
            "PATCH", "onboarding/steps/\(step.rawValue)",
            body: StepStatus(status: status),
            headers: ["Idempotency-Key": key]
        )
    }

    func classes() async throws -> [ClassDTO] {
        let page: Page<ClassDTO> = try await api.request(
            "GET", "classes",
            query: [URLQueryItem(name: "status", value: "active"), URLQueryItem(name: "pageSize", value: "100")]
        )
        return page.items
    }

    /// `id` do app sinh: gửi lại sau khi mất mạng hoặc kill app không tạo lớp thứ hai.
    func createClass(_ body: CreateClassRequest, key: String) async throws {
        try await api.send("POST", "classes", body: body, headers: ["Idempotency-Key": key])
    }

    /// `POST /classes` chưa nhận học phí; đặt bằng `PATCH`, Trọn khóa cần lịch đã lưu.
    func setFee(classId: String, _ body: FeePatch, key: String) async throws {
        try await api.send("PATCH", "classes/\(classId)", body: body, headers: ["Idempotency-Key": key])
    }

    func putRules(classId: String, _ rules: [RuleDTO], key: String) async throws {
        try await api.send(
            "PUT", "classes/\(classId)/schedule-rules",
            body: RulesRequest(rules: rules),
            headers: ["Idempotency-Key": key]
        )
    }

    func students(classId: String) async throws -> [StudentDTO] {
        let page: Page<StudentDTO> = try await api.request(
            "GET", "students",
            query: [URLQueryItem(name: "classId", value: classId), URLQueryItem(name: "pageSize", value: "100")]
        )
        return page.items
    }

    func createStudent(_ body: CreateStudentRequest, key: String) async throws -> StudentDTO {
        try await api.request("POST", "students", body: body, headers: ["Idempotency-Key": key])
    }

    func enroll(classId: String, studentId: String, key: String) async throws {
        try await api.send(
            "POST", "classes/\(classId)/enrollments",
            body: EnrollRequest(studentId: studentId),
            headers: ["Idempotency-Key": key]
        )
    }
}

/// Học phí Trọn khóa chọn ở bước 1 chỉ ghi được khi lớp đã có lịch (server tính tổng buổi),
/// nên giữ trên máy tới bước 3. Chỉ có id lớp và số tiền, không có dữ liệu cá nhân.
struct PendingFeeStore: @unchecked Sendable {
    var defaults: UserDefaults = .standard
    private static let key = "pending_course_fee"

    func get(classId: String) -> Int? {
        let parts = defaults.string(forKey: Self.key)?.split(separator: "|") ?? []
        guard parts.count == 2, parts[0] == classId else { return nil }
        return Int(parts[1])
    }

    func set(classId: String, amount: Int) { defaults.set("\(classId)|\(amount)", forKey: Self.key) }
    func clear() { defaults.removeObject(forKey: Self.key) }
}

// MARK: - Luật thuần (cùng ca kiểm thử với Android)

/// SĐT phụ huynh hợp lệ theo hợp đồng `Guardian.phone`.
func isValidPhone(_ phone: String) -> Bool { phone.wholeMatch(of: #/\+?[0-9]{8,15}/#) != nil }

/// Chuẩn hóa SĐT gõ tay hoặc lấy từ danh bạ: bỏ khoảng trắng, dấu chấm, gạch;
/// `+84 912…`, `0084 912…`, `84912…` (11 số) thành `0912…`. Không có chữ số thì `nil`.
func normalizePhone(_ raw: String?) -> String? {
    guard let raw else { return nil }
    var digits = raw.filter { $0.isASCII && $0.isNumber }
    guard !digits.isEmpty else { return nil }
    var intl = raw.drop { $0.isWhitespace }.hasPrefix("+")
    if !intl, digits.hasPrefix("00") {
        intl = true
        digits.removeFirst(2)
    }
    if digits.hasPrefix("84"), intl || digits.count == 11 {
        var rest = digits.dropFirst(2)
        if rest.hasPrefix("0") { rest = rest.dropFirst() }
        return "0" + rest
    }
    return intl ? "+" + digits : digits
}

/// Số tiền VND từ ô nhập (chỉ lấy chữ số); rỗng hoặc quá 12 chữ số thì `nil`.
func parseVnd(_ text: String) -> Int? {
    let digits = text.filter { $0.isASCII && $0.isNumber }
    return (1...12).contains(digits.count) ? Int(digits) : nil
}

func minutesOf(_ hhmm: String) -> Int {
    let p = hhmm.split(separator: ":").compactMap { Int($0) }
    return p.count == 2 ? p[0] * 60 + p[1] : 0
}

func hhmm(_ minutes: Int) -> String { String(format: "%02d:%02d", minutes / 60, minutes % 60) }

/// Một hàng lịch đang soạn; `weekday` `nil` = chưa chọn thứ (hàng mới thêm).
struct ScheduleRow: Identifiable, Equatable, Sendable {
    let id: Int
    var weekday: Int?
    var start = "17:30"
    var duration = 90
}

/// Hàng chồng giờ với một hàng trước nó cùng thứ: (chỉ số hàng sau, hàng trước).
func findOverlap(_ rows: [ScheduleRow]) -> (index: Int, other: ScheduleRow)? {
    for j in rows.indices.dropFirst() {
        for i in 0..<j {
            let a = rows[i], b = rows[j]
            guard let d = a.weekday, d == b.weekday else { continue }
            let aS = minutesOf(a.start), bS = minutesOf(b.start)
            if aS < bS + b.duration, bS < aS + a.duration { return (j, a) }
        }
    }
    return nil
}

/// Mọi hàng có thứ, không chồng giờ, không qua nửa đêm.
func rowsValid(_ rows: [ScheduleRow]) -> Bool {
    !rows.isEmpty
        && rows.allSatisfy { $0.weekday != nil && minutesOf($0.start) + $0.duration <= 24 * 60 }
        && findOverlap(rows) == nil
}

/// Lịch Gregory, múi giờ máy (ngày học của giáo viên).
private let gregorian = Calendar(identifier: .gregorian)

/// Thứ ISO: 1 = thứ Hai … 7 = Chủ nhật.
func isoWeekday(_ date: Date) -> Int { (gregorian.component(.weekday, from: date) + 5) % 7 + 1 }

/// Buổi đầu tiên theo lịch, tính từ ngày bắt đầu lớp (hoặc hôm nay nếu lớp đã bắt đầu): ngày và quy tắc sớm nhất.
func firstSession(_ rules: [RuleDTO], start: Date, today: Date) -> (date: Date, rule: RuleDTO)? {
    guard !rules.isEmpty else { return nil }
    let from = gregorian.startOfDay(for: max(start, today))
    for k in 0..<7 {
        guard let d = gregorian.date(byAdding: .day, value: k, to: from) else { continue }
        let wd = isoWeekday(d)
        if let hit = rules.filter({ $0.weekday == wd }).min(by: { minutesOf($0.startTime) < minutesOf($1.startTime) }) {
            return (d, hit)
        }
    }
    return nil
}

/// `yyyy-MM-dd` theo lịch Gregory, múi giờ máy.
func isoDate(_ date: Date) -> String {
    let c = gregorian.dateComponents([.year, .month, .day], from: date)
    return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
}

func parseIsoDate(_ text: String) -> Date? {
    let p = text.split(separator: "-").compactMap { Int($0) }
    guard p.count == 3 else { return nil }
    return gregorian.date(from: DateComponents(year: p[0], month: p[1], day: p[2]))
}
