package vn.dayhoc.app.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhPrimaryButton
import vn.dayhoc.app.core.designsystem.DhSecondaryButton
import vn.dayhoc.app.core.designsystem.DhTextField
import vn.dayhoc.app.core.designsystem.TeachHubTokens
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.apiCall
import vn.dayhoc.app.core.session.CenterWaitlistRequest
import vn.dayhoc.app.core.session.MembershipDto
import vn.dayhoc.app.core.session.MembershipStore
import vn.dayhoc.app.core.session.Roles
import vn.dayhoc.app.core.session.SessionApi
import vn.dayhoc.app.core.session.roleDescRes
import vn.dayhoc.app.core.session.roleNameRes

data class RolePickerUiState(
    val selected: String? = null,
    val owned: Set<String> = emptySet(),
    val busy: Boolean = false,
    val failed: Boolean = false,
    /** Vai trò đã mở: màn điều hướng sang không gian của nó. */
    val opened: MembershipDto? = null,
)

/** [T01c] Vai trò đã có thì mở luôn; chưa có thì tạo (Giáo viên tạo không gian, US-0.10 AC2). */
class RolePickerViewModel(private val store: MembershipStore) : ViewModel() {
    private val _state = MutableStateFlow(RolePickerUiState(owned = store.state.value.items.map { it.role }.toSet()))
    val state: StateFlow<RolePickerUiState> = _state.asStateFlow()

    fun select(role: String) {
        if (_state.value.busy) return
        if (_state.value.selected != role) store.resetPendingAdd()
        _state.update { it.copy(selected = role) }
    }

    fun proceed() {
        val role = _state.value.selected ?: return
        if (_state.value.busy) return
        val owned = store.state.value.items.firstOrNull { it.role == role }
        _state.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            try {
                val opened = owned?.also { store.select(it) } ?: store.addRole(role)
                _state.update { it.copy(busy = false, opened = opened) }
            } catch (e: ApiError) {
                _state.update { it.copy(busy = false, failed = true) }
            }
        }
    }

    fun failureShown() = _state.update { it.copy(failed = false) }
}

/** [T01c] "Bạn là…?" (Figma B04, B05, B15). [onBack] khác `null` khi mở từ "Thêm vai trò". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RolePickerScreen(
    viewModel: RolePickerViewModel,
    onOpened: (MembershipDto) -> Unit,
    onCenterOwner: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failedText = stringResource(R.string.role_add_failed)
    LaunchedEffect(state.opened) { state.opened?.let(onOpened) }
    LaunchedEffect(state.failed) {
        if (state.failed) {
            snackbar.showSnackbar(failedText)
            viewModel.failureShown()
        }
    }

    Scaffold(
        topBar = { if (onBack != null) TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                DhPrimaryButton(
                    text = stringResource(R.string.role_continue),
                    loadingText = stringResource(R.string.loading),
                    loading = state.busy,
                    enabled = state.selected != null,
                    onClick = viewModel::proceed,
                    modifier = Modifier.navigationBarsPadding().padding(Dimens.spacingPage),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.spacingPage, vertical = Dimens.spacingSection),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
        ) {
            Text(stringResource(R.string.role_who_are_you), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.role_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Roles.ALL.forEach { role ->
                val selected = state.selected == role
                RoleCard(
                    title = stringResource(roleNameRes(role)),
                    caption = stringResource(roleDescRes(role)),
                    selected = selected,
                    tag = when {
                        selected -> stringResource(R.string.role_selected)
                        role in state.owned -> stringResource(R.string.role_owned)
                        else -> null
                    },
                    onClick = { viewModel.select(role) },
                )
            }
            RoleCard(
                title = stringResource(R.string.role_center_owner),
                caption = stringResource(R.string.role_center_owner_desc),
                comingSoon = stringResource(R.string.role_coming_soon),
                onClick = onCenterOwner,
            )
        }
    }
}

/** `Surfaces/Card` của T01c: viền 1.5, chọn thì viền ink 2.5 + tag "Đã chọn"; sắp ra mắt viền đứt amber. */
@Composable
private fun RoleCard(
    title: String,
    caption: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    tag: String? = null,
    comingSoon: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val warning = if (isSystemInDarkTheme()) TeachHubTokens.Dark.warningColor else TeachHubTokens.Light.warningColor
    Surface(
        shape = RoundedCornerShape(Dimens.cardRadius),
        color = colors.surface,
        border = when {
            comingSoon != null -> null
            selected -> BorderStroke(2.5.dp, colors.onSurface)
            else -> BorderStroke(1.5.dp, colors.outlineVariant)
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (comingSoon != null) Modifier.dashedBorder(warning, Dimens.cardRadius) else Modifier)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(Dimens.spacingContent), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(caption, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            when {
                tag != null -> Tag(tag, filled = selected)
                comingSoon != null -> Tag(comingSoon, filled = false, dashed = true)
            }
        }
    }
}

