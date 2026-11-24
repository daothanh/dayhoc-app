package vn.dayhoc.app.shell

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.dayhoc.app.R
import vn.dayhoc.app.core.auth.TokenStore
import vn.dayhoc.app.core.designsystem.DhBrandMark
import vn.dayhoc.app.core.designsystem.DhPrimaryButton
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.session.MembershipStore

/** Nơi splash mở tiếp. */
sealed interface SplashDest {
    data object Login : SplashDest
    data object RolePicker : SplashDest
    data class Space(val role: String) : SplashDest
}

/** Kiểm phiên rồi tải vai trò: có vai trò đang mở → không gian của nó, chưa có → "Bạn là…?". */
class SplashViewModel(private val tokens: TokenStore, private val memberships: MembershipStore) : ViewModel() {
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val _dest = MutableStateFlow<SplashDest?>(null)
    val dest: StateFlow<SplashDest?> = _dest.asStateFlow()

    init {
        start()
    }

    fun start() {
        _failed.value = false
        viewModelScope.launch {
            if (tokens.load() == null) {
                _dest.value = SplashDest.Login
                return@launch
            }
            try {
                val active = memberships.load().active
                _dest.value = active?.let { SplashDest.Space(it.role) } ?: SplashDest.RolePicker
            } catch (e: ApiError) {
                // shortcut: chưa cache vai trò nên mất mạng lúc mở app thì kẹt ở đây; làm cache ở NA-07 (ngoại tuyến).
                _failed.value = true
            }
        }
    }
}

/** [T00s] Splash (Figma B00a/B00b): nền lime, ký hiệu logo, tên app, khẩu hiệu; lỗi tải vai trò thì có nút thử lại. */
@Composable
fun SplashScreen(viewModel: SplashViewModel, onDone: (SplashDest) -> Unit) {
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val dest by viewModel.dest.collectAsStateWithLifecycle()
    LaunchedEffect(dest) { dest?.let(onDone) }

    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Surface(color = colors.primary, contentColor = colors.onPrimary, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.safeDrawingPadding().padding(Dimens.spacingSection), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.spacingControl),
            ) {
                DhBrandMark(size = 160.dp)
                Text(stringResource(R.string.app_name), style = type.headlineLarge)
                Text(
                    stringResource(R.string.login_slogan) + "\n" + stringResource(R.string.login_tagline),
                    style = type.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (failed) {
                    Text(
                        stringResource(R.string.splash_load_failed),
                        style = type.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Dimens.spacingSection),
                    )
                    // Nền lime: nút viền ink để tách khỏi nền.
                    OutlinedButton(
                        onClick = viewModel::start,
                        border = BorderStroke(2.dp, colors.onPrimary),
                        modifier = Modifier.padding(top = Dimens.spacingContent),
                    ) {
                        Text(stringResource(R.string.retry), color = colors.onPrimary)
                    }
                }
            }
        }
    }
}

/** Bản cài thấp hơn `minSupportedVersion` (Technical Spec 5.1): chặn mọi màn, chỉ mở cửa hàng. */
@Composable
fun UpdateRequiredScreen() {
    val context = LocalContext.current
    BackHandler { }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(Dimens.spacingSection),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingControl, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.update_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.update_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            DhPrimaryButton(
                text = stringResource(R.string.update_action),
                onClick = {
                    val id = context.packageName
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$id".toUri()))
                    } catch (e: ActivityNotFoundException) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$id".toUri()),
                        )
                    }
                },
                modifier = Modifier.padding(top = Dimens.spacingSection),
            )
        }
    }
}
