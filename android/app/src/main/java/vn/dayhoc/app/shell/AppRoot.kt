package vn.dayhoc.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import vn.dayhoc.app.AppContainer
import vn.dayhoc.app.BuildConfig
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhPrimaryButton
import vn.dayhoc.app.core.designsystem.DhTextButton
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.apiCall
import vn.dayhoc.app.core.session.MembershipStore
import vn.dayhoc.app.core.session.RoleSpaceScaffold
import vn.dayhoc.app.core.session.Roles
import vn.dayhoc.app.core.session.isVersionBelow
import java.time.ZoneId
import vn.dayhoc.app.teacher.TeacherSpace
import vn.dayhoc.app.teacher.attendance.AttendanceScreen
import vn.dayhoc.app.teacher.attendance.AttendanceViewModel
import vn.dayhoc.app.teacher.attendance.repeatLabel
import vn.dayhoc.app.teacher.onboarding.OnboardingScreen
import vn.dayhoc.app.teacher.onboarding.OnboardingStep
import vn.dayhoc.app.teacher.today.TodayViewModel
import vn.dayhoc.app.teacher.onboarding.OnboardingViewModel

@Serializable
data object SplashRoute

@Serializable
data object LoginRoute

/** [adding] = mở từ "Thêm vai trò" trong T00: có nút quay lại. */
@Serializable
data class RolePickerRoute(val adding: Boolean = false)

@Serializable
data object CenterWaitlistRoute

@Serializable
data object TeacherRoute

/** [endsAt] = giờ kết thúc buổi (ISO) từ thẻ ở T03, chỉ để hiện "17:30–19:00" ở tiêu đề; API T04 không trả giờ kết thúc. */
@Serializable
data class AttendanceRoute(val sessionId: String, val endsAt: String)

/** [startStep] = mở lại đúng bước đã bỏ qua (banner ở T03); `null` = bước đang dở theo server. */
@Serializable
data class OnboardingRoute(val startStep: String? = null)

@Serializable
data object ParentRoute

@Serializable
data object StudentRoute

private fun spaceRoute(role: String): Any = when (role) {
    Roles.TEACHER -> TeacherRoute
    Roles.PARENT -> ParentRoute
    else -> StudentRoute
}

/**
 * Điều hướng gốc: Splash → Đăng nhập | "Bạn là…?" | không gian vai trò đang mở.
 * Hết phiên ở bất kỳ đâu → Đăng nhập. Bản cài thấp hơn mức tối thiểu → chặn bằng màn cập nhật.
 */
