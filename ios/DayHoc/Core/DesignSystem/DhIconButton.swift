import SwiftUI

/// Nút icon của DS (Figma Actions/Icon Button): icon Solar 24 trong vùng chạm 44, tô theo `onSurface`.
/// Icon và vùng chạm chưa có token nên khai tại đây, cùng cách `DhPrimaryButton` khai 48.
struct DhIconButton: View {
    let icon: String
    let label: LocalizedStringKey
    let action: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        Button(action: action) {
            Image(icon)
                .renderingMode(.template)
                .resizable()
                .frame(width: 24, height: 24)
                .foregroundStyle(colors.onSurface)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Mũi tên phải ở cuối dòng danh sách (Solar `alt-arrow-right`, cỡ 20).
struct DhChevron: View {
    @Environment(\.dhColors) private var colors

    var body: some View {
        Image("ic_arrow_right").renderingMode(.template).resizable()
            .frame(width: 20, height: 20)
            .foregroundStyle(colors.onSurface)
            .accessibilityHidden(true)
    }
}
