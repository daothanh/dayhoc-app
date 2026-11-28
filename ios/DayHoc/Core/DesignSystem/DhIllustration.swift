import SwiftUI

/// Loại minh hoạ của Figma Display/Illustration; thêm loại mới bằng một glyph template trong Assets.
enum DhIllustrationType {
    case calendar
    /// Glyph lệch 4 lên trái nhường chỗ cho badge dấu cộng ở góc dưới phải.
    case calendarPlus

    fileprivate var glyph: String { "ill_calendar_blank" }
    fileprivate var glyphShift: CGFloat { self == .calendarPlus ? -4 : 0 }
    fileprivate var hasBadge: Bool { self == .calendarPlus }
}

/// Minh hoạ 120×120 (Figma Display/Illustration): nền `primaryContainer`, đĩa `primary` 76 ở giữa,
/// glyph 40 màu `onPrimary`; `.calendarPlus` thêm badge 30 nền `onPrimary` chứa dấu cộng 16 màu `primary`. Màu theo theme nên tự đổi Light/Dark.
struct DhIllustration: View {
    let type: DhIllustrationType

    @Environment(\.dhColors) private var colors

    var body: some View {
        ZStack {
            Circle().fill(colors.primary).frame(width: 76, height: 76)
            Image(type.glyph).renderingMode(.template).resizable().frame(width: 40, height: 40)
                .foregroundStyle(colors.onPrimary)
                .offset(x: type.glyphShift, y: type.glyphShift)
            if type.hasBadge {
                ZStack {
                    Circle().fill(colors.onPrimary)
                    Image("ill_plus").renderingMode(.template).resizable().frame(width: 16, height: 16)
                        .foregroundStyle(colors.primary)
                }
                .frame(width: 30, height: 30)
                .offset(x: 31, y: 31) // tâm badge ở (91,91) trong khung 120
            }
        }
        .frame(width: 120, height: 120)
        .background(colors.primaryContainer, in: RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.sheetRadius))
        .accessibilityHidden(true)
    }
}
