package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/** C06 Chip trạng thái (Figma Display/Tag): Outline · Filled · Success · Dashed · Counter. */
enum class DhChipStyle { Outline, Filled, Success, Dashed, Counter }

@Composable
fun DhChip(text: String, modifier: Modifier = Modifier, style: DhChipStyle = DhChipStyle.Outline) {
    val colors = MaterialTheme.colorScheme
    val success = LocalSuccessColor.current
    val shape = RoundedCornerShape(Dimens.tagRadius)
    val stroke = 1.5.dp
    val (container, content) = when (style) {
        DhChipStyle.Filled -> colors.inverseSurface to colors.inverseOnSurface
        DhChipStyle.Counter -> colors.onErrorContainer to colors.inverseOnSurface
        DhChipStyle.Success -> colors.surface to success
        else -> colors.surface to colors.onSurface
    }
    val borderColor = when (style) {
        DhChipStyle.Success -> success
        else -> colors.outlineVariant
    }
    val bordered = style == DhChipStyle.Outline || style == DhChipStyle.Success
    Row(
        modifier = modifier
            .height(24.dp)
            .defaultMinSize(minWidth = 24.dp)
            .clip(shape)
            .background(container)
            .then(if (bordered) Modifier.border(BorderStroke(stroke, borderColor), shape) else Modifier)
            .then(if (style == DhChipStyle.Dashed) Modifier.dashedBorder(borderColor, Dimens.tagRadius, stroke) else Modifier)
            .padding(start = if (style == DhChipStyle.Success) Dimens.spacingInline else Dimens.spacingControl, end = Dimens.spacingControl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingInline, Alignment.CenterHorizontally),
    ) {
        if (style == DhChipStyle.Success) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(12.dp), tint = content)
        Text(text, style = TeachHubText.label, color = content, maxLines = 1)
    }
}

/** Viền nét đứt bo góc dùng chung cho chip Dashed và banner. */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier =
    drawBehind {
        val w = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(w / 2f, w / 2f),
            size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(radius.toPx().coerceAtMost((size.height - w) / 2f) - w / 2f),
            style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
        )
    }
