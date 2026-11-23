import Foundation
import os
import Testing
@testable import DayHoc

/// Cùng ca kiểm thử với `OnboardingRulesTest`, `OnboardingViewModelTest` bên Android.
struct OnboardingRulesTests {
    @Test func normalizesPhone() {
        #expect(normalizePhone("0912 345 678") == "0912345678")
        #expect(normalizePhone("+84 912 345 678") == "0912345678")
        #expect(normalizePhone("0084912345678") == "0912345678")
        #expect(normalizePhone("84912345678") == "0912345678")
        #expect(normalizePhone("+65 9123 4567") == "+6591234567")
        #expect(normalizePhone("không có số") == nil)
        #expect(isValidPhone("0912345678"))
        #expect(!isValidPhone("0912"))
    }

    @Test func parsesMoney() {
        #expect(parseVnd("150.000 đ") == 150_000)
        #expect(parseVnd("") == nil)
        #expect(parseVnd("1234567890123") == nil)
    }

    @Test func laterRowOverlappingSameDayIsFlagged() {
        let rows = [
            ScheduleRow(id: 0, weekday: 1, start: "17:30", duration: 90),
            ScheduleRow(id: 1, weekday: 3, start: "17:30", duration: 90),
            ScheduleRow(id: 2, weekday: 1, start: "18:30", duration: 60),
        ]
        #expect(findOverlap(rows)?.index == 2)
        #expect(findOverlap(rows)?.other == rows[0])
        #expect(!rowsValid(rows))
        // Nối tiếp nhau (17:30–19:00 rồi 19:00) không tính là chồng.
        #expect(rowsValid([
            ScheduleRow(id: 0, weekday: 1, start: "17:30", duration: 90),
            ScheduleRow(id: 1, weekday: 1, start: "19:00", duration: 60),
        ]))
        #expect(!rowsValid([ScheduleRow(id: 0, weekday: nil)]))
        #expect(!rowsValid([ScheduleRow(id: 0, weekday: 2, start: "23:30", duration: 60)]))
    }

    @Test func firstSessionIsNearestDayEarliestTime() throws {
        // 26/11/2026 là thứ Năm.
        let today = try #require(parseIsoDate("2026-11-26"))
        let rules = [
            RuleDTO(weekday: 1, startTime: "17:30", durationMin: 90),
            RuleDTO(weekday: 6, startTime: "19:00", durationMin: 60),
            RuleDTO(weekday: 6, startTime: "08:00", durationMin: 90),
        ]
        let first = try #require(firstSession(rules, start: today, today: today))
        #expect(isoDate(first.date) == "2026-11-28")
        #expect(first.rule == rules[2])
        let monday = try #require(parseIsoDate("2026-12-07"))
        let later = try #require(firstSession(rules, start: monday, today: today))
        #expect(isoDate(later.date) == "2026-12-07")
        #expect(later.rule == rules[0])
    }

    @Test func comparesMinimumVersion() {
        #expect(isVersionBelow("0.1.0", "0.2.0"))
        #expect(isVersionBelow("1.9.9", "1.10.0"))
        #expect(!isVersionBelow("1.2.0", "1.2.0"))
        #expect(!isVersionBelow("1.2.0-beta+12", "1.1.9"))
    }
}

