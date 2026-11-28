package vn.dayhoc.app.teacher.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhBellButton
import vn.dayhoc.app.core.designsystem.DhBottomNavHeight
import vn.dayhoc.app.core.designsystem.DhCard
import vn.dayhoc.app.core.designsystem.DhCardStyle
import vn.dayhoc.app.core.designsystem.DhChip
import vn.dayhoc.app.core.designsystem.DhChipStyle
import vn.dayhoc.app.core.designsystem.DhEmptyState
import vn.dayhoc.app.core.designsystem.DhInfoBanner
import vn.dayhoc.app.core.designsystem.DhListRow
import vn.dayhoc.app.core.designsystem.DhOfflineBanner
import vn.dayhoc.app.core.designsystem.DhPrimaryButton
import vn.dayhoc.app.core.designsystem.DhSkeletonCard
import vn.dayhoc.app.core.designsystem.DhSkeletonRow
import vn.dayhoc.app.core.designsystem.DhSkeletonText
import vn.dayhoc.app.core.designsystem.TeachHubText
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens
import vn.dayhoc.app.teacher.onboarding.OnboardingStep

/** Nội dung dưới App Bar của [T03] Hôm nay. Các màn đích chưa có nên [onComingSoon] báo "đang hoàn thiện". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayContent(
    state: TodayUiState,
    zone: ZoneId,
    onRefresh: () -> Unit,
    onComingSoon: () -> Unit,
    onOpenSchedule: () -> Unit,
    onContinueSetup: (OnboardingStep) -> Unit,
) {
    val data = state.data
    // Giờ trôi qua thì thẻ buổi tự chuyển trạng thái; sang ngày mới thì tải lại.
    var now by remember { mutableStateOf(Instant.now()) }
    val latestRefresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(zone) {
        while (true) {
            delay(60_000)
            now = Instant.now()
            // shortcut: dùng múi giờ máy thay cho múi giờ tenant, đổi khi membership có timezone
            if (LocalDate.now(zone) != state.data?.date) latestRefresh()
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (state.offline) {
            DhOfflineBanner(stringResource(R.string.today_offline_title), stringResource(R.string.today_offline_body))
        }
        when {
            data == null && state.failed -> DhEmptyState(
                title = stringResource(R.string.today_load_failed),
                description = null,
                actionText = stringResource(R.string.today_retry),
                onAction = onRefresh,
            )
            data == null -> Skeleton()
            else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Dimens.spacingPage).plusBottom(DhBottomNavHeight),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
                ) {
                    item { Caption(formatDateLine(data.date)) }
                    val sessions = arrange(data.sessions, now)
                    if (sessions.isEmpty()) {
                        item { EmptyToday(data.next, onOpenSchedule) }
                    } else {
                        item { Section(stringResource(R.string.today_section_sessions, sessions.size)) }
                        items(sessions, key = { it.id }) { SessionCard(it, zone, onComingSoon) }
                    }
                    if (data.setupSteps.isNotEmpty()) item { SetupBanner(data.setupSteps, onContinueSetup) }
                    val overdue = data.overdue
                    if (overdue != null && overdue.count > 0) {
                        item { Section(stringResource(R.string.today_section_todo)) }
                        item {
                            DhListRow(
                                title = stringResource(R.string.today_overdue_title),
                                subtitle = stringResource(R.string.today_overdue_total, formatVnd(overdue.total)),
                                onClick = onComingSoon,
                                trailing = { DhChip(overdue.count.toString(), style = DhChipStyle.Counter) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun PaddingValues.plusBottom(extra: androidx.compose.ui.unit.Dp) =
    PaddingValues(start = calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr), top = calculateTopPadding(),
        end = calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr), bottom = calculateBottomPadding() + extra)

@Composable
private fun Caption(text: String) =
    Text(text, style = TeachHubText.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Section(text: String) =
    Text(text.uppercase(Locale.ROOT), style = TeachHubText.section, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Skeleton() {
    Column(Modifier.padding(Dimens.spacingPage), verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent)) {
        DhSkeletonText()
        repeat(3) { DhSkeletonCard() }
        repeat(3) { DhSkeletonRow() }
    }
}

@Composable
private fun SessionCard(s: TodaySession, zone: ZoneId, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val style = when (s.kind) {
        SessionKind.Teaching, SessionKind.Missed -> DhCardStyle.Emphasis
        SessionKind.Upcoming -> DhCardStyle.Default
        SessionKind.Taught, SessionKind.Cancelled -> DhCardStyle.Muted
    }
    val subtitle = buildList {
        add(formatTimeRange(s.start, s.end, zone))
        s.enrolled?.let { add(stringResource(R.string.today_students, it)) }
        s.location?.let { add(it) }
    }.joinToString(" · ")
    DhCard(style = style, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spacingControl)) {
            Column(Modifier.weight(1f)) {
                Text(s.className, style = TeachHubText.bodyBold, color = colors.onSurface, maxLines = 1)
                Text(subtitle, style = TeachHubText.caption, color = colors.onSurfaceVariant, maxLines = 1)
            }
            when (s.kind) {
                SessionKind.Teaching -> DhChip(stringResource(R.string.today_chip_teaching), style = DhChipStyle.Filled)
                SessionKind.Missed -> DhChip(stringResource(R.string.today_chip_missed), style = DhChipStyle.Filled)
                SessionKind.Upcoming -> DhChip(stringResource(R.string.today_chip_upcoming))
                SessionKind.Cancelled -> DhChip(stringResource(R.string.today_chip_cancelled))
                SessionKind.Taught -> DhChip(
                    if (s.attended != null && s.total != null) {
                        stringResource(R.string.today_chip_taught_count, s.attended, s.total)
                    } else {
                        stringResource(R.string.today_chip_taught)
                    },
                    style = DhChipStyle.Success,
                )
            }
        }
        if (style == DhCardStyle.Emphasis) DhPrimaryButton(stringResource(R.string.today_attendance), onClick)
    }
}

@Composable
private fun SetupBanner(steps: List<OnboardingStep>, onContinue: (OnboardingStep) -> Unit) {
    val addStudents = stringResource(R.string.today_setup_add_students)
    val setSchedule = stringResource(R.string.today_setup_set_schedule)
    val names = steps.joinToString(" · ") { if (it == OnboardingStep.AddStudents) addStudents else setSchedule }
    DhInfoBanner(
        title = stringResource(R.string.today_setup_title, steps.size),
        description = names,
        actionText = stringResource(R.string.today_setup_continue),
        onAction = { onContinue(steps.first()) },
    )
}

@Composable
private fun EmptyToday(next: NextSession?, onOpenSchedule: () -> Unit) {
    val description = if (next != null) {
        stringResource(R.string.today_empty_next, next.className, formatShortDay(next.day), next.time)
    } else {
        stringResource(R.string.today_empty_none)
    }
    DhEmptyState(stringResource(R.string.today_empty_title), description, stringResource(R.string.today_empty_action), onOpenSchedule)
}
