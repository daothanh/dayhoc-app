package vn.dayhoc.app.teacher.attendance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhAppBar
import vn.dayhoc.app.core.designsystem.DhAvatar
import vn.dayhoc.app.core.designsystem.DhBottomActionBar
import vn.dayhoc.app.core.designsystem.DhChip
import vn.dayhoc.app.core.designsystem.DhChipStyle
import vn.dayhoc.app.core.designsystem.DhEmptyState
import vn.dayhoc.app.core.designsystem.DhExpandableRow
import vn.dayhoc.app.core.designsystem.DhInfoBanner
import vn.dayhoc.app.core.designsystem.DhOfflineBanner
import vn.dayhoc.app.core.designsystem.DhPrimaryButton
import vn.dayhoc.app.core.designsystem.DhSecondaryButton
import vn.dayhoc.app.core.designsystem.DhSegmented
import vn.dayhoc.app.core.designsystem.DhSkeletonRow
import vn.dayhoc.app.core.designsystem.TeachHubText
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens
import vn.dayhoc.app.teacher.today.formatShortDay
import vn.dayhoc.app.teacher.today.formatTime
import vn.dayhoc.app.teacher.today.toInstantOrNull

private val SEGMENTS = listOf(AttStatus.Present, AttStatus.Late, AttStatus.Excused, AttStatus.Unexcused)
private val STAMP = DateTimeFormatter.ofPattern("HH:mm dd/MM")

/** [T04] Điểm danh (Figma A03–A09, A26–A28). Màn đẩy, không có bottom nav. [endsAt] lấy từ thẻ buổi ở T03. */
@Composable
fun AttendanceScreen(viewModel: AttendanceViewModel, endsAt: String?, zone: ZoneId, onClose: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val canLeaveFreely = !s.dirty || s.readOnly != null
    // Đang lưu/bỏ: không rời màn (lô đang gửi hoặc đang xóa phải xong trước).
    val back = { if (s.saving) Unit else if (canLeaveFreely) onClose() else viewModel.askDiscard() }
    BackHandler(enabled = s.saving || !canLeaveFreely) { back() }
    LaunchedEffect(s.done) { if (s.done) onClose() }

    val staleText = stringResource(R.string.attendance_stale)
    LaunchedEffect(s.staleNotice) {
        if (s.staleNotice) {
            viewModel.staleNoticeShown()
            snackbar.showSnackbar(staleText)
        }
    }
    val error = s.error
    val errorText = error?.let { stringResource(errorRes(it.code)) }
    val retryText = stringResource(R.string.today_retry)
    LaunchedEffect(error) {
        if (error != null && errorText != null) {
            val r = snackbar.showSnackbar(errorText, if (error.retry) retryText else null, duration = SnackbarDuration.Long)
            viewModel.errorShown()
            if (r == SnackbarResult.ActionPerformed) viewModel.save()
        }
    }

    val title = s.className ?: stringResource(R.string.attendance_title)
    Scaffold(
        topBar = {
            DhAppBar(
                title = if (s.editMode) stringResource(R.string.attendance_title_edit, title) else title,
                subtitle = subtitle(s.startsAt, endsAt, zone),
                onBack = back,
                backDescription = stringResource(R.string.back),
            )
        },
        bottomBar = { if (s.readOnly == null && !s.loading && !s.loadFailed) ActionBar(s, viewModel::save) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                s.loading -> Column(Modifier.padding(Dimens.spacingPage)) { repeat(6) { DhSkeletonRow() } }
                s.loadFailed -> DhEmptyState(
                    stringResource(R.string.attendance_load_failed), null, stringResource(R.string.today_retry), viewModel::load,
                )
                else -> Body(s, zone, viewModel)
            }
        }
    }

    if (s.confirmDiscard) DiscardDialog(viewModel::stay, viewModel::discard)
}

@Composable
private fun subtitle(start: Instant?, end: String?, zone: ZoneId): String? {
    start ?: return null
    val endAt = end?.let(::toInstantOrNull)
    val range = if (endAt != null) stringResource(R.string.attendance_time_range, formatTime(start, zone), formatTime(endAt, zone)) else formatTime(start, zone)
    return stringResource(R.string.attendance_subtitle, formatShortDay(start.atZone(zone).toLocalDate()), range)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Body(s: AttendanceUiState, zone: ZoneId, vm: AttendanceViewModel) {
    if (s.offline && s.readOnly == null) {
        DhOfflineBanner(stringResource(R.string.attendance_offline_title), stringResource(R.string.attendance_offline_body))
    }
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingPage)) {
        s.readOnly?.let { ro ->
            item {
                Text(
                    stringResource(if (ro == ReadOnly.Cancelled) R.string.attendance_readonly_cancelled else R.string.attendance_readonly_expired),
                    style = TeachHubText.body,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dimens.spacingControl),
                )
            }
        }
        s.failedCode?.let { code ->
            item {
                DhInfoBanner(
                    title = stringResource(R.string.attendance_sync_failed_title),
                    description = stringResource(errorRes(code)),
                    modifier = Modifier.padding(bottom = Dimens.spacingControl),
                    actionText = stringResource(R.string.attendance_sync_failed_resend),
                    onAction = vm::resend,
                )
            }
        }
        if (s.editMode && s.readOnly == null) item {
            DhInfoBanner(
                title = s.lastEditedAt?.let {
                    val at = STAMP.format(it.atZone(zone))
                    s.lastEditedBy?.let { by -> stringResource(R.string.attendance_edit_banner_title_by, by, at) }
                        ?: stringResource(R.string.attendance_edit_banner_title, at)
                }
                    ?: stringResource(R.string.attendance_edit_banner_title_none),
                description = stringResource(R.string.attendance_edit_banner_body),
                modifier = Modifier.padding(bottom = Dimens.spacingControl),
            )
        }
        if (s.readOnly == null && !s.editMode) item {
            Text(
                stringResource(R.string.attendance_hint),
                style = TeachHubText.body,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Dimens.spacingControl),
            )
        }
        // Hàng bộ đếm dính trên khi cuộn (spec T04 mục 8).
        stickyHeader {
            val c = s.counts
            Row(
                Modifier.fillMaxWidth().background(colors.background).padding(bottom = Dimens.spacingControl),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingControl),
            ) {
                DhChip(stringResource(R.string.attendance_count_present, c.present), style = DhChipStyle.Success)
                DhChip(stringResource(R.string.attendance_count_absent, c.absent), style = DhChipStyle.Danger)
                DhChip(stringResource(R.string.attendance_count_late, c.late), style = DhChipStyle.Warning)
            }
        }
        item {
            Text(
                stringResource(R.string.attendance_legend),
                style = TeachHubText.caption,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Dimens.spacingControl),
            )
        }
        items(s.rows, key = { it.studentId }) { row ->
            StudentRow(row, expanded = s.expandedId == row.studentId, enabled = s.readOnly == null, onToggle = { vm.toggle(row.studentId) }, onSelect = { vm.select(row.studentId, it) })
        }
    }
}

