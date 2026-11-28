import SwiftUI

/// C08 List Row biến thể `Expanded` (Figma A04): nền Fill + bo `fieldRadius` khi mở hoặc `highlighted` (viền lime, A26).
/// Phần chính là một hàng cao tối thiểu 40; `expandedContent` chỉ hiện khi mở.
struct DhExpandableRow<Main: View, Expanded: View>: View {
    var expanded = false
    var highlighted = false
    var onTap: (() -> Void)?
    @ViewBuilder let main: () -> Main
    @ViewBuilder let expandedContent: () -> Expanded

    @Environment(\.dhColors) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: expanded || highlighted ? TeachHubTokens.Dimens.fieldRadius : 0)
        VStack(spacing: 0) {
            VStack(spacing: TeachHubTokens.Dimens.spacingControl) {
                Group {
                    if let onTap {
                        Button(action: onTap) { mainRow }.buttonStyle(.plain)
                    } else {
                        mainRow
                    }
                }
                if expanded { expandedContent() }
            }
            .padding(TeachHubTokens.Dimens.spacingControl)
            if !expanded && !highlighted { Rectangle().fill(colors.surfaceContainerLow).frame(height: 1) }
        }
        .background(expanded ? colors.surfaceContainerLow : colors.surface, in: shape)
        .overlay { if highlighted && !expanded { shape.stroke(colors.primary, lineWidth: 1.5) } }
        .clipShape(shape)
        .animation(.easeInOut(duration: 0.15), value: expanded)
    }

    private var mainRow: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingContent, content: main)
            .frame(minHeight: 40)
            .contentShape(Rectangle())
    }
}

/// C13 Bottom Action Bar (Figma Actions/Bottom Action Bar): nền surface, viền trên 1.5, chừa vùng an toàn dưới.
struct DhBottomActionBar<Content: View>: View {
    @ViewBuilder let content: () -> Content

    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(spacing: 0, content: content)
            .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
            .padding(.top, TeachHubTokens.Dimens.spacingContent)
            .padding(.bottom, TeachHubTokens.Dimens.spacingPage)
            .frame(maxWidth: .infinity)
            .background(colors.surface)
            .overlay(alignment: .top) { Rectangle().fill(colors.outlineVariant).frame(height: 1.5) }
    }
}
