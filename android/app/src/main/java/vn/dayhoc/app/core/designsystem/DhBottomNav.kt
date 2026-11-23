package vn.dayhoc.app.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

data class DhNavItem(val label: String, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int)

/** Chiều cao thanh kể cả lề ngoài: danh sách đặt padding đáy bằng số này để mục cuối không bị che. */
val DhBottomNavHeight = 82.dp

/**
 * Bottom Nav dạng viên nổi (Design System, 22/11/2026): thanh bo tròn nổi trên nội dung,
 * tab đang chọn là viên lime trượt 220 ms, rung nhẹ khi đổi tab.
 * Chưa có blur nền (Compose không blur lớp phía dưới): giữ nền surface 88% như spec cho phép.
 */
@Composable
fun DhBottomNav(
    items: List<DhNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Dimens.spacingPage, vertical = 8.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = colors.surface.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BoxWithConstraints(Modifier.padding(4.dp)) {
                val itemWidth = maxWidth / items.size
                val indicatorX by animateDpAsState(
                    targetValue = itemWidth * selected,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "navIndicator",
                )
                Box(
                    Modifier
                        .offset { IntOffset(indicatorX.roundToPx(), 0) }
                        .width(itemWidth)
                        .height(56.dp)
                        .clip(CircleShape)
                        .background(colors.primary),
                )
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    items.forEachIndexed { index, item ->
                        val active = index == selected
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(CircleShape)
                                .selectable(
                                    selected = active,
                                    role = Role.Tab,
                                    onClick = {
                                        if (!active) {
                                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            onSelect(index)
                                        }
                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            val tint = if (active) colors.onPrimary else colors.onSurfaceVariant
                            Icon(
                                painterResource(if (active) item.selectedIcon else item.icon),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(
                                item.label,
                                color = tint,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
