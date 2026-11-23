package vn.dayhoc.app.core.session

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

@StringRes
fun roleNameRes(role: String): Int = when (role) {
    Roles.TEACHER -> R.string.role_teacher
    Roles.PARENT -> R.string.role_parent
    else -> R.string.role_student
}

@StringRes
fun roleDescRes(role: String): Int = when (role) {
    Roles.TEACHER -> R.string.role_teacher_desc
    Roles.PARENT -> R.string.role_parent_desc
    else -> R.string.role_student_desc
}

/**
 * C20 Chip vai trò ở App Bar `Root` của mọi không gian. Một vai trò thì chỉ hiện tên;
 * nhiều vai trò thì có ▾ và chạm mở [RoleSwitcherSheet] (T00).
 */
@Composable
fun RoleChip(state: MembershipStore.State, onClick: () -> Unit) {
    val active = state.active ?: return
    val multi = state.items.size > 1
    val name = stringResource(roleNameRes(active.role))
    Surface(
        shape = CircleShape,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .then(if (multi) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RoleAvatar(name, 24.dp)
            Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            if (multi) Icon(painterResource(R.drawable.ic_caret_down), null, Modifier.size(16.dp))
        }
    }
}

/**
 * [T00] Chuyển vai trò: chỉ đổi vai trò đang mở ở shell rồi điều hướng, không đăng nhập lại,
 * nên dưới 1 giây (US-0.11 AC1). Dòng của vai trò hiện tại không bấm được.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleSwitcherSheet(
    state: MembershipStore.State,
    onDismiss: () -> Unit,
    onSelect: (MembershipDto) -> Unit,
    onAddRole: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Dimens.spacingContent)) {
            Text(
                stringResource(R.string.role_switch_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.spacingPage, vertical = Dimens.spacingControl),
            )
            state.items.forEach { m ->
                val current = m.id == state.active?.id
                val name = stringResource(roleNameRes(m.role))
                // Phạm vi lấy từ nhãn API ("Giáo viên · Cô Lan" → "Cô Lan"), không có thì mô tả vai trò.
                val scope = m.label.substringAfter(" · ", "").ifEmpty { stringResource(roleDescRes(m.role)) }
                SheetRow(
                    leading = { RoleAvatar(name, 36.dp) },
                    title = name,
                    subtitle = scope,
                    selected = current,
                    onClick = if (current) null else ({ onSelect(m) }),
                )
            }
            HorizontalDivider()
            SheetRow(
                leading = { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(36.dp).padding(6.dp)) },
                title = stringResource(R.string.role_add),
                onClick = onAddRole,
            )
        }
    }
}

@Composable
private fun SheetRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface)
            .padding(horizontal = Dimens.spacingPage, vertical = Dimens.spacingControl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp))
    }
}

@Composable
private fun RoleAvatar(name: String, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        Alignment.Center,
    ) {
        Text(
            name.take(1),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = (size.value * 0.45f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
