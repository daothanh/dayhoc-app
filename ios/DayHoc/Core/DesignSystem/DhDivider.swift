import SwiftUI

/// Đường phân cách có nhãn ở giữa (Figma B01: "hoặc dùng email").
struct DhDivider: View {
    let label: LocalizedStringKey

    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingContent) {
            line
            Text(label)
                .font(.footnote)
                .foregroundStyle(colors.onSurfaceVariant)
                .fixedSize()
            line
        }
    }

    private var line: some View {
        Rectangle()
            .fill(colors.divider)
            .frame(maxWidth: .infinity)
            .frame(height: 1)
    }
}