@Composable
fun AppRoot(container: AppContainer) {
    val nav = rememberNavController()
    var unsynced by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    // Không chờ khi khởi động, lỗi thì bỏ qua: app phải mở được khi mất mạng (NFR-17); lần mở sau kiểm lại.
    var updateRequired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val min = runCatching { apiCall { container.sessionApi.appConfig() }.minSupportedVersion.android }.getOrNull()
        if (min != null) updateRequired = isVersionBelow(BuildConfig.VERSION_NAME, min)
    }

    val sessionExpired by container.sessionExpired.collectAsState()
    LaunchedEffect(sessionExpired) {
        if (!sessionExpired) return@LaunchedEffect
        container.forgetSession()
        // Đang ở Đăng nhập thì giữ nguyên, không mất email đang nhập.
        if (nav.currentDestination?.hasRoute<LoginRoute>() != true) nav.resetTo(LoginRoute)
        container.sessionExpired.value = false
    }

    if (updateRequired) {
        UpdateRequiredScreen()
        return
    }

    val forceLogout: suspend () -> Unit = {
        container.logout()
        nav.resetTo(LoginRoute)
    }
    // Đăng xuất xóa điểm danh chưa gửi trên máy này: còn lô chờ/lỗi thì hỏi trước. Hết phiên không qua đây (giữ lô).
    val logout: suspend () -> Unit = {
        val n = container.unsyncedSessionCount()
        if (n > 0) unsynced = n else forceLogout()
    }

    NavHost(navController = nav, startDestination = SplashRoute) {
        composable<SplashRoute> {
            val vm = viewModel { SplashViewModel(container.tokenStore, container.membershipStore) }
            SplashScreen(vm) { dest ->
                nav.resetTo(
                    when (dest) {
                        SplashDest.Login -> LoginRoute
                        SplashDest.RolePicker -> RolePickerRoute()
                        is SplashDest.Space -> spaceRoute(dest.role)
                    },
                )
            }
        }
        composable<LoginRoute> {
            val vm = viewModel { LoginViewModel(container.authRepository, createSavedStateHandle()) }
            val appleCode by container.appleCallbackCode.collectAsState()
            LaunchedEffect(appleCode) {
                val code = appleCode ?: return@LaunchedEffect
                container.appleCallbackCode.value = null
                vm.onAppleCallback(code)
            }
            // Đăng nhập xong: splash tải vai trò rồi mở đúng nơi.
            LoginScreen(vm, onLoggedIn = { nav.resetTo(SplashRoute) })
        }
        composable<RolePickerRoute> { entry ->
            val adding = entry.toRoute<RolePickerRoute>().adding
            val vm = viewModel { RolePickerViewModel(container.membershipStore) }
            RolePickerScreen(
                viewModel = vm,
                onOpened = { m -> nav.resetTo(spaceRoute(m.role)) },
                onCenterOwner = { nav.navigate(CenterWaitlistRoute) },
                onBack = if (adding) ({ nav.popBackStack() }) else null,
            )
        }
        composable<CenterWaitlistRoute> {
            val vm = viewModel { CenterWaitlistViewModel(container.sessionApi) }
            CenterWaitlistScreen(vm, onBack = { nav.popBackStack() })
        }
        composable<TeacherRoute> {
            // Gắn chủ dữ liệu ngoại tuyến (người dùng + tenant) trước khi vào không gian giáo viên, và gắn lại khi đổi tenant.
            val membership by container.membershipStore.state.collectAsState()
            val tenantId = membership.active?.tenantId
            val bound by produceState(false, tenantId) {
                value = false
                container.bindAttendanceOwner(tenantId)
                value = true
            }
            if (!bound) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                return@composable
            }
            TeacherSpace(
                store = container.membershipStore,
                onboardingApi = container.onboardingApi,
                today = viewModel { TodayViewModel(container.todayApi, outboxSessions = container.outboxSessions) },
                attendanceNotice = container.attendanceNotice,
                syncEvents = container.syncEngine.events,
                loadRepeat = { classId ->
                    try {
                        repeatLabel(apiCall { container.attendanceApi.classDetail(classId) }.scheduleRules.map { it.weekday })
                    } catch (e: ApiError) {
                        null
                    }
                },
                onOpenAttendance = { id, endsAt -> nav.navigate(AttendanceRoute(id, endsAt)) { launchSingleTop = true } },
                onOpenOnboarding = { step -> nav.navigate(OnboardingRoute(step?.wire)) { launchSingleTop = true } },
                onSwitched = { m -> nav.resetTo(spaceRoute(m.role)) },
                onAddRole = { nav.navigate(RolePickerRoute(adding = true)) },
                onLogout = logout,
            )
        }
        composable<AttendanceRoute> { entry ->
            val route = entry.toRoute<AttendanceRoute>()
            val vm = viewModel {
                AttendanceViewModel(
                    sessionId = route.sessionId,
                    api = container.attendanceApi,
                    outbox = container.localStore,
                    roster = container.localStore,
                    device = container.localStore,
                    engine = container.syncEngine,
                    connectivity = container.connectivity,
                    ownerProvider = container::currentOwner,
                    notify = { container.attendanceNotice.value = it },
                    onEnqueued = container::onOutboxEnqueued,
                )
            }
            AttendanceScreen(vm, route.endsAt, ZoneId.systemDefault(), onClose = { nav.popBackStack() })
        }
        composable<OnboardingRoute> { entry ->
            val start = OnboardingStep.fromWire(entry.toRoute<OnboardingRoute>().startStep)
            val vm = viewModel { OnboardingViewModel(container.onboardingApi, container.pendingFeeStore, startStep = start) }
            OnboardingScreen(viewModel = vm, onFinished = { nav.popBackStack() })
        }
        composable<ParentRoute> {
            PlaceholderSpace(container.membershipStore, nav, logout)
        }
        composable<StudentRoute> {
            PlaceholderSpace(container.membershipStore, nav, logout)
        }
    }

    unsynced?.let { n ->
        AlertDialog(
            onDismissRequest = { unsynced = null },
            title = { Text(stringResource(R.string.logout_unsynced_title, n)) },
            text = { Text(stringResource(R.string.logout_unsynced_body)) },
            dismissButton = { DhTextButton(stringResource(R.string.logout_unsynced_stay), { unsynced = null }) },
            confirmButton = {
                DhTextButton(stringResource(R.string.logout_unsynced_confirm), {
                    unsynced = null
                    scope.launch { forceLogout() }
                })
            },
        )
    }
}

/**
 * Không gian phụ huynh, học sinh ở trạng thái chưa có lời mời (US-0.10 AC3).
 * shortcut: chỉ có chip vai trò và đăng xuất; màn thật và bottom nav `Parent`/`Student` làm ở NA-11, NA-12.
 */
@Composable
private fun PlaceholderSpace(store: MembershipStore, nav: NavHostController, logout: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    RoleSpaceScaffold(
        store = store,
        onSwitched = { m -> nav.resetTo(spaceRoute(m.role)) },
        onAddRole = { nav.navigate(RolePickerRoute(adding = true)) },
    ) {
        Column(
            Modifier.fillMaxSize().padding(Dimens.spacingPage),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
        ) {
            Text(stringResource(R.string.space_no_invite), style = MaterialTheme.typography.bodyLarge)
            DhPrimaryButton(
                text = stringResource(R.string.logout),
                loading = loggingOut,
                onClick = {
                    loggingOut = true
                    scope.launch {
                        logout()
                        loggingOut = false
                    }
                },
            )
        }
    }
}

private fun NavHostController.resetTo(route: Any) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
}
