package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/**
 * Banner thông tin của DS (Figma B09): viền nét đứt màu mực, icon info, tiêu đề đậm, mô tả nhạt.
 * Dùng cho ghi chú trong nội dung, không phải cảnh báo lỗi. [actionText] thêm nút phụ nhỏ bên phải (Figma A01 F).
 */
@Composable
fun DhInfoBanner(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.cardRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = colors.onSurface,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(Dimens.cardRadius.toPx() - stroke / 2f),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
            .padding(Dimens.spacingContent),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(painterResource(R.drawable.ic_info), null, Modifier.size(20.dp), tint = colors.onSurface)
        Spacer(Modifier.width(Dimens.spacingContent))
        Column(Modifier.weight(1f)) {
            Text(title, style = TeachHubText.bodyBold, color = colors.onSurface)
            Text(description, style = TeachHubText.caption, color = colors.onSurfaceVariant)
        }
        if (actionText != null) {
            Spacer(Modifier.width(Dimens.spacingContent))
            DhSecondaryButton(actionText, onAction, small = true)
        }
    }
}

/** C12 Banner Offline (Figma A25): full-width dưới App Bar, nền cảnh báo nhạt, đường đứt phía dưới. */
@Composable
fun DhOfflineBanner(title: String, description: String, modifier: Modifier = Modifier) {
    val warning = LocalWarningColor.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(warning.copy(alpha = 0.12f))
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawLine(
                    color = warning,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
            }
            .padding(Dimens.spacingContent),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(painterResource(R.drawable.ic_wifi_off), null, Modifier.size(20.dp), tint = warning)
        Spacer(Modifier.width(Dimens.spacingContent))
        Column(Modifier.weight(1f)) {
            Text(title, style = TeachHubText.bodyBold, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = TeachHubText.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
