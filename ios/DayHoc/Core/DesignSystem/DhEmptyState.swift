import SwiftUI

/// Empty State (Figma A24): khối minh hoạ, tiêu đề, mô tả, nút chính.
/// shortcut: minh hoạ là khối Fill giữ chỗ như Figma, thay khi DS xuất bộ minh hoạ.
struct DhEmptyState: View {
    let title: LocalizedStringKey
    var description: String?
    let actionTitle: LocalizedStringKey
    let action: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.dialogRadius)
                .fill(colors.surfaceContainerHighest)
                .frame(width: 120, height: 120)
            Text(title).dhText(TeachHubTokens.Typo.bodyBold).foregroundStyle(colors.onSurface)
            if let description {
                Text(description).dhText(TeachHubTokens.Typo.caption).foregroundStyle(colors.onSurfaceVariant)
            }
            DhPrimaryButton(title: actionTitle, action: action)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.top, TeachHubTokens.Dimens.spacingControl)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, TeachHubTokens.Dimens.spacingSection)
    }
}
