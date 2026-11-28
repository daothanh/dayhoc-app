package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/** C07 Card: Emphasis (nổi, viền mảnh) · Default · Muted (nền Fill). Pressed đổi nền sang Fill. */
enum class DhCardStyle { Emphasis, Default, Muted }

@Composable
fun DhCard(
    modifier: Modifier = Modifier,
    style: DhCardStyle = DhCardStyle.Default,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.cardRadius)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val container = if (pressed || style == DhCardStyle.Muted) colors.surfaceContainerLow else colors.surface
    val border = if (style == DhCardStyle.Emphasis) BorderStroke(1.dp, colors.surfaceContainerLow) else BorderStroke(1.5.dp, colors.outlineVariant)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (style == DhCardStyle.Emphasis) Modifier.shadow(1.dp, shape) else Modifier)
            .clip(shape)
            .background(container)
            .border(border, shape)
            .then(if (onClick != null) Modifier.clickable(source, null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(Dimens.spacingContent),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingControl),
        content = content,
    )
}
