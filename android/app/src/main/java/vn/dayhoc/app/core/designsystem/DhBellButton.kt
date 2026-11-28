package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/** Nút chuông App Bar (Figma Actions/Icon Button 44 + Badge Dot 8): chấm đỏ góc trên phải khi [unread] > 0. */
@Composable
fun DhBellButton(unread: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick), Alignment.Center) {
        Icon(painterResource(R.drawable.ic_bell), contentDescription, Modifier.size(Dimens.spacingSection))
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopStart).offset(x = 28.dp, y = 10.dp).size(Dimens.spacingControl).clip(CircleShape)
                    .background(colors.onErrorContainer).border(1.5.dp, colors.background, CircleShape),
            )
        }
    }
}
