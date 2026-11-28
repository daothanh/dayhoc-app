package vn.dayhoc.app.teacher.attendance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhListRow
import vn.dayhoc.app.core.designsystem.DhPrimaryButton
import vn.dayhoc.app.core.designsystem.TeachHubText
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens
import vn.dayhoc.app.teacher.today.TodaySession
import vn.dayhoc.app.teacher.today.formatSheetDay
import vn.dayhoc.app.teacher.today.formatTimeRange

/**
 * [T05a] Bottom sheet Chi tiết buổi (Figma A02). [loadRepeat] trả "T2-T4-T6" từ quy tắc lặp của lớp (`GET /classes/{id}`);
 * không có thì dòng ngày bỏ phần "lặp". Dời / Tạo buổi bù / Hủy buổi là T05b, T05c: [onComingSoon].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    session: TodaySession,
    zone: ZoneId,
    loadRepeat: suspend (classId: String) -> String?,
    onAttend: () -> Unit,
    onDismiss: () -> Unit,
    onComingSoon: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val repeat by produceState<String?>(null, session.classId) { value = loadRepeat(session.classId) }
    val day = formatSheetDay(session.start.atZone(zone).toLocalDate()) + " · " + formatTimeRange(session.start, session.end, zone)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = Dimens.spacingPage).padding(bottom = Dimens.spacingSection),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.spacingContent),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(session.className, style = TeachHubText.h2, color = colors.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
                IconButton(onDismiss, Modifier.size(44.dp)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.session_sheet_close), tint = colors.onSurface)
                }
            }
            Text(
                repeat?.let { stringResource(R.string.session_sheet_repeat, day, it) } ?: day,
                style = TeachHubText.body,
                color = colors.onSurfaceVariant,
            )
            when (sheetAction(session.status, session.pending)) {
                SheetAction.Attend -> DhPrimaryButton(stringResource(R.string.today_attendance), onAttend)
                SheetAction.Edit -> DhPrimaryButton(stringResource(R.string.session_sheet_edit_attendance), onAttend)
                SheetAction.None -> Unit
            }
            Chevron(stringResource(R.string.session_sheet_reschedule), stringResource(R.string.session_sheet_reschedule_hint), onComingSoon)
            Chevron(stringResource(R.string.session_sheet_makeup), null, onComingSoon)
            Chevron(stringResource(R.string.session_sheet_cancel), stringResource(R.string.session_sheet_cancel_hint), onComingSoon)
            Text(stringResource(R.string.session_sheet_note), style = TeachHubText.caption, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Chevron(title: String, subtitle: String?, onClick: () -> Unit) {
    DhListRow(title, subtitle = subtitle, onClick = onClick, trailing = {
        Icon(painterResource(R.drawable.ic_arrow_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
    })
}
