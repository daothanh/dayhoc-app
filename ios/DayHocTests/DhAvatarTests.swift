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
