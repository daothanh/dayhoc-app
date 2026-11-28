import Foundation
import Testing
@testable import DayHoc

/// Cùng ca kiểm thử với `TodayPendingTest` bên Android, thêm buổi có lô lỗi.
struct TodayPendingTests {
    private let now = Date(timeIntervalSince1970: 1_794_221_100)

    private func s(_ id: String, _ status: String, _ startHours: Double) -> TodaySession {
        let start = now.addingTimeInterval(startHours * 3600)
        return TodaySession(id: id, className: "Lớp \(id)", start: start, end: start.addingTimeInterval(5400), kind: .upcoming,
                            enrolled: nil, location: nil, attended: nil, total: nil, status: status)
    }

    @Test func pendingSessionMovesToTaughtGroupAndIsMarked() {
        let out = arrange([s("a", "planned", -3), s("b", "planned", 2), s("c", "taught", -5)], now: now, pendingIds: ["a"])
        #expect(out.map(\.id) == ["b", "c", "a"])
        #expect(out.map(\.kind) == [.upcoming, .taught, .taught])
        #expect(out.map(\.pending) == [false, false, true])
    }

    @Test func noPendingLeavesArrangeUnchanged() {
        let out = arrange([s("a", "planned", -3)], now: now)
        #expect(out.first?.kind == .missed)
        #expect(out.first?.pending == false)
    }

    @Test func failedSessionIsFlagged() {
        let out = arrange([s("a", "planned", -3)], now: now, pendingIds: ["a"], failedIds: ["a"])
        #expect(out.first?.syncFailed == true)
        #expect(out.first?.pending == true)
    }

    @Test func cancelledSessionIsNotTurnedIntoTaught() {
        let out = arrange([s("a", "cancelled", -3)], now: now, pendingIds: ["a"])
        #expect(out.first?.kind == .cancelled)
        #expect(out.first?.pending == false)
    }
}
