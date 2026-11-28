import Testing
import UIKit
@testable import DayHoc

/// Be Vietnam Pro phải nằm trong bundle và khai ở `UIAppFonts`; thiếu thì iOS âm thầm rơi về font hệ thống
/// nên màn không khớp Figma mà không báo lỗi.
struct DesignSystemFontTests {
    @Test func beVietnamProWeightsLoad() {
        let weights = [
            "BeVietnamPro-Regular",
            "BeVietnamPro-Medium",
            "BeVietnamPro-SemiBold",
            "BeVietnamPro-Bold",
        ]
        for name in weights {
            #expect(UIFont(name: name, size: 14) != nil, "Không nạp được font \(name)")
        }
    }
}