@MainActor
struct OnboardingModelTests {
    private let store = InMemoryTokenStore(Tokens(accessToken: "a", refreshToken: "r"))
    private let fees = PendingFeeStore(defaults: UserDefaults(suiteName: UUID().uuidString)!)
    private let today = parseIsoDate("2026-11-23")!
    private static let oneClass = [#"{"id":"c1","name":"Toán 9A","startDate":"2026-11-23"}"#]

    private func makeModel(_ server: MockServer) -> OnboardingModel {
        let today = today
        return OnboardingModel(api: OnboardingAPI(api: server.makeClient(tokenStore: store)), pendingFee: fees) { today }
    }

    @Test func retryAfterServerErrorReusesClassIdAndKey() async throws {
        let attempts = OSAllocatedUnfairLock(initialState: 0)
        let server = FakeBackend(current: "create_class").server { request in
            guard request.url?.path == "/v1/classes", request.httpMethod == "POST" else { return nil }
            return attempts.withLock { n in
                n += 1
                return n == 1 ? .error(503, "SERVICE_UNAVAILABLE") : Stub(status: 201, json: "{}")
            }
        }
        let model = makeModel(server)
        await model.load()
        model.classForm = ClassForm(name: " Toán 9A ", subject: "Toán", grade: 9, feeType: FeeTypes.perSession, amount: "150000")

        await model.createClass()
        #expect(model.message != nil)
        #expect(model.step == .createClass)
        await model.createClass()

        let creates = server.requests.filter { $0.url?.path == "/v1/classes" && $0.httpMethod == "POST" }
        try #require(creates.count == 2)
        #expect(creates[0].value(forHTTPHeaderField: "Idempotency-Key") == creates[1].value(forHTTPHeaderField: "Idempotency-Key"))
        let first = creates[0].jsonObject, second = creates[1].jsonObject
        #expect(first["id"] as? String == second["id"] as? String)
        #expect(second["name"] as? String == "Toán 9A")
        #expect(second["mode"] as? String == "offline")
        #expect(second["startDate"] as? String == "2026-11-23")
        let patches = server.requests.filter { $0.httpMethod == "PATCH" && ($0.url?.path ?? "").hasPrefix("/v1/classes/") }
        let fee = try #require(patches.first)
        let feeBody = fee.jsonObject
        #expect(feeBody["feeType"] as? String == "per_session")
        #expect(feeBody["feeAmount"] as? Int == 150_000)
        #expect(model.step == .addStudents)
    }

    @Test func reopeningWithExistingClassOnlyRecordsStep() async {
        let server = FakeBackend(current: "create_class", classes: Self.oneClass).server()
        let model = makeModel(server)
        await model.load()

        let creates = server.requests.filter { $0.url?.path == "/v1/classes" && $0.httpMethod == "POST" }
        let recorded = server.requests(to: "onboarding/steps/create_class")
        #expect(creates.isEmpty)
        #expect(recorded.count == 1)
    }

    @Test func courseFeeWaitsForScheduleThenIsSaved() async throws {
        let server = FakeBackend(current: "set_schedule", classes: Self.oneClass).server()
        fees.set(classId: "c1", amount: 3_000_000)
        let model = makeModel(server)
        await model.load()

        // Mặc định một hàng "T2 · 17:30 · 90 phút".
        #expect(model.rows.map(\.weekday) == [1])
        await model.saveSchedule()

        let rulesRequest = try #require(server.requests(to: "classes/c1/schedule-rules").first)
        let rules = rulesRequest.jsonObject
        #expect((rules["rules"] as? [[String: Any]])?.first?["startTime"] as? String == "17:30")
        let feePatches = server.requests(to: "classes/c1").filter { $0.httpMethod == "PATCH" }
        let fee = try #require(feePatches.first).jsonObject
        #expect(fee["feeType"] as? String == "course")
        #expect(fee["feeAmount"] as? Int == 3_000_000)
        #expect(fees.get(classId: "c1") == nil)
        #expect(model.finished)
    }

    @Test func addedStudentIsEnrolledWithNormalizedPhone() async throws {
        let server = FakeBackend(current: "add_students", classes: Self.oneClass).server()
        let model = makeModel(server)
        await model.load()

        model.studentName = "Nguyễn Văn Nam"
        model.studentPhone = "+84 912 345 678"
        await model.addStudent(guardian: "Phụ huynh của Nguyễn Văn Nam")

        let posts = server.requests(to: "students").filter { $0.httpMethod == "POST" }
        let create = try #require(posts.first).jsonObject
        let guardian = (create["guardians"] as? [[String: Any]])?.first
        #expect(guardian?["phone"] as? String == "0912345678")
        #expect(guardian?["fullName"] as? String == "Phụ huynh của Nguyễn Văn Nam")
        let enrollRequest = try #require(server.requests(to: "classes/c1/enrollments").first)
        let enroll = enrollRequest.jsonObject
        #expect(enroll["studentId"] as? String == "s1")
        #expect(model.added == [AddedStudent(name: "Nguyễn Văn Nam", phone: "0912345678")])
        #expect(model.studentName.isEmpty)

        await model.finishStudents()
        #expect(model.step == .setSchedule)
    }
}

/// Backend giả cho onboarding: tiến độ 3 bước, danh sách lớp, các lệnh ghi trả thành công.
/// Không đọc body trong handler: stream body chỉ đọc được một lần, để dành cho phần kiểm tra.
private struct FakeBackend {
    let current: String?
    var classes: [String] = []

    private static let order = ["create_class", "add_students", "set_schedule"]

    static func onboarding(_ current: String?) -> String {
        let steps = order.map { s in
            let done = current.map { order.firstIndex(of: s)! < order.firstIndex(of: $0)! } ?? true
            return #"{"step":"\#(s)","status":"\#(done ? "done" : "pending")"}"#
        }
        let cur = current.map { #""\#($0)""# } ?? "null"
        return #"{"steps":[\#(steps.joined(separator: ","))],"currentStep":\#(cur)}"#
    }

    /// `override` trả phản hồi riêng cho request nào cần; `nil` thì dùng mặc định.
    func server(_ override: @escaping @Sendable (URLRequest) -> Stub? = { _ in nil }) -> MockServer {
        let classes = classes
        let current = OSAllocatedUnfairLock(initialState: current)
        return MockServer { request in
            if let stub = override(request) { return stub }
            let path = request.url?.path ?? ""
            switch (request.httpMethod ?? "GET", path) {
            case ("GET", "/v1/onboarding"):
                return Stub(status: 200, json: Self.onboarding(current.withLock { $0 }))
            case ("PATCH", let p) where p.hasPrefix("/v1/onboarding/steps/"):
                let step = String(p.dropFirst("/v1/onboarding/steps/".count))
                let next = Self.order.firstIndex(of: step).flatMap { $0 + 1 < Self.order.count ? Self.order[$0 + 1] : nil }
                current.withLock { $0 = next }
                return Stub(status: 200, json: Self.onboarding(next))
            case ("GET", "/v1/classes"):
                return Stub(status: 200, json: #"{"items":[\#(classes.joined(separator: ","))]}"#)
            case ("GET", "/v1/students"):
                return Stub(status: 200, json: #"{"items":[]}"#)
            case ("POST", "/v1/students"):
                return Stub(status: 201, json: #"{"id":"s1","fullName":"Nguyễn Văn Nam","guardians":[]}"#)
            case ("POST", _):
                return Stub(status: 201, json: "{}")
            default:
                return Stub(status: 200, json: "{}")
            }
        }
    }
}

extension URLRequest {
    /// Body JSON bất kỳ (số, mảng); URLProtocol nhận body qua stream chứ không qua `httpBody`.
    var jsonObject: [String: Any] {
        guard let stream = httpBodyStream else { return [:] }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            guard read > 0 else { break }
            data.append(buffer, count: read)
        }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }
}