/** `Display/Tag` 24px: Filled ink hoặc Outline. */
@Composable
private fun Tag(label: String, filled: Boolean, dashed: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (filled) colors.onSurface else colors.surface,
        border = if (filled || dashed) null else BorderStroke(1.5.dp, colors.outlineVariant),
        modifier = Modifier.height(24.dp).then(
            if (dashed) Modifier.dashedBorder(colors.outlineVariant, Dimens.pillRadius) else Modifier,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (filled) colors.inverseOnSurface else colors.onSurface,
            modifier = Modifier.padding(horizontal = Dimens.spacingControl, vertical = 3.dp),
        )
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp) = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
    )
}

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }
}

// ───────────────────────────── [T01d] Đăng ký sớm Chủ trung tâm ─────────────────────────────

data class WaitlistUiState(
    val centerName: String = "",
    val teacherCount: String = "",
    val showErrors: Boolean = false,
    val busy: Boolean = false,
    val sent: Boolean = false,
    val failure: ApiError? = null,
) {
    val nameValid get() = centerName.trim().length in 1..120
    val count get() = teacherCount.trim().toIntOrNull()?.takeIf { it in 1..1000 }
}

class CenterWaitlistViewModel(private val api: SessionApi) : ViewModel() {
    private val _state = MutableStateFlow(WaitlistUiState())
    val state: StateFlow<WaitlistUiState> = _state.asStateFlow()

    /** Khóa theo nội dung: gửi lại sau lỗi mạng không ghi hai lần; sửa ô thì là lệnh mới. */
    private var key: Pair<CenterWaitlistRequest, String>? = null

    fun onName(v: String) = _state.update { it.copy(centerName = v) }

    fun onCount(v: String) = _state.update { it.copy(teacherCount = v.filter(Char::isDigit).take(4)) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        val count = s.count
        if (!s.nameValid || count == null) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        val body = CenterWaitlistRequest(s.centerName.trim(), count)
        val k = key?.takeIf { it.first == body }?.second ?: UUID.randomUUID().toString()
        key = body to k
        _state.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            try {
                apiCall { api.joinCenterWaitlist(k, body) }
                _state.update { it.copy(busy = false, sent = true) }
            } catch (e: ApiError) {
                if (!e.retryable) key = null
                _state.update { it.copy(busy = false, failure = e) }
            }
        }
    }

    fun failureShown() = _state.update { it.copy(failure = null) }
}

/** [T01d] Gửi xong quay về T01c để chọn Giáo viên. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CenterWaitlistScreen(viewModel: CenterWaitlistViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failureText = state.failure?.let { waitlistErrorText(it) }
    LaunchedEffect(state.failure) {
        if (failureText != null) {
            snackbar.showSnackbar(failureText)
            viewModel.failureShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.waitlist_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.spacingPage),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
        ) {
            val trial = @Composable {
                DhSecondaryButton(
                    text = stringResource(R.string.waitlist_trial_action),
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.sent) {
                Text(stringResource(R.string.waitlist_sent), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.waitlist_sent_body), style = MaterialTheme.typography.bodyMedium)
                trial()
                return@Column
            }
            Text(stringResource(R.string.waitlist_intro), style = MaterialTheme.typography.bodyMedium)
            val nameError = state.showErrors && !state.nameValid
            DhTextField(
                value = state.centerName,
                onValueChange = viewModel::onName,
                label = stringResource(R.string.waitlist_center_name) + " *",
                isError = nameError,
                supportingText = if (nameError) stringResource(R.string.waitlist_center_name_required) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            val countError = state.showErrors && state.count == null
            DhTextField(
                value = state.teacherCount,
                onValueChange = viewModel::onCount,
                label = stringResource(R.string.waitlist_teacher_count) + " *",
                isError = countError,
                supportingText = if (countError) stringResource(R.string.waitlist_teacher_count_invalid) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Surface(
                shape = RoundedCornerShape(Dimens.cardRadius),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.waitlist_trial_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(Dimens.spacingContent),
                )
            }
            DhPrimaryButton(
                text = stringResource(R.string.waitlist_submit),
                loading = state.busy,
                onClick = viewModel::submit,
            )
            trial()
        }
    }
}

@Composable
private fun waitlistErrorText(e: ApiError): String = stringResource(
    when {
        e == ApiError.Network || e == ApiError.Timeout -> R.string.error_network
        e is ApiError.Http && e.httpStatus == 409 -> R.string.waitlist_error_busy
        e is ApiError.Http && e.httpStatus == 422 -> R.string.waitlist_error_validation
        else -> R.string.waitlist_error_generic
    },
)
