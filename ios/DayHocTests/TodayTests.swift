import Foundation
import Testing
@testable import DayHoc

private let vn = TimeZone(identifier: "Asia/Ho_Chi_Minh")!
private let t0 = ISO8601DateFormatter().date(from: "2026-11-09T10:30:00Z")! // 17:30 giờ VN
private let t1 = t0.addingTimeInterval(5400)

/// Cùng ca kiểm thử với `TodayRulesTest` bên Android.
struct TodayRulesTests {
    @Test func classifiesByStatusAndClock() {
        #expect(classify(status: "planned", start: t0, end: t1, now: t0 - 1) == .upcoming)
        #expect(classify(status: "planned", start: t0, end: t1, now: t0) == .teaching)
        #expect(classify(status: "planned", start: t0, end: t1, now: t1 - 1) == .teaching)
        #expect(classify(status: "planned", start: t0, end: t1, now: t1) == .missed)
        #expect(classify(status: "taught", start: t0, end: t1, now: t1 + 60) == .taught)
        #expect(classify(status: "cancelled", start: t0, end: t1, now: t0) == .cancelled)
    }

    @Test func emptyVariantFollowsNextSession() {
        #expect(todayEmptyVariant(next: NextSession(className: "Toán 9A", day: t0, time: "17:30")) == .nextSession)
        #expect(todayEmptyVariant(next: nil) == .noSchedule)
    }

    private func session(_ id: String, _ kind: SessionKind, _ at: String) -> TodaySession {
        let start = ISO8601DateFormatter().date(from: at)!
        return TodaySession(id: id, className: id, start: start, end: start + 5400, kind: kind,
                            enrolled: nil, location: nil, attended: nil, total: nil)
    }

    @Test func sortsTeachingMissedUpcomingTaughtCancelled() {
        let sorted = sortSessions([
            session("huy", .cancelled, "2026-11-09T01:00:00Z"),
            session("xong", .taught, "2026-11-09T02:00:00Z"),
            session("toi2", .upcoming, "2026-11-09T14:00:00Z"),
            session("toi1", .upcoming, "2026-11-09T12:30:00Z"),
            session("lo", .missed, "2026-11-09T03:00:00Z"),
            session("day", .teaching, "2026-11-09T10:30:00Z"),
        ])
        #expect(sorted.map(\.id) == ["day", "lo", "toi1", "toi2", "xong", "huy"])
    }

    private func onboarding(_ steps: [(String, String)]) -> OnboardingDTO {
        OnboardingDTO(steps: steps.map { .init(step: $0.0, status: $0.1) }, currentStep: nil)
    }

    @Test func bannerCountsOnlySkippedAddStudentsAndSetSchedule() {
        let all = onboarding([("create_class", "done"), ("add_students", "skipped"), ("set_schedule", "skipped"),
                              ("payout_account", "skipped"), ("compliance_checklist", "pending")])
        #expect(skippedSetupSteps(all) == [.addStudents, .setSchedule])
        #expect(skippedSetupSteps(onboarding([("add_students", "done"), ("set_schedule", "skipped")])) == [.setSchedule])
        #expect(skippedSetupSteps(onboarding([("add_students", "pending"), ("payout_account", "skipped")])).isEmpty)
    }

    @Test func sumsOverdueReceivables() {
        let rows = [ReceivableRowDTO(outstandingAmount: 3_000_000, overdueDays: 5),
                    ReceivableRowDTO(outstandingAmount: 1_000_000, overdueDays: 0),
                    ReceivableRowDTO(outstandingAmount: 3_800_000, overdueDays: 40)]
        #expect(overdueSummary(rows) == Overdue(count: 2, total: 6_800_000))
        #expect(overdueSummary([ReceivableRowDTO(outstandingAmount: 500_000, overdueDays: 0)]) == Overdue(count: 0, total: 0))
    }

    @Test func formatsDatesTimesAndMoney() {
        func day(_ s: String) -> Date { ISO8601DateFormatter().date(from: s + "T05:00:00Z")! }
        #expect(formatDateLine(day("2026-11-09"), vn) == "Thứ Hai, 09/11/2026")
        #expect(formatDateLine(day("2026-11-15"), vn) == "Chủ Nhật, 15/11/2026")
        #expect(formatShortDay(day("2026-11-16"), vn) == "T2 16/11")
        #expect(formatShortDay(day("2026-11-15"), vn) == "CN 15/11")
        #expect(formatTimeRange(t0, t1, vn) == "17:30–19:00")
        #expect(formatVnd(6_800_000) == "6.800.000 đ")
        #expect(formatVnd(950) == "950 đ")
        #expect(formatVnd(1000) == "1.000 đ")
    }

