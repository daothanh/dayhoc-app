package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/**
 * App Bar `Root` (Figma Navigation/App Bar, A01): [leading] (chip vai trò), [title] H2 tuỳ chọn,
 * [trailing] bên phải. Khoảng cách giữa các phần tử 4 (Figma A02/A25); tiêu đề co trước, chip và nút không co, cắt bằng "…". Biến thể `Detail` (A03): [onBack] thêm nút quay lại 44 ở đầu, [subtitle] là dòng Caption dưới tiêu đề. Cao 56, nền surface, viền dưới outlineVariant 1.5. Tiêu đề nhường chỗ trước.
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
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingInline),
            ) {
                if (onBack != null) {
                    DhIconButton(R.drawable.ic_arrow_back, backDescription, onBack)
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
                } else if (title != null) Text(title, style = TeachHubText.h2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                else androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                trailing()
            }
            HorizontalDivider(thickness = 1.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
