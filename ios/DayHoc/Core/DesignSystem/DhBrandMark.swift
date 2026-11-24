import SwiftUI

/// Ký hiệu thương hiệu DayHoc (Figma Brand/Logo · Mark): ô lime bo góc, glyph D + chấm
/// tô `onPrimary`. Bo góc tỉ lệ 20/48 như Figma; 48 ở T01, 64 ở màn cập nhật, 160 ở splash.
struct DhBrandMark: View {
    var size: CGFloat = 48

    @Environment(\.dhColors) private var colors

    var body: some View {
        Image("brand.mark")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .foregroundStyle(colors.onPrimary)
            .background(colors.primary, in: RoundedRectangle(cornerRadius: size * 20 / 48))
            .accessibilityLabel(Text("app.name"))
    }
}
