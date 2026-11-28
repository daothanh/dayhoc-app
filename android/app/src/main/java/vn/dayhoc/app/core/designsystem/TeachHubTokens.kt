// File sinh bởi tool/gen_tokens.py từ tokens/teachhub.tokens.json. Không sửa tay.
package vn.dayhoc.app.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object TeachHubTokens {
    object Light {
        val primary = Color(0xFFBDFF3E)
        val onPrimary = Color(0xFF1F1F1F)
        val primaryContainer = Color(0xFFF7FFE6)
        val onPrimaryContainer = Color(0xFF1F1F1F)
        val secondaryContainer = Color(0xFFF7FFE6)
        val onSecondaryContainer = Color(0xFF1F1F1F)
        val secondary = Color(0xFF4F7300)
        val onSecondary = Color(0xFFFFFFFF)
        val error = Color(0xFFC62828)
        val errorContainer = Color(0xFFFDECEC)
        val onErrorContainer = Color(0xFFA31F1F)
        val surface = Color(0xFFFFFFFF)
        val onSurface = Color(0xFF1F1F1F)
        val onSurfaceVariant = Color(0xFF52524C)
        val surfaceContainerLow = Color(0xFFF0F0EB)
        val surfaceContainerHighest = Color(0xFFE4E4DE)
        val outline = Color(0xFF8C8C84)
        val outlineVariant = Color(0xFFE4E4DE)
        val inverseSurface = Color(0xFF1F1F1F)
        val onInverseSurface = Color(0xFFFFFFFF)
        val surfaceTint = Color(0xFFFFFFFF)
        val scaffoldBackground = Color(0xFFF7F7F4)
        val divider = Color(0xFFF0F0EB)
        val buttonBorder = Color(0xFFD3D3CB)
        val primaryColor = Color(0xFFBDFF3E)
        val successColor = Color(0xFF047857)
        val warningColor = Color(0xFFA15C07)
        val infoColor = Color(0xFF1F1F1F)
        val placeholderColor = Color(0xFF6B6B64)
        val sectionHeaderBackground = Color(0xFFF0F0EB)
        val sectionHeaderForeground = Color(0xFF1F1F1F)
        val focusRingColor = Color(0xFF4F7300)
    }
    object Dark {
        val primary = Color(0xFFBDFF3E)
        val onPrimary = Color(0xFF1F1F1F)
        val primaryContainer = Color(0xFF1E2610)
        val onPrimaryContainer = Color(0xFFF2F2EE)
        val secondaryContainer = Color(0xFF1E2610)
        val onSecondaryContainer = Color(0xFFF2F2EE)
        val secondary = Color(0xFFBDFF3E)
        val onSecondary = Color(0xFF1F1F1F)
        val error = Color(0xFFEF4444)
        val errorContainer = Color(0xFF2D1212)
        val onErrorContainer = Color(0xFFFCA5A5)
        val surface = Color(0xFF171716)
        val onSurface = Color(0xFFF2F2EE)
        val onSurfaceVariant = Color(0xFFB4B4AC)
        val surfaceContainerLow = Color(0xFF1F1F1D)
        val surfaceContainerHighest = Color(0xFF2A2A27)
        val outline = Color(0xFF6B6B64)
        val outlineVariant = Color(0xFF34342F)
        val inverseSurface = Color(0xFFF2F2EE)
        val onInverseSurface = Color(0xFF1F1F1F)
        val surfaceTint = Color(0xFF171716)
        val scaffoldBackground = Color(0xFF0F0F0E)
        val divider = Color(0xFF1F1F1D)
        val buttonBorder = Color(0xFF6B6B64)
        val primaryColor = Color(0xFFBDFF3E)
        val successColor = Color(0xFF6EE7B7)
        val warningColor = Color(0xFFFCD34D)
        val infoColor = Color(0xFFF2F2EE)
        val placeholderColor = Color(0xFF8C8C84)
        val sectionHeaderBackground = Color(0xFF1F1F1D)
        val sectionHeaderForeground = Color(0xFFF2F2EE)
        val focusRingColor = Color(0xFFBDFF3E)
    }
    object Dimens {
        val cardRadius = 14.dp
        val buttonRadius = 14.dp
        val fieldRadius = 12.dp
        val controlRadius = 14.dp
        val dialogRadius = 20.dp
        val sheetRadius = 28.dp
        val tagRadius = 999.dp
        val badgeRadius = 999.dp
        val pillRadius = 999.dp
        val checkboxRadius = 3.dp
        val spacingInline = 4.dp
        val spacingControl = 8.dp
        val spacingContent = 12.dp
        val spacingSection = 24.dp
        val spacingPage = 16.dp
        val spacingPageLarge = 24.dp
        val focusRingWidth = 2.dp
        val buttonElevation = 0.dp
        val appBarElevation = 0.dp
    }
    /** Text style DayHoc (Figma). Dựng TextStyle ở TeachHubTheme. */
    object Typo {
        data class Spec(
            val fontSize: Float,
            val lineHeight: Float,
            val fontWeight: Int,
            val letterSpacing: Float,
        )

        val h1 = Spec(20f, 28f, 600, -0.3f)
        val h2 = Spec(17f, 24f, 600, -0.17f)
        val h3 = Spec(15f, 22f, 600, -0.075f)
        val body = Spec(14f, 20f, 400, 0f)
        val bodyBold = Spec(14f, 20f, 600, 0f)
        val label = Spec(12f, 16f, 500, 0f)
        val caption = Spec(12f, 16f, 400, 0f)
        val button = Spec(15f, 20f, 600, 0f)
        val buttonSmall = Spec(13f, 18f, 600, 0f)
        val section = Spec(12f, 16f, 600, 0.6f)
    }
}
