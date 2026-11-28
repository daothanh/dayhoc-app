package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/**
 * Empty State (Figma C17/A24): minh hoạ, tiêu đề H3, mô tả Caption (rộng tối đa 300), nút chính nhỏ.
 */
@Composable
fun DhEmptyState(
    title: String,
    description: String?,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    illustration: DhIllustrationType = DhIllustrationType.Calendar,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = Dimens.spacingPage, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
    ) {
        DhIllustration(illustration)
        Text(title, style = TeachHubText.h3, color = colors.onSurface, textAlign = TextAlign.Center)
        if (description != null) Text(description, Modifier.widthIn(max = 300.dp), style = TeachHubText.caption, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        DhPrimaryButton(actionText, onAction, fullWidth = false, small = true)
    }
}