    @Test func nextSessionIsEarliestPlanned() {
        func dto(_ id: String, _ at: String, _ status: String) -> SessionDTO {
            SessionDTO(id: id, classId: "c", className: "Toán 9A", startsAt: at, endsAt: at, status: status)
        }
        let list = [dto("a", "2026-11-17T10:30:00+07:00", "planned"),
                    dto("b", "2026-11-16T10:30:00+07:00", "cancelled"),
                    dto("c", "2026-11-16T17:30:00+07:00", "planned")]
        #expect(earliestPlanned(list)?.id == "c")
        #expect(earliestPlanned([dto("x", "2026-11-16T10:30:00+07:00", "cancelled")]) == nil)
    }
}

private final class FakeSource: TodayDataSource, @unchecked Sendable {
    var sessionsError: APIError?
    var delay: Duration = .zero
    var attendanceFails = false
    var receivablesFails = false
    var notificationsFails = false
    var onboardingFails = false
    var today: [SessionDTO] = [
        SessionDTO(id: "s4", classId: "c4", className: "Sử 6", startsAt: "2026-11-09T20:00:00+07:00", endsAt: "2026-11-09T21:00:00+07:00", status: "cancelled"),
        SessionDTO(id: "s3", classId: "c3", className: "Toán 7", startsAt: "2026-11-09T15:00:00+07:00", endsAt: "2026-11-09T16:30:00+07:00", status: "taught"),
        SessionDTO(id: "s2", classId: "c2", className: "Lý 8", startsAt: "2026-11-09T19:15:00+07:00", endsAt: "2026-11-09T20:45:00+07:00", status: "planned"),
        SessionDTO(id: "s1", classId: "c1", className: "Toán 9A", startsAt: "2026-11-09T17:30:00+07:00", endsAt: "2026-11-09T19:00:00+07:00", status: "planned"),
    ]
    var upcoming: [SessionDTO] = []
    var sessionCalls: [(String, String)] = []

    func onboarding() async throws -> OnboardingDTO {
        if onboardingFails { throw APIError(kind: .timeout) }
        return OnboardingDTO(steps: [.init(step: "add_students", status: "done"), .init(step: "set_schedule", status: "skipped")], currentStep: nil)
    }

    func sessions(from: String, to: String) async throws -> [SessionDTO] {
        sessionCalls.append((from, to))
        let result = from == to ? today : upcoming
        let error = sessionsError
        try await Task.sleep(for: delay)
        if let error { throw error }
        return result
    }

    func classes() async throws -> [ClassInfoDTO] {
        [ClassInfoDTO(id: "c1", name: "Toán 9A", location: "Tại nhà", enrolledCount: 18),
         ClassInfoDTO(id: "c2", name: "Lý 8", enrolledCount: 12),
         ClassInfoDTO(id: "c3", name: "Toán 7", enrolledCount: 16)]
    }

    func receivables() async throws -> [ReceivableRowDTO] {
        if receivablesFails { throw APIError(kind: .http, status: 500) }
        return [ReceivableRowDTO(outstandingAmount: 500_000, overdueDays: 3), ReceivableRowDTO(outstandingAmount: 900_000, overdueDays: 0)]
    }

    func unreadCount() async throws -> Int {
        if notificationsFails { throw APIError(kind: .offline) }
        return 3
    }

    func attendance(sessionId: String) async throws -> AttendanceDTO {
        if attendanceFails { throw APIError(kind: .http, status: 500) }
        return AttendanceDTO(items: (0..<16).map { .init(studentId: "st\($0)") }, summary: .init(present: 14, late: 1))
    }
}

/// Cùng ca kiểm thử với `TodayViewModelTest` bên Android (09/11/2026 17:45 giờ VN: buổi 17:30–19:00 đang dạy).
@MainActor
struct TodayModelTests {
    private let source = FakeSource()
    private func model() -> TodayModel {
        TodayModel(source: source, now: { t0.addingTimeInterval(900) }, timeZone: vn)
    }

    @Test func loadsSessionsAttendanceOverdueBellAndBanner() async {
        let m = model()
        await m.refresh()
        let d = try! #require(m.data)
        #expect(!m.loading)
        #expect(d.sessions.map(\.className) == ["Toán 9A", "Lý 8", "Toán 7", "Sử 6"])
        #expect(d.sessions[0].kind == .teaching)
        #expect(d.sessions[0].enrolled == 18)
        #expect(d.sessions[0].location == "Tại nhà")
        #expect(d.sessions[2].attended == 15 && d.sessions[2].total == 16)
        #expect(d.sessions[3].kind == .cancelled)
        #expect(d.overdue == Overdue(count: 1, total: 500_000))
        #expect(d.unread == 3)
        #expect(d.setupSteps == [.setSchedule])
        #expect(d.next == nil)
        #expect(source.sessionCalls.first?.0 == "2026-11-09" && source.sessionCalls.first?.1 == "2026-11-09")
    }

