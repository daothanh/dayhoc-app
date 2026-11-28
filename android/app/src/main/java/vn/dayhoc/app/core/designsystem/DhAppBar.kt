package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/**
 * App Bar `Root` (Figma Navigation/App Bar, A01): [leading] (chip vai trò), [title] H2 tuỳ chọn,
 * [trailing] bên phải. Biến thể `Detail` (A03): [onBack] thêm nút quay lại 44 ở đầu, [subtitle] là dòng Caption dưới tiêu đề. Cao 56, nền surface, viền dưới outlineVariant 1.5. Tiêu đề nhường chỗ trước.
 */
@Composable
fun DhAppBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    leading: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = if (onBack != null) Dimens.spacingInline else Dimens.spacingPage, end = Dimens.spacingInline),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingControl),
            ) {
                if (onBack != null) {
                    IconButton(onBack, Modifier.size(44.dp)) {
                        Icon(painterResource(R.drawable.ic_arrow_back), backDescription, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                leading()
                if (title != null && subtitle != null) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = TeachHubText.h2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            subtitle,
                            style = TeachHubText.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (title != null) Text(title, style = TeachHubText.h2, maxLines = 1, modifier = Modifier.weight(1f))
                else androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                trailing()
            }
            HorizontalDivider(thickness = 1.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
