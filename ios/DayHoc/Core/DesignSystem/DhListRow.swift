import SwiftUI

/// C08 List Row: tiêu đề + phụ đề, phần cuối tuỳ ý; Pressed đổi nền sang Fill.
struct DhListRow<Trailing: View>: View {
    let title: LocalizedStringKey
    var subtitle: String?
    var onTap: (() -> Void)?
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        if let onTap {
            Button(action: onTap) { DhListRowBody(title: title, subtitle: subtitle, trailing: trailing) }
                .buttonStyle(DhPressableStyle())
        } else {
            DhListRowBody(title: title, subtitle: subtitle, trailing: trailing)
        }
    }
}

private struct DhListRowBody<Trailing: View>: View {
    let title: LocalizedStringKey
    let subtitle: String?
    @ViewBuilder let trailing: () -> Trailing

    @Environment(\.dhColors) private var colors
    @Environment(\.isPressedByParentButton) private var pressed

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingContent) {
            VStack(alignment: .leading, spacing: 0) {
                Text(title).dhText(TeachHubTokens.Typo.body).foregroundStyle(colors.onSurface)
                if let subtitle {
                    Text(subtitle).dhText(TeachHubTokens.Typo.caption).foregroundStyle(colors.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .padding(TeachHubTokens.Dimens.spacingControl)
        .frame(minHeight: 56)
        .background(pressed ? colors.surfaceContainerLow : colors.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(colors.surfaceContainerLow).frame(height: 1) }
        .contentShape(Rectangle())
    }
}
