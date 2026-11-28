package vn.dayhoc.app.core.session

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhAppBar

/**
 * Khung chung của mỗi không gian vai trò: App Bar `Root` có C20 Chip vai trò mở [RoleSwitcherSheet] (T00).
 * Nội dung vẽ toàn màn dưới App Bar; bottom nav nổi (nếu có) do không gian tự đặt trong [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleSpaceScaffold(
    store: MembershipStore,
    onSwitched: (MembershipDto) -> Unit,
    onAddRole: () -> Unit,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val state by store.state.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current

    Scaffold(
        topBar = { DhAppBar(title = title, leading = { RoleChip(state) { sheet = true } }, trailing = actions) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), content = content)
    }

    if (sheet) {
        RoleSwitcherSheet(
            state = state,
            onDismiss = { sheet = false },
            onSelect = { m ->
                sheet = false
                scope.launch {
                    store.select(m)
                    val name = resources.getString(roleNameRes(m.role))
                    Toast.makeText(context, resources.getString(R.string.role_switched_to, name), Toast.LENGTH_SHORT).show()
                    onSwitched(m)
                }
            },
            onAddRole = {
                sheet = false
                onAddRole()
            },
        )
    }
}
