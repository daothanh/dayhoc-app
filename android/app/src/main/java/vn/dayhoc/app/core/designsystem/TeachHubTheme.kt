package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dark
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Light
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Typo

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
    // Hộp thoại, DatePicker, TimePicker dùng surfaceContainer/High; để trống thì M3 lấy tím mặc định.
    surfaceContainer = Light.surface,
    surfaceContainerHigh = Light.surface,
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
    // Hộp thoại, DatePicker, TimePicker dùng surfaceContainer/High; để trống thì M3 lấy tím mặc định.
    surfaceContainer = Dark.surface,
    surfaceContainerHigh = Dark.surface,
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

/** Dựng [TextStyle] từ text style DayHoc (cỡ chữ, giãn dòng, giãn chữ theo Figma). */
private fun dhStyle(spec: Typo.Spec) = TextStyle(
    fontFamily = BeVietnamPro,
    fontWeight = FontWeight(spec.fontWeight),
    fontSize = spec.fontSize.sp,
    lineHeight = spec.lineHeight.sp,
    letterSpacing = spec.letterSpacing.sp,
)

/** Text style DayHoc (Figma). Màn dùng trực tiếp theo tên trong DS. */
object TeachHubText {
    val h1 = dhStyle(Typo.h1)
    val h2 = dhStyle(Typo.h2)
    val body = dhStyle(Typo.body)
    val bodyBold = dhStyle(Typo.bodyBold)
    val label = dhStyle(Typo.label)
    val caption = dhStyle(Typo.caption)
    val button = dhStyle(Typo.button)
    val buttonSmall = dhStyle(Typo.buttonSmall)
    val section = dhStyle(Typo.section)
}

/** Vai trò M3 ánh xạ sang thang DayHoc để chỗ nào còn dùng role cũng khớp Figma. */
private val TeachHubTypography = Typography(
    displayLarge = TeachHubText.h1,
    displayMedium = TeachHubText.h2,
    displaySmall = TeachHubText.h2,
    headlineLarge = TeachHubText.h1,
    headlineMedium = TeachHubText.h1,
    headlineSmall = TeachHubText.h2,
    titleLarge = TeachHubText.h2,
    titleMedium = TeachHubText.bodyBold,
    titleSmall = TeachHubText.label,
    bodyLarge = TeachHubText.body,
    bodyMedium = TeachHubText.body,
    bodySmall = TeachHubText.caption,
    labelLarge = TeachHubText.button,
    labelMedium = TeachHubText.label,
    labelSmall = TeachHubText.caption,
)

/** Màu viền nút phụ theo chế độ (token `buttonBorder`). DS đọc qua composition local. */
val LocalButtonBorderColor = staticCompositionLocalOf { Light.buttonBorder }

/** Theme DayHoc dựng hoàn toàn từ [TeachHubTokens]. */
@Composable
fun TeachHubTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalButtonBorderColor provides if (darkTheme) Dark.buttonBorder else Light.buttonBorder,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = TeachHubTypography,
            shapes = TeachHubShapes,
            content = content,
        )
    }
}
