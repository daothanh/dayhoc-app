package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/**
 * Nút chính: nền primary (lime), chữ onPrimary. Khi [loading] giữ nền lime, hiện spinner
 * và [loadingText] (Figma B03, B32); [small] = cao 40, nhãn Button S (Figma Primary Small); không bấm được khi loading hoặc [enabled] = false.
 */
@Composable
fun DhPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String = text,
    fullWidth: Boolean = true,
    small: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.then(if (fullWidth) Modifier.fillMaxWidth() else Modifier).defaultMinSize(minHeight = if (small) 40.dp else 48.dp),
        shape = RoundedCornerShape(Dimens.buttonRadius),
        contentPadding = if (small) PaddingValues(horizontal = Dimens.spacingContent) else ButtonDefaults.ContentPadding,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = Dimens.buttonElevation),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = if (loading) colors.primary else colors.surfaceContainerHighest,
            disabledContentColor = if (loading) colors.onPrimary else colors.onSurfaceVariant,
        ),
    ) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = colors.onPrimary,
                )
                Spacer(Modifier.width(Dimens.spacingControl))
                Text(loadingText, style = TeachHubText.button)
            }
        } else {
            Text(text, style = if (small) TeachHubText.buttonSmall else TeachHubText.button)
        }
    }
}

/**
 * Nút phụ trung tính (Figma Actions/Button Secondary): nền trong suốt, viền `buttonBorder` 2px,
 * chữ onSurface. [small] = cao 40, nhãn DayHoc/Button S cho nút nằm trong nội dung; [icon] là
 * icon dẫn trước nhãn (Figma: plus).
 */
@Composable
fun DhSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    small: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (enabled) LocalButtonBorderColor.current else colors.outlineVariant
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = if (small) 40.dp else 48.dp),
        shape = RoundedCornerShape(Dimens.buttonRadius),
        border = BorderStroke(2.dp, borderColor),
        contentPadding = PaddingValues(horizontal = if (small) Dimens.spacingContent else Dimens.spacingPage),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.spacingControl))
        }
        Text(text, style = if (small) TeachHubText.buttonSmall else TeachHubText.button)
    }
}

/**
 * Nút chữ (Figma "Actions/Text Button"): chữ màu link — lime/800 ở Light, lime/500 ở Dark — 13 SemiBold, vùng chạm 44.
 * Dùng thay `TextButton` của M3, vốn lấy `primary` lime nên không đọc được trên nền sáng (nút hộp thoại, "Bỏ qua").
 */
@Composable
fun DhTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
    ) {
        Text(text, style = TeachHubText.buttonSmall)
    }
}
