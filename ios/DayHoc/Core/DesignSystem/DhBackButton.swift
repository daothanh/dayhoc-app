import SwiftUI

/// Nút lùi của App Bar Detail (Figma `Navigation/App Bar`): icon Solar `ic_arrow_back`, vùng chạm 44.
///
/// Icon Solar thay cho ký tự hệ thống, theo thống nhất của nhóm thiết kế. Cỡ 24 và vùng chạm 44
/// chưa có token nên khai tại đây, cùng cách DhPrimaryButton khai 48.
struct DhBackButton: View {
    let action: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        Button(action: action) {
            Image("ic_arrow_back")
                .renderingMode(.template)
                .resizable()
                .frame(width: 24, height: 24)
                .foregroundStyle(colors.onSurface)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("back")
    }
}
