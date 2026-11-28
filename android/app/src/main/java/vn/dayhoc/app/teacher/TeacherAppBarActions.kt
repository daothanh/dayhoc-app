package vn.dayhoc.app.teacher

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhBellButton
import vn.dayhoc.app.core.designsystem.DhChip
import vn.dayhoc.app.core.designsystem.DhChipStyle
import vn.dayhoc.app.teacher.today.TodayUiState

/**
 * Phần phải của App Bar `Root` ở mọi tab giáo viên (Figma A01, A10, A18…): chip đồng bộ nét đứt + chuông
 * có chấm khi còn thông báo chưa đọc. Phản ánh lần tải gần nhất của Hôm nay; đổi tab không gọi thêm API.
 */
@Composable
fun RowScope.TeacherAppBarActions(state: TodayUiState, onBell: () -> Unit) {
    DhChip(
        // Số buổi còn chờ đồng bộ đọc từ outbox (Figma A25): hiện cả khi mất mạng.
        if (state.pending.isNotEmpty()) {
            stringResource(R.string.today_sync_pending, state.pending.size)
        } else {
            stringResource(
                when {
                    state.offline -> R.string.today_sync_offline
                    state.syncing -> R.string.today_sync_busy
                    else -> R.string.today_sync_done
                },
            )
        },
        style = DhChipStyle.Dashed,
    )
    DhBellButton(state.data?.unread ?: 0, stringResource(R.string.today_notifications), onBell)
}
