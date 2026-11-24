import SwiftUI

/// `Brand/Logo` (Figma B01 Header): ký hiệu thương hiệu cạnh tên app đậm. Dùng ở đầu màn [T01].
struct DhBrandLogo: View {
    var markSize: CGFloat = 48

    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingContent) {
            DhBrandMark(size: markSize)
            Text("app.name")
                .font(.title2.bold())
                .foregroundStyle(colors.onSurface)
        }
    }
}
