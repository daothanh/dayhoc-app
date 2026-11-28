import SwiftUI

/// Banner thông tin của DS (Figma B09): viền nét đứt màu mực, icon info, tiêu đề đậm, mô tả nhạt.
/// Dùng cho ghi chú trong nội dung, không phải cảnh báo lỗi.
struct DhInfoBanner: View {
    let title: LocalizedStringKey
    let description: String

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
        }
        .padding(TeachHubTokens.Dimens.spacingContent)
        .overlay(
            RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.cardRadius)
                .strokeBorder(colors.onSurface, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
        )
    }
}
