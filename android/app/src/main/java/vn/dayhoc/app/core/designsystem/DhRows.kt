package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/** C08 List Row biến thể `Expanded` (Figma A04): nền Fill + bo [Dimens.fieldRadius] khi mở hoặc [highlighted] (viền lime, A26). */
@Composable
fun DhExpandableRow(
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    main: @Composable RowScope.() -> Unit,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (expanded || highlighted) Dimens.fieldRadius else 0.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (expanded) colors.surfaceContainerLow else colors.surface)
            .then(if (highlighted && !expanded) Modifier.border(1.5.dp, colors.primary, shape) else Modifier),
    ) {
        Column(Modifier.padding(Dimens.spacingControl), verticalArrangement = Arrangement.spacedBy(Dimens.spacingControl)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 40.dp)
                    .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
                content = main,
            )
            if (expanded && expandedContent != null) expandedContent()
        }
        if (!expanded && !highlighted) HorizontalDivider(color = colors.surfaceContainerLow)
    }
}

/** C13 Bottom Action Bar (Figma Actions/Bottom Action Bar): nền surface, viền trên 1.5, chừa vùng điều hướng hệ thống. */
@Composable
fun DhBottomActionBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(thickness = 1.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.padding(start = Dimens.spacingPage, end = Dimens.spacingPage, top = Dimens.spacingContent, bottom = Dimens.spacingPage),
                content = content,
            )
        }
    }
}

/** Icon Button chuẩn 44 (Figma Actions/Icon Button): icon 24, tô màu onSurface. */
@Composable
fun DhIconButton(iconRes: Int, description: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier.size(44.dp)) {
        Icon(painterResource(iconRes), description, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Mũi tên phải 20 ở cuối List Row (Figma caret-right). */
@Composable
fun DhChevron(modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_arrow_right), null, modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
}
