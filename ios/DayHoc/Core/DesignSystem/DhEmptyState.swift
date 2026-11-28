import SwiftUI

/// Empty State (Figma C17/A24): minh hoạ, tiêu đề H3, mô tả Caption (rộng tối đa 300), nút chính nhỏ.
struct DhEmptyState: View {
    let title: LocalizedStringKey
    var description: String?
    let actionTitle: LocalizedStringKey
    var illustration: DhIllustrationType = .calendar
    let action: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(spacing: TeachHubTokens.Dimens.spacingContent) {
            DhIllustration(type: illustration)
            Text(title).dhText(TeachHubTokens.Typo.h3).foregroundStyle(colors.onSurface)
            if let description {
                Text(description).dhText(TeachHubTokens.Typo.caption).foregroundStyle(colors.onSurfaceVariant)
                    .frame(maxWidth: 300)
            }
            DhPrimaryButton(title: actionTitle, small: true, action: action)
                .fixedSize(horizontal: true, vertical: false)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
        .padding(.vertical, 32)
    }
}
