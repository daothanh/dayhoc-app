package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/**
 * C05 Segmented Control (Figma Selection/Segmented Control): 2–4 ô chia đều, cao 40, viền 1.5.
 * Ô chọn nền lime (action/selected-bg), chữ Button S.
 */
@Composable
fun DhSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.cardRadius)
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.5.dp, colors.outlineVariant, shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(if (selected) colors.primary else colors.surface)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .semantics { this.selected = selected; role = Role.RadioButton; contentDescription = label }
                    .padding(horizontal = Dimens.spacingControl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = TeachHubText.buttonSmall,
                    color = if (selected) colors.onPrimary else colors.onSurface,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
