package vn.dayhoc.app.teacher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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

/**
 * Không gian giáo viên: 5 tab Hôm nay · Lịch · Lớp · Học phí · Thêm (Figma C02 `Teacher`).
 * Nội dung các tab làm ở NA-06, NA-08; tab Thêm có Đăng xuất.
 * Còn bước onboarding dở thì mở onboarding, trừ khi giáo viên vừa thoát nó trong lần mở app này.
 */
@Composable
fun TeacherSpace(
    store: MembershipStore,
    onboardingApi: OnboardingApi,
    onboardingDismissed: Boolean,
    onOpenOnboarding: () -> Unit,
    onSwitched: (MembershipDto) -> Unit,
    onAddRole: () -> Unit,
    onLogout: suspend () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (onboardingDismissed) return@LaunchedEffect
        // Lỗi mạng: vào màn chính, lần mở sau kiểm lại.
        val pending = try {
            apiCall { onboardingApi.get() }.current != null
        } catch (e: ApiError) {
            false
        }
        if (pending) onOpenOnboarding()
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

    RoleSpaceScaffold(store, onSwitched, onAddRole) {
        Column(
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
        DhBottomNav(items, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
    }
}
