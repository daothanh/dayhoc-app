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

extension View {
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
