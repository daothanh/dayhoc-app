package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/**
 * Empty State (Figma A24): khối minh hoạ, tiêu đề, mô tả, nút chính nhỏ.
 * shortcut: minh hoạ là khối Fill giữ chỗ như Figma, thay khi DS xuất bộ minh hoạ.
 */
@Composable
fun DhEmptyState(title: String, description: String?, actionText: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(vertical = Dimens.spacingSection),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingControl),
    ) {
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(Dimens.dialogRadius)).background(colors.surfaceContainerHighest))
        Text(title, style = TeachHubText.bodyBold, color = colors.onSurface, textAlign = TextAlign.Center)
        if (description != null) Text(description, style = TeachHubText.caption, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        DhPrimaryButton(actionText, onAction, Modifier.padding(top = Dimens.spacingControl), fullWidth = false)
    }
}