@Composable
private fun parentReportedText(reason: String?): String {
    val base = stringResource(R.string.attendance_row_parent_reported)
    val label = when (reason) {
        "sick" -> R.string.attendance_reason_sick
        "family" -> R.string.attendance_reason_family
        "other" -> R.string.attendance_reason_other
        else -> null
    } ?: return base
    return "$base · ${stringResource(label)}"
}

@Composable
private fun StudentRow(row: AttRow, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit, onSelect: (AttStatus) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val subtitle = when {
        row.parentReported -> parentReportedText(row.parentReason)
        row.changed -> stringResource(R.string.attendance_row_changed)
        else -> null
    }
    DhExpandableRow(
        expanded = expanded,
        highlighted = row.changed,
        onClick = if (enabled) onToggle else null,
        main = {
            DhAvatar(row.name)
            Column(Modifier.weight(1f)) {
                Text(
                    row.name,
                    style = if (expanded) TeachHubText.bodyBold else TeachHubText.body,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = TeachHubText.caption, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StatusChip(row.status)
        },
        expandedContent = {
            DhSegmented(
                options = SEGMENTS.map { stringResource(statusLabel(it)) },
                selectedIndex = SEGMENTS.indexOf(row.status),
                onSelect = { onSelect(SEGMENTS[it]) },
            )
        },
    )
}

private fun statusLabel(s: AttStatus) = when (s) {
    AttStatus.Present -> R.string.attendance_status_present
    AttStatus.Late -> R.string.attendance_status_late
    AttStatus.Excused -> R.string.attendance_status_excused
    AttStatus.Unexcused -> R.string.attendance_status_unexcused
}

private fun errorRes(code: String?) = when (code) {
    "ATTENDANCE_EDIT_WINDOW_CLOSED" -> R.string.attendance_error_window_closed
    "SESSION_CANCELLED" -> R.string.attendance_error_cancelled
    "NOT_FOUND" -> R.string.attendance_error_not_found
    "CORRUPT" -> R.string.attendance_error_corrupt
    AttendanceViewModel.OWNER_MISSING -> R.string.attendance_error_owner
    AttendanceViewModel.DISCARD_FAILED -> R.string.attendance_error_discard
    else -> R.string.attendance_error_generic
}

@Composable
private fun StatusChip(s: AttStatus) {
    val style = when (s) {
        AttStatus.Present -> DhChipStyle.Success
        AttStatus.Late -> DhChipStyle.Warning
        AttStatus.Excused -> DhChipStyle.WarningFilled
        AttStatus.Unexcused -> DhChipStyle.DangerFilled
    }
    DhChip(stringResource(statusLabel(s)), style = style)
}

/** Bottom Action Bar (Figma C13): nút Primary; chế độ sửa disabled tới khi có thay đổi. */
@Composable
private fun ActionBar(s: AttendanceUiState, onSave: () -> Unit) {
    val text = when (val l = s.label) {
        SaveLabel.Save -> stringResource(R.string.attendance_save)
        is SaveLabel.Notify -> stringResource(R.string.attendance_save_notify, l.n)
        SaveLabel.SaveChanges -> stringResource(R.string.attendance_save_changes)
        SaveLabel.SaveLocal -> stringResource(R.string.attendance_save_local)
    }
    DhBottomActionBar {
        DhPrimaryButton(
            text = text,
            onClick = onSave,
            enabled = s.canSave || s.saving,
            loading = s.saving,
            loadingText = stringResource(R.string.attendance_saving),
        )
    }
}

/** Hộp thoại A07: "Ở lại" (phụ) / "Bỏ thay đổi" (chính). */
@Composable
private fun DiscardDialog(onStay: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.attendance_discard_title)) },
        text = { Text(stringResource(R.string.attendance_discard_body)) },
        dismissButton = { DhSecondaryButton(stringResource(R.string.attendance_discard_stay), onStay, small = true) },
        confirmButton = {
            DhPrimaryButton(stringResource(R.string.attendance_discard_confirm), onDiscard, fullWidth = false)
        },
    )
}