    @Test func softFailuresDoNotBreakTheScreen() async {
        source.attendanceFails = true
        source.receivablesFails = true
        source.notificationsFails = true
        source.onboardingFails = true
        let m = model()
        await m.refresh()
        let d = try! #require(m.data)
        #expect(!m.failed)
        #expect(d.sessions.first { $0.className == "Toán 7" }?.attended == nil)
        #expect(d.overdue == nil)
        #expect(d.unread == 0)
        #expect(d.setupSteps.isEmpty)
    }

    @Test func emptyDayLooksForNextSessionWithin14Days() async {
        source.today = []
        source.upcoming = [SessionDTO(id: "n", classId: "c1", className: "Toán 9A", startsAt: "2026-11-16T17:30:00+07:00", endsAt: "2026-11-16T19:00:00+07:00", status: "planned")]
        let m = model()
        await m.refresh()
        let d = try! #require(m.data)
        #expect(d.sessions.isEmpty)
        #expect(d.next?.className == "Toán 9A" && d.next?.time == "17:30")
        #expect(formatShortDay(d.next!.day, vn) == "T2 16/11")
        #expect(source.sessionCalls.last?.0 == "2026-11-10" && source.sessionCalls.last?.1 == "2026-11-23")
    }

    @Test func emptyDayWithoutNextSession() async {
        source.today = []
        let m = model()
        await m.refresh()
        #expect(m.data?.next == nil)
    }

    @Test func cancelledRefreshDoesNotOverwriteData() async throws {
        let m = model()
        await m.refresh()
        #expect(m.data?.sessions.count == 4)
        // Lần tải chậm sẽ trả danh sách rỗng; bị lần tải sau (lỗi mạng) hủy thì không được ghi đè.
        source.today = []
        source.delay = .milliseconds(300)
        let slow = Task { await m.refresh() }
        try await Task.sleep(for: .milliseconds(50))
        source.delay = .zero
        source.sessionsError = APIError(kind: .offline)
        await m.refresh()
        await slow.value
        #expect(m.data?.sessions.count == 4)
        #expect(m.offline)
    }

    @Test func firstLoadCancelledByNewRefreshStillEndsLoaded() async throws {
        source.delay = .milliseconds(300)
        let m = model()
        let slow = Task { await m.refresh() }
        try await Task.sleep(for: .milliseconds(50))
        source.delay = .zero
        await m.refresh()
        await slow.value
        #expect(m.data?.sessions.count == 4)
        #expect(!m.loading && !m.failed)
    }

    @Test func nonNetworkErrorWithDataKeepsDataAndCountsFailure() async {
        let m = model()
        await m.refresh()
        source.sessionsError = APIError(kind: .http, status: 500)
        await m.refresh()
        #expect(m.refreshFailures == 1)
        #expect(!m.offline && m.data?.sessions.count == 4)
    }

    @Test func cardsChangeStateAsTimePasses() {
        let s = TodaySession(id: "a", className: "Toán 9A", start: t0, end: t1, kind: .upcoming,
                             enrolled: nil, location: nil, attended: nil, total: nil)
        #expect(arrange([s], now: t0 - 60).first?.kind == .upcoming)
        #expect(arrange([s], now: t0 + 60).first?.kind == .teaching)
        #expect(arrange([s], now: t1).first?.kind == .missed)
    }

    @Test func firstLoadFailureShowsErrorThenRetrySucceeds() async {
        source.sessionsError = APIError(kind: .offline)
        let m = model()
        await m.refresh()
        #expect(m.failed && m.data == nil && !m.offline)
        source.sessionsError = nil
        await m.refresh()
        #expect(!m.failed)
        #expect(m.data?.sessions.count == 4)
    }

    @Test func networkLossWithDataKeepsDataAndGoesOffline() async {
        let m = model()
        await m.refresh()
        source.sessionsError = APIError(kind: .offline)
        await m.refresh()
        #expect(m.offline && !m.refreshing && !m.failed)
        #expect(m.data?.sessions.count == 4)
        source.sessionsError = nil
        await m.refresh()
        #expect(!m.offline)
    }
}
