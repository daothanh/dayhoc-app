package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/** Đường phân cách có nhãn ở giữa (Figma B01: "hoặc dùng email"). */
@Composable
fun DhDivider(label: String, modifier: Modifier = Modifier) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Token `divider` chưa nằm trong M3 ColorScheme nên lấy trực tiếp theo mode.
    val lineColor = if (isSystemInDarkTheme()) TeachHubTokens.Dark.divider else TeachHubTokens.Light.divider
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = lineColor,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = labelColor)
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = lineColor,
        )
    }
}
