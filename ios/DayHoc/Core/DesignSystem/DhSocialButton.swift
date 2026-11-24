import SwiftUI

// Màu thương hiệu Apple/Google theo hướng dẫn của hai nhà cung cấp, đổi theo Light/Dark. Rule 30 §4
// cho phép hardcode màu thương hiệu nên không đưa vào token sinh.
// Figma "Auth/Social Button" (131:2430): Light — Apple nền đen chữ trắng, Google nền trắng viền
// #747775 chữ #1F1F1F · Dark — Apple nền trắng, Google nền #131314.
private let appleLightFill = Color(red: 0, green: 0, blue: 0)
private let appleLightContent = Color(red: 1, green: 1, blue: 1)
private let appleDarkContent = Color(red: 31 / 255, green: 31 / 255, blue: 31 / 255)
private let googleLightBorder = Color(red: 116 / 255, green: 119 / 255, blue: 117 / 255)
private let googleLightContent = Color(red: 31 / 255, green: 31 / 255, blue: 31 / 255)
private let googleDarkFill = Color(red: 19 / 255, green: 19 / 255, blue: 20 / 255)
private let googleDarkBorder = Color(red: 142 / 255, green: 145 / 255, blue: 143 / 255)
private let googleDarkContent = Color(red: 227 / 255, green: 227 / 255, blue: 227 / 255)

/// Vùng chạm tối thiểu 48; DS chưa có token chiều cao nút (cùng cách DhPrimaryButton).
private let socialMinHeight: CGFloat = 48

/// "Tiếp tục với Apple" (Figma B01): Light nền đen chữ trắng, Dark nền trắng chữ đen, cao 48,
/// bo `buttonRadius`. Chỉ dựng giao diện; luồng SSO thật làm ở task riêng.
struct DhAppleButton: View {
    let title: LocalizedStringKey
    var enabled = true
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let fill = scheme == .dark ? appleLightContent : appleLightFill
        let content = scheme == .dark ? appleDarkContent : appleLightContent
        Button(action: action) {
            SocialLabel(icon: "ic_logo_apple", title: title, tint: content)
                .frame(maxWidth: .infinity, minHeight: socialMinHeight)
                .foregroundStyle(content)
                .background(
                    fill.opacity(enabled ? 1 : 0.4),
                    in: RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.buttonRadius)
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// "Tiếp tục với Google" (Figma B01): Light nền surface viền #747775, Dark nền #131314 viền #8E918F,
/// cao 48. Logo Google giữ 4 màu gốc nên không tint.
struct DhGoogleButton: View {
    let title: LocalizedStringKey
    var enabled = true
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme
    @Environment(\.dhColors) private var colors

    var body: some View {
        let dark = scheme == .dark
        let shape = RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.buttonRadius)
        let border = !enabled ? colors.outlineVariant : (dark ? googleDarkBorder : googleLightBorder)
        let fill = dark ? googleDarkFill : colors.surface
        let content = dark ? googleDarkContent : googleLightContent
        Button(action: action) {
            SocialLabel(icon: "ic_logo_google", title: title, tint: nil)
                .frame(maxWidth: .infinity, minHeight: socialMinHeight)
                .foregroundStyle(content)
                .background(fill, in: shape)
                .overlay(shape.stroke(border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// Icon 20 cạnh nhãn. `tint` khác `nil` thì icon đơn sắc (Apple); Google truyền `nil` để giữ 4 màu.
private struct SocialLabel: View {
    let icon: String
    let title: LocalizedStringKey
    var tint: Color?

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            if let tint {
                Image(icon)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(tint)
            } else {
                Image(icon)
                    .renderingMode(.original)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 20, height: 20)
            }
            Text(title)
                .font(.body.weight(.semibold))
        }
    }
}
