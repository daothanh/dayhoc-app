import SwiftUI

/// Banner thông tin của DS (Figma B09): viền nét đứt màu mực, icon info, tiêu đề đậm, mô tả nhạt.
/// Dùng cho ghi chú trong nội dung, không phải cảnh báo lỗi.
struct DhInfoBanner: View {
    let title: LocalizedStringKey
    let description: String
    /// Nút phụ nhỏ bên phải (Figma A01 F); `nil` thì không có.
    var actionTitle: LocalizedStringKey?
    var onAction: () -> Void = {}

    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(alignment: .top, spacing: TeachHubTokens.Dimens.spacingContent) {
            Image(systemName: "info.circle")
                .font(.system(size: 20))
                .foregroundStyle(colors.onSurface)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .dhText(TeachHubTokens.Typo.bodyBold)
                    .foregroundStyle(colors.onSurface)
                Text(description)
                    .dhText(TeachHubTokens.Typo.caption)
                    .foregroundStyle(colors.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let actionTitle {
                DhSecondaryButton(title: actionTitle, fullWidth: false, small: true, action: onAction)
            }
        }
        .padding(TeachHubTokens.Dimens.spacingContent)
        .overlay(
            RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.cardRadius)
                .strokeBorder(colors.onSurface, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
        )
    }
}

/// C12 Banner Offline (Figma A25): full-width dưới App Bar, nền cảnh báo nhạt, đường đứt phía dưới.
struct DhOfflineBanner: View {
    let title: LocalizedStringKey
    let description: LocalizedStringKey

    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(alignment: .top, spacing: TeachHubTokens.Dimens.spacingContent) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 20))
                .foregroundStyle(colors.warning)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).dhText(TeachHubTokens.Typo.bodyBold).foregroundStyle(colors.onSurface)
                Text(description).dhText(TeachHubTokens.Typo.caption).foregroundStyle(colors.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(TeachHubTokens.Dimens.spacingContent)
        .background(colors.warning.opacity(0.12))
        .overlay(alignment: .bottom) {
            Rectangle()
                .stroke(colors.warning, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                .frame(height: 1.5)
        }
    }
}
