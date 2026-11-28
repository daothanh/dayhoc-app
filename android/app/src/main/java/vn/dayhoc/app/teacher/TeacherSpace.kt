package vn.dayhoc.app.teacher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import kotlinx.coroutines.launch
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhBottomNav
import vn.dayhoc.app.core.designsystem.DhBottomNavHeight
import vn.dayhoc.app.core.designsystem.DhNavItem
import vn.dayhoc.app.core.designsystem.DhPrimaryButton
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.apiCall
import vn.dayhoc.app.core.session.MembershipDto
import vn.dayhoc.app.core.session.MembershipStore
import vn.dayhoc.app.core.session.RoleSpaceScaffold
import vn.dayhoc.app.teacher.onboarding.OnboardingApi
import vn.dayhoc.app.teacher.onboarding.OnboardingStep
import vn.dayhoc.app.teacher.today.TodayAppBarActions
import vn.dayhoc.app.teacher.today.TodayContent
import vn.dayhoc.app.teacher.today.TodayViewModel

/**
 * Không gian giáo viên: 5 tab Hôm nay · Lịch · Lớp · Học phí · Thêm (Figma C02 `Teacher`).
 * Nội dung các tab làm ở NA-06, NA-08; tab Thêm có Đăng xuất.
 * Thiết lập lớp học là bắt buộc nên còn bước dở thì tự mở lại.
 */
@Composable
fun TeacherSpace(
    store: MembershipStore,
    onboardingApi: OnboardingApi,
    today: TodayViewModel,
    onOpenOnboarding: (OnboardingStep?) -> Unit,
    onSwitched: (MembershipDto) -> Unit,
    onAddRole: () -> Unit,
    onLogout: suspend () -> Unit,
) {
    LaunchedEffect(Unit) {
        // Lỗi mạng: vào màn chính, lần mở sau kiểm lại.
        val pending = try {
            apiCall { onboardingApi.get() }.current != null
        } catch (e: ApiError) {
            false
        }
        if (pending) onOpenOnboarding(null)
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var loggingOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val items = listOf(
        DhNavItem(stringResource(R.string.nav_today), R.drawable.ic_nav_home_2, R.drawable.ic_nav_home_2_bold),
        DhNavItem(stringResource(R.string.nav_schedule), R.drawable.ic_nav_calendar, R.drawable.ic_nav_calendar_bold),
        DhNavItem(
            stringResource(R.string.nav_classes),
            R.drawable.ic_nav_users_group_rounded,
            R.drawable.ic_nav_users_group_rounded_bold,
        ),
        DhNavItem(stringResource(R.string.nav_fees), R.drawable.ic_nav_wallet, R.drawable.ic_nav_wallet_bold),
        DhNavItem(stringResource(R.string.nav_more), R.drawable.ic_nav_widget, R.drawable.ic_nav_widget_bold),
    )

    val todayState by today.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today.refresh() }
    val snackbar = remember { SnackbarHostState() }
    val comingSoon = stringResource(R.string.coming_soon)
    val showComingSoon: () -> Unit = {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(comingSoon)
        }
    }

    RoleSpaceScaffold(
        store,
        onSwitched,
        onAddRole,
        title = if (tab == 0) stringResource(R.string.today_title) else null,
        actions = { if (tab == 0) TodayAppBarActions(todayState, showComingSoon) },
    ) {
        if (tab == 0) {
            TodayContent(
                state = todayState,
                zone = ZoneId.systemDefault(),
                onRefresh = today::refresh,
                onComingSoon = showComingSoon,
                onOpenSchedule = { tab = 1 },
                onContinueSetup = onOpenOnboarding,
            )
        } else Column(
            Modifier.fillMaxSize().padding(Dimens.spacingPage).padding(bottom = DhBottomNavHeight),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
        ) {
            Text(items[tab].label, style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.space_coming),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tab == items.lastIndex) {
                DhPrimaryButton(
                    text = stringResource(R.string.logout),
                    loading = loggingOut,
                    onClick = {
                        loggingOut = true
                        scope.launch { onLogout() }
                    },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = DhBottomNavHeight))
        DhBottomNav(items, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
    }
}
