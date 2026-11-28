import SwiftUI

/// Nút chuông App Bar (Figma Actions/Icon Button 44 + Badge Dot 8): chấm đỏ góc trên phải khi `unread` > 0.
struct DhBellButton: View {
    let unread: Int
    let label: LocalizedStringKey
    let action: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        Button(action: action) {
            Image(systemName: "bell")
                .font(.system(size: TeachHubTokens.Dimens.spacingSection - 4))
                .foregroundStyle(colors.onSurface)
                .frame(width: 44, height: 44)
                .overlay(alignment: .topLeading) {
                    if unread > 0 {
                        Circle().fill(colors.onErrorContainer)
                            .frame(width: TeachHubTokens.Dimens.spacingControl, height: TeachHubTokens.Dimens.spacingControl)
                            .overlay(Circle().stroke(colors.scaffoldBackground, lineWidth: 1.5))
                            .offset(x: 28, y: 10)
                    }
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}
