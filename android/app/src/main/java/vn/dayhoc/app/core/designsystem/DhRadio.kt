package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Nút chọn một của DS (Figma Design System): chọn thì nền lime và chấm mực, chưa chọn thì viền outline.
 * Vẽ 20x20; vùng chạm 44 do hàng bao ngoài lo (dùng `selectable`).
 */
@Composable
fun DhRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.size(20.dp)) {
        val stroke = 1.5.dp.toPx()
        if (selected) {
            drawCircle(color = colors.primary, radius = size.minDimension / 2f)
            drawCircle(color = colors.onSurface, radius = 4.dp.toPx())
        } else {
            drawCircle(
                color = colors.outline,
                radius = size.minDimension / 2f - stroke / 2f,
                style = Stroke(width = stroke),
            )
        }
    }
}
