package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dark
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Light

// Nền màn hình (background) = scaffoldBackground; Scaffold của M3 lấy màu này làm nền.
private val LightColors = lightColorScheme(
    primary = Light.primary,
    onPrimary = Light.onPrimary,
    primaryContainer = Light.primaryContainer,
    onPrimaryContainer = Light.onPrimaryContainer,
    secondary = Light.secondary,
    onSecondary = Light.onSecondary,
    secondaryContainer = Light.secondaryContainer,
    onSecondaryContainer = Light.onSecondaryContainer,
    error = Light.error,
    errorContainer = Light.errorContainer,
    onErrorContainer = Light.onErrorContainer,
    background = Light.scaffoldBackground,
    onBackground = Light.onSurface,
    surface = Light.surface,
    onSurface = Light.onSurface,
    onSurfaceVariant = Light.onSurfaceVariant,
    surfaceContainerLow = Light.surfaceContainerLow,
    surfaceContainerHighest = Light.surfaceContainerHighest,
    outline = Light.outline,
    outlineVariant = Light.outlineVariant,
    inverseSurface = Light.inverseSurface,
    inverseOnSurface = Light.onInverseSurface,
    surfaceTint = Light.surfaceTint,
)

private val DarkColors = darkColorScheme(
    primary = Dark.primary,
    onPrimary = Dark.onPrimary,
    primaryContainer = Dark.primaryContainer,
    onPrimaryContainer = Dark.onPrimaryContainer,
    secondary = Dark.secondary,
    onSecondary = Dark.onSecondary,
    secondaryContainer = Dark.secondaryContainer,
    onSecondaryContainer = Dark.onSecondaryContainer,
    error = Dark.error,
    errorContainer = Dark.errorContainer,
    onErrorContainer = Dark.onErrorContainer,
    background = Dark.scaffoldBackground,
    onBackground = Dark.onSurface,
    surface = Dark.surface,
    onSurface = Dark.onSurface,
    onSurfaceVariant = Dark.onSurfaceVariant,
    surfaceContainerLow = Dark.surfaceContainerLow,
    surfaceContainerHighest = Dark.surfaceContainerHighest,
    outline = Dark.outline,
    outlineVariant = Dark.outlineVariant,
    inverseSurface = Dark.inverseSurface,
    inverseOnSurface = Dark.onInverseSurface,
    surfaceTint = Dark.surfaceTint,
)

private val TeachHubShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.checkboxRadius),
    small = RoundedCornerShape(Dimens.fieldRadius),
    medium = RoundedCornerShape(Dimens.cardRadius),
    large = RoundedCornerShape(Dimens.dialogRadius),
    extraLarge = RoundedCornerShape(Dimens.sheetRadius),
)

// Be Vietnam Pro là font của Figma; DS chỉ dùng 4 độ đậm 400/500/600/700.
private val BeVietnamPro = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semibold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold),
)

/** Typography M3 giữ nguyên cỡ chữ, chỉ đổi font sang [BeVietnamPro] cho khớp Figma. */
private val TeachHubTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = BeVietnamPro),
        displayMedium = base.displayMedium.copy(fontFamily = BeVietnamPro),
        displaySmall = base.displaySmall.copy(fontFamily = BeVietnamPro),
        headlineLarge = base.headlineLarge.copy(fontFamily = BeVietnamPro),
        headlineMedium = base.headlineMedium.copy(fontFamily = BeVietnamPro),
        headlineSmall = base.headlineSmall.copy(fontFamily = BeVietnamPro),
        titleLarge = base.titleLarge.copy(fontFamily = BeVietnamPro),
        titleMedium = base.titleMedium.copy(fontFamily = BeVietnamPro),
        titleSmall = base.titleSmall.copy(fontFamily = BeVietnamPro),
        bodyLarge = base.bodyLarge.copy(fontFamily = BeVietnamPro),
        bodyMedium = base.bodyMedium.copy(fontFamily = BeVietnamPro),
        bodySmall = base.bodySmall.copy(fontFamily = BeVietnamPro),
        labelLarge = base.labelLarge.copy(fontFamily = BeVietnamPro),
        labelMedium = base.labelMedium.copy(fontFamily = BeVietnamPro),
        labelSmall = base.labelSmall.copy(fontFamily = BeVietnamPro),
    )
}

/** Theme DayHoc dựng hoàn toàn từ [TeachHubTokens]. */
@Composable
fun TeachHubTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TeachHubTypography,
        shapes = TeachHubShapes,
        content = content,
    )
}
