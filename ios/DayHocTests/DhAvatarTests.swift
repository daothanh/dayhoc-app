import Foundation
import Testing
@testable import DayHoc

/// Cùng ca kiểm thử với `DhAvatarTest` bên Android.
struct DhAvatarTests {
    @Test func initialsTakeFirstAndLastWord() {
        #expect(initialsOf("Nguyễn An") == "NA")
        #expect(initialsOf("Đỗ Hà") == "ĐH")
        #expect(initialsOf("Trần Thị Bích Ngọc") == "TN")
        #expect(initialsOf("  Lan  ") == "L")
        #expect(initialsOf("  ") == "?")
        #expect(initialsOf("lan") == "L")
    }
}

/// Dòng ngày của sheet Chi tiết buổi (cùng ca với `formatSheetDay` bên Android).
struct SheetDayTests {
    @Test func formatsWeekdayAndDayMonth() {
        let tz = TimeZone(identifier: "Asia/Ho_Chi_Minh")!
        let d = ISO8601DateFormatter().date(from: "2026-11-09T05:00:00Z")!
        #expect(formatSheetDay(d, tz) == "Thứ Hai 09/11")
    }
}
