import SwiftUI

/// C06 Chip trạng thái (Figma Display/Tag): Outline · Filled · Success · Dashed · Counter.
enum DhChipStyle { case outline, filled, success, dashed, counter }

struct DhChip: View {
    let text: String
    var style: DhChipStyle = .outline

    @Environment(\.dhColors) private var colors

    var body: some View {
        let content: Color = switch style {
        case .filled, .counter: colors.onInverseSurface
        case .success: colors.success
        default: colors.onSurface
        }
        let container: Color = switch style {
        case .filled: colors.onSurface
        case .counter: colors.onErrorContainer
        default: colors.surface
        }
        HStack(spacing: TeachHubTokens.Dimens.spacingInline) {
            if style == .success {
                Image("ic_check").renderingMode(.template).resizable().frame(width: 12, height: 12)
            }
            Text(text).dhText(TeachHubTokens.Typo.label).lineLimit(1)
        }
        .foregroundStyle(content)
        .padding(.leading, style == .success ? TeachHubTokens.Dimens.spacingInline : TeachHubTokens.Dimens.spacingControl)
        .padding(.trailing, TeachHubTokens.Dimens.spacingControl)
        .frame(minWidth: 24, minHeight: 24)
        // Chip không co: thiếu chỗ thì phần khác (tiêu đề App Bar) co trước.
        .fixedSize()
        .background(container, in: Capsule())
        .overlay {
            switch style {
            case .outline: Capsule().stroke(colors.outlineVariant, lineWidth: 1.5)
            case .success: Capsule().stroke(colors.success, lineWidth: 1.5)
            case .dashed: Capsule().strokeBorder(colors.outlineVariant, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
            default: EmptyView()
            }
        }
    }
}
