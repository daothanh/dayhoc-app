import SwiftUI

/// Bộ màu Light/Dark lấy từ `TeachHubTokens` (sinh từ token Figma). Màn hình đọc qua
/// `@Environment(\.dhColors)`, không viết màu rời.
struct DhColors: Sendable {
    let primary: Color
    let onPrimary: Color
    let primaryContainer: Color
    let secondary: Color
    let surface: Color
    let onSurface: Color
    let onSurfaceVariant: Color
    let divider: Color
    let surfaceContainerHighest: Color
    let outline: Color
    let outlineVariant: Color
    /// Viền nút phụ (token `buttonBorder`); đậm hơn `outlineVariant`.
    let buttonBorder: Color
    let surfaceContainerLow: Color
    let secondaryContainer: Color
    let onSecondaryContainer: Color
    let onInverseSurface: Color
    let warning: Color
    let success: Color
    let error: Color
    let errorContainer: Color
    let onErrorContainer: Color
    let placeholder: Color
    let scaffoldBackground: Color

    static let light = DhColors(
        primary: TeachHubTokens.Light.primary,
        onPrimary: TeachHubTokens.Light.onPrimary,
        primaryContainer: TeachHubTokens.Light.primaryContainer,
        secondary: TeachHubTokens.Light.secondary,
        surface: TeachHubTokens.Light.surface,
        onSurface: TeachHubTokens.Light.onSurface,
        onSurfaceVariant: TeachHubTokens.Light.onSurfaceVariant,
        divider: TeachHubTokens.Light.divider,
        surfaceContainerHighest: TeachHubTokens.Light.surfaceContainerHighest,
        outline: TeachHubTokens.Light.outline,
        outlineVariant: TeachHubTokens.Light.outlineVariant,
        buttonBorder: TeachHubTokens.Light.buttonBorder,
        surfaceContainerLow: TeachHubTokens.Light.surfaceContainerLow,
        secondaryContainer: TeachHubTokens.Light.secondaryContainer,
        onSecondaryContainer: TeachHubTokens.Light.onSecondaryContainer,
        onInverseSurface: TeachHubTokens.Light.onInverseSurface,
        warning: TeachHubTokens.Light.warningColor,
        success: TeachHubTokens.Light.successColor,
        error: TeachHubTokens.Light.error,
        errorContainer: TeachHubTokens.Light.errorContainer,
        onErrorContainer: TeachHubTokens.Light.onErrorContainer,
        placeholder: TeachHubTokens.Light.placeholderColor,
        scaffoldBackground: TeachHubTokens.Light.scaffoldBackground
    )

    static let dark = DhColors(
        primary: TeachHubTokens.Dark.primary,
        onPrimary: TeachHubTokens.Dark.onPrimary,
        primaryContainer: TeachHubTokens.Dark.primaryContainer,
        secondary: TeachHubTokens.Dark.secondary,
        surface: TeachHubTokens.Dark.surface,
        onSurface: TeachHubTokens.Dark.onSurface,
        onSurfaceVariant: TeachHubTokens.Dark.onSurfaceVariant,
        divider: TeachHubTokens.Dark.divider,
        surfaceContainerHighest: TeachHubTokens.Dark.surfaceContainerHighest,
        outline: TeachHubTokens.Dark.outline,
        outlineVariant: TeachHubTokens.Dark.outlineVariant,
        buttonBorder: TeachHubTokens.Dark.buttonBorder,
        surfaceContainerLow: TeachHubTokens.Dark.surfaceContainerLow,
        secondaryContainer: TeachHubTokens.Dark.secondaryContainer,
        onSecondaryContainer: TeachHubTokens.Dark.onSecondaryContainer,
        onInverseSurface: TeachHubTokens.Dark.onInverseSurface,
        warning: TeachHubTokens.Dark.warningColor,
        success: TeachHubTokens.Dark.successColor,
        error: TeachHubTokens.Dark.error,
        errorContainer: TeachHubTokens.Dark.errorContainer,
        onErrorContainer: TeachHubTokens.Dark.onErrorContainer,
        placeholder: TeachHubTokens.Dark.placeholderColor,
        scaffoldBackground: TeachHubTokens.Dark.scaffoldBackground
    )
}

extension EnvironmentValues {
    @Entry var dhColors: DhColors = .light
}

/// Tên PostScript của Be Vietnam Pro (bundle trong `Resources/Fonts`, khai ở `UIAppFonts`).
/// DS chỉ dùng 4 độ đậm 400/500/600/700, khớp token.
private enum DhFontName {
    static let regular = "BeVietnamPro-Regular"
    static let medium = "BeVietnamPro-Medium"
    static let semibold = "BeVietnamPro-SemiBold"
    static let bold = "BeVietnamPro-Bold"
}

/// Quy đổi số độ đậm (Figma) sang tên font Be Vietnam Pro tương ứng.
private extension Int {
    var dhFontName: String {
        switch self {
        case ..<450: return DhFontName.regular
        case ..<550: return DhFontName.medium
        case ..<650: return DhFontName.semibold
        default: return DhFontName.bold
        }
    }
}

extension View {
    /// Áp text style DayHoc (font, cỡ chữ, độ đậm, giãn chữ) từ token. Màn gọi `.dhText(.h1)`.
    func dhText(_ spec: TeachHubTokens.Typo.Spec) -> some View {
        font(.custom(spec.fontWeight.dhFontName, size: spec.fontSize))
            .tracking(spec.letterSpacing)
    }

    /// Chọn bộ màu theo chế độ sáng/tối của hệ thống. Gắn một lần ở gốc app.
    func dhTheme() -> some View { modifier(DhThemeModifier()) }

    /// Nền màn hình (`scaffoldBackground`) phủ cả vùng an toàn.
    func dhScreen() -> some View { modifier(DhScreenModifier()) }
}

private struct DhThemeModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let colors: DhColors = colorScheme == .dark ? .dark : .light
        content
            .font(.custom(DhFontName.regular, size: TeachHubTokens.Typo.body.fontSize))
            .environment(\.dhColors, colors)
            .tint(colors.secondary)
    }
}

private struct DhScreenModifier: ViewModifier {
    @Environment(\.dhColors) private var colors

    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(colors.scaffoldBackground.ignoresSafeArea())
            .foregroundStyle(colors.onSurface)
    }
}
