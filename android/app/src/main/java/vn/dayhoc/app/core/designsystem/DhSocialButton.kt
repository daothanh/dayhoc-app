package vn.dayhoc.app.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

// Màu thương hiệu Apple/Google đi theo hướng dẫn thương hiệu và đổi theo Light/Dark; rule 30 §4 cho
// phép hardcode màu thương hiệu nên không đưa vào token sinh.
// Figma "Auth/Social Button" (131:2430): Light — Apple nền đen chữ trắng, Google nền trắng viền
// #747775 chữ #1F1F1F · Dark — Apple nền trắng, Google nền #131314.
private val AppleLightFill = Color(0xFF000000)
private val AppleLightContent = Color(0xFFFFFFFF)
private val AppleDarkFill = Color(0xFFFFFFFF)
private val AppleDarkContent = Color(0xFF1F1F1F)
private val GoogleLightBorder = Color(0xFF747775)
private val GoogleLightContent = Color(0xFF1F1F1F)
private val GoogleDarkFill = Color(0xFF131314)
private val GoogleDarkBorder = Color(0xFF8E918F)
private val GoogleDarkContent = Color(0xFFE3E3E3)

/**
 * "Tiếp tục với Apple" (Figma B01): Light nền đen chữ trắng, Dark nền trắng chữ đen, cao 48,
 * bo [Dimens.buttonRadius]. Chỉ dựng giao diện; luồng SSO thật làm ở task riêng.
 */
@Composable
fun DhAppleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fill = if (isSystemInDarkTheme()) AppleDarkFill else AppleLightFill
    val content = if (isSystemInDarkTheme()) AppleDarkContent else AppleLightContent
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(Dimens.buttonRadius),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = Dimens.buttonElevation),
        colors = ButtonDefaults.buttonColors(
            containerColor = fill,
            contentColor = content,
            disabledContainerColor = fill.copy(alpha = 0.4f),
            disabledContentColor = content,
        ),
    ) {
        SocialLabel(icon = R.drawable.ic_logo_apple, text = text, tint = content)
    }
}

/**
 * "Tiếp tục với Google" (Figma B01): Light nền surface viền #747775, Dark nền #131314 viền #8E918F,
 * cao 48. Logo Google giữ 4 màu gốc nên không tint.
 */
@Composable
fun DhGoogleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val border = when {
        !enabled -> colors.outlineVariant
        dark -> GoogleDarkBorder
        else -> GoogleLightBorder
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(Dimens.buttonRadius),
        border = BorderStroke(1.dp, border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (dark) GoogleDarkFill else colors.surface,
            contentColor = if (dark) GoogleDarkContent else GoogleLightContent,
        ),
    ) {
        SocialLabel(icon = R.drawable.ic_logo_google, text = text, tint = null)
    }
}

@Composable
private fun SocialLabel(@DrawableRes icon: Int, text: String, tint: Color?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingControl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = tint?.let { ColorFilter.tint(it) },
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
