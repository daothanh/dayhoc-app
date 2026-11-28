import SwiftUI

/// Loại minh hoạ của Figma Display/Illustration; thêm loại mới bằng một glyph template trong Assets.
enum DhIllustrationType {
    case calendar

    fileprivate var glyph: String {
        switch self {
        case .calendar: "ill_calendar_blank"
        }
    }
}

/// Minh hoạ 120×120 (Figma Display/Illustration): nền `primaryContainer`, đĩa `primary` 76 ở giữa,
/// glyph 40 màu `onPrimary`. Màu theo theme nên tự đổi Light/Dark.
struct DhIllustration: View {
    let type: DhIllustrationType

    @Environment(\.dhColors) private var colors

    var body: some View {
        ZStack {
            Circle().fill(colors.primary).frame(width: 76, height: 76)
            Image(type.glyph).renderingMode(.template).resizable().frame(width: 40, height: 40)
                .foregroundStyle(colors.onPrimary)
        }
        .frame(width: 120, height: 120)
        .background(colors.primaryContainer, in: RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.sheetRadius))
        .accessibilityHidden(true)
    }
}
