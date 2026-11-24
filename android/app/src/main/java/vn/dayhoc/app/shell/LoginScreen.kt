package vn.dayhoc.app.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.DhAppleButton
import vn.dayhoc.app.core.designsystem.DhBrandLogo
import vn.dayhoc.app.core.designsystem.DhDivider
import vn.dayhoc.app.core.designsystem.DhGoogleButton
import vn.dayhoc.app.core.designsystem.DhHeroClasses
import vn.dayhoc.app.core.designsystem.DhOtpCell
import vn.dayhoc.app.core.designsystem.DhOtpCellState
import vn.dayhoc.app.core.designsystem.DhPrimaryButton
import vn.dayhoc.app.core.designsystem.DhTextField
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

// Figma B01: khoảng 18 giữa hình minh họa và khối đăng nhập (không có token tương ứng).
private val HeroActionGap = 18.dp

// Trang pháp lý của DayHoc (teachhub-site). Xem Figma B01, dòng điều khoản.
private const val TermsUrl = "https://dayhoc.app/terms"
private const val PrivacyUrl = "https://dayhoc.app/privacy-policy"

/**
 * [T01] Chào & Đăng nhập · [T01a] Nhập mã OTP (Figma B01, B01a).
 *
 * Nút Google/Apple đã dựng theo thiết kế nhưng chưa nối SDK: bấm vào sẽ báo dùng email.
 * Luồng SSO thật làm ở task riêng.
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel, onLoggedIn: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ssoSoon = stringResource(R.string.login_sso_soon)
    val onSocialSignIn: () -> Unit = { scope.launch { snackbar.showSnackbar(ssoSoon) } }

    LaunchedEffect(state.loggedIn) { if (state.loggedIn) onLoggedIn() }

    val toastFailure = state.failure?.takeUnless { it is LoginFailure.OtpInvalid }
    val toastMessage = toastFailure?.let { failureText(it) }
    LaunchedEffect(toastFailure) {
        if (toastMessage != null) {
            snackbar.showSnackbar(toastMessage)
            viewModel.failureShown()
        }
    }

    BackHandler(enabled = state.sentTo != null && !state.verifying) { viewModel.changeEmail() }

    if (state.sentTo == null) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.spacingPage),
                verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
            ) {
                EmailStep(state, viewModel, onSocialSignIn)
            }
        }
    } else {
        OtpStep(state, viewModel, snackbar)
    }
}

/**
 * Bước nhập email (Figma B01): Header logo + khẩu hiệu, hình minh họa, hai nút SSO,
 * đường phân cách, ô email, nút gửi mã và dòng điều khoản.
 */
@Composable
private fun EmailStep(
    state: LoginUiState,
    viewModel: LoginViewModel,
    onSocialSignIn: () -> Unit,
) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingControl)) {
            DhBrandLogo()
            Column(
                modifier = Modifier.padding(top = Dimens.spacingControl),
                verticalArrangement = Arrangement.spacedBy(Dimens.spacingInline),
            ) {
                Text(
                    stringResource(R.string.login_slogan),
                    style = type.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.login_tagline),
                    style = type.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        DhHeroClasses()

        Spacer(Modifier.height(HeroActionGap))

        DhAppleButton(
            text = stringResource(R.string.login_continue_apple),
            onClick = onSocialSignIn,
        )
        DhGoogleButton(
            text = stringResource(R.string.login_continue_google),
            onClick = onSocialSignIn,
        )
        DhDivider(stringResource(R.string.login_or_email))
        DhTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.login_email),
            placeholder = stringResource(R.string.login_email_hint),
            supportingText = stringResource(
                if (state.showEmailError) R.string.login_email_invalid else R.string.login_email_helper,
            ),
            isError = state.showEmailError,
            enabled = !state.sending,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { viewModel.sendCode() }),
            modifier = Modifier.fillMaxWidth(),
        )
        DhPrimaryButton(
            text = stringResource(R.string.login_send_otp),
            loadingText = stringResource(R.string.login_sending),
            loading = state.sending,
            enabled = state.emailValid,
            onClick = viewModel::sendCode,
        )
        TermsNotice()
    }
}

/**
 * Dòng điều khoản (Figma B01): "Điều khoản sử dụng" và "Chính sách dữ liệu" gạch chân, tô màu link
 * (lime800 ở Light, lime500 ở Dark) và bấm mở trang pháp lý tương ứng.
 *
 * Hit-test tự làm theo vị trí ký tự: LinkAnnotation của Compose trong bản này trả vùng bấm sai
 * (bấm chữ thường vẫn mở link, còn nhãn ở dòng dưới không bấm được), nên chỉ mở URL khi điểm bấm
 * rơi đúng khoảng offset của nhãn.
 */
@Composable
private fun TermsNotice() {
    val colors = MaterialTheme.colorScheme
    val sentence = stringResource(R.string.login_terms)
    val termsLabel = stringResource(R.string.login_terms_link_terms)
    val privacyLabel = stringResource(R.string.login_terms_link_privacy)
    val uriHandler = LocalUriHandler.current

    val termsStart = sentence.indexOf(termsLabel)
    val termsEnd = if (termsStart >= 0) termsStart + termsLabel.length else -1
    val privacyStart = sentence.indexOf(privacyLabel)
    val privacyEnd = if (privacyStart >= 0) privacyStart + privacyLabel.length else -1

    val linkStyle = SpanStyle(color = colors.secondary, textDecoration = TextDecoration.Underline)
    val text = remember(sentence, termsStart, termsEnd, privacyStart, privacyEnd, linkStyle) {
        buildAnnotatedString {
            append(sentence)
            if (termsStart >= 0) addStyle(linkStyle, termsStart, termsEnd)
            if (privacyStart >= 0) addStyle(linkStyle, privacyStart, privacyEnd)
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(termsStart, termsEnd, privacyStart, privacyEnd) {
            detectTapGestures { position ->
                val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                when {
                    termsStart >= 0 && offset >= termsStart && offset < termsEnd ->
                        uriHandler.openUri(TermsUrl)
                    privacyStart >= 0 && offset >= privacyStart && offset < privacyEnd ->
                        uriHandler.openUri(PrivacyUrl)
                }
            }
        },
    )
}

/**
 * [T01a] Nhập mã OTP (Figma B02): App Bar Detail có nút lùi và tiêu đề H2, tiêu đề H1, dòng
 * "Mã 6 số đã gửi tới …" kèm "Đổi email", hàng 6 ô mã 44×52 căn giữa, caption đếm ngược và nút
 * "Xác nhận" ghim đáy theo `Actions/Bottom Action Bar`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtpStep(state: LoginUiState, viewModel: LoginViewModel, snackbar: SnackbarHostState) {
    val colors = MaterialTheme.colorScheme
    val locked = state.lockedFor > 0
    val invalid = state.failure as? LoginFailure.OtpInvalid
    val enabled = !locked && !state.verifying

    Scaffold(
        topBar = { OtpAppBar(onBack = viewModel::changeEmail) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            OtpBottomBar(
                locked = locked,
                verifying = state.verifying,
                ready = state.code.length == LoginViewModel.CODE_LENGTH,
                onConfirm = viewModel::verify,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Dimens.spacingPage,
                    end = Dimens.spacingPage,
                    top = Dimens.spacingPageLarge,
                    bottom = Dimens.spacingPage,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingPage),
        ) {
            Text(stringResource(R.string.otp_heading), style = FigmaH1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.otp_sent_to, state.sentTo.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(
                    onClick = viewModel::changeEmail,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = Dimens.spacingControl),
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Text(
                        stringResource(R.string.login_change_email),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = colors.secondary,
                    )
                }
            }
            OtpCells(
                code = state.code,
                locked = locked,
                error = invalid != null,
                enabled = enabled,
                onValueChange = viewModel::onCodeChange,
                onDone = viewModel::verify,
            )
            if (locked) {
                LockedBanner()
            } else if (invalid != null) {
                Text(
                    failureText(invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ResendNotice(state, locked, viewModel)
        }
    }
}

// Figma DayHoc/H1: 20/28 SemiBold, giãn chữ -0.015em. M3 không có cỡ 20 (headlineMedium = 28) nên đặt tường minh.
private val FigmaH1 = TextStyle(
    fontSize = 20.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.015).em,
)

/** App Bar Detail (Figma `Navigation/App Bar`, node 43:5193): cao 56, nền surface, kẻ dưới 1.5px. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtpAppBar(onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.otp_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.01).em,
                    ),
                    color = colors.onSurface,
                )
            },
            navigationIcon = { BackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
        )
        HorizontalDivider(thickness = 1.5.dp, color = colors.outlineVariant)
    }
}

/** `Actions/Bottom Action Bar` (Figma 43:5237): nền surface, kẻ trên 1.5px, nút Primary "Xác nhận". */
@Composable
private fun OtpBottomBar(locked: Boolean, verifying: Boolean, ready: Boolean, onConfirm: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface) {
        // Chừa chỗ cho thanh điều hướng và bàn phím: chọn inset lớn hơn để không cộng dồn hai lần.
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
            HorizontalDivider(thickness = 1.5.dp, color = colors.outlineVariant)
            DhPrimaryButton(
                text = stringResource(R.string.otp_confirm),
                loadingText = stringResource(R.string.otp_verifying),
                loading = verifying,
                enabled = ready && !locked,
                onClick = onConfirm,
                modifier = Modifier.padding(
                    start = Dimens.spacingPage,
                    end = Dimens.spacingPage,
                    top = Dimens.spacingContent,
                    bottom = Dimens.spacingPage,
                ),
            )
        }
    }
}

/**
 * Hàng 6 ô mã (Figma B02): một [BasicTextField] trong suốt phủ lên hàng ô để nhận gõ và dán mã,
 * còn các ô chỉ vẽ ký tự nên không cần ô nhập riêng.
 */
@Composable
private fun OtpCells(
    code: String,
    locked: Boolean,
    error: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    // Figma B02: ô đầu ở trạng thái Focus kèm con trỏ, nên xin focus và mở bàn phím ngay khi vào màn.
    // Chờ hết khung hình đầu để cây focus sẵn sàng, nếu không yêu cầu focus sẽ bị bỏ qua.
    LaunchedEffect(enabled) {
        if (enabled) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingControl)) {
            repeat(LoginViewModel.CODE_LENGTH) { index ->
                val char = code.getOrNull(index)
                val cellState = when {
                    locked -> DhOtpCellState.Locked
                    error -> DhOtpCellState.Error
                    char != null -> DhOtpCellState.Filled
                    focused && index == code.length -> DhOtpCellState.Focus
                    else -> DhOtpCellState.Empty
                }
                DhOtpCell(char = char, state = cellState)
            }
        }
        // Ô nhập trong suốt phủ lên hàng ô để nhận gõ và dán mã; bản thân nó không vẽ gì.
        BasicTextField(
            value = code,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .matchParentSize()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
        )
    }
}

/** Caption đếm ngược "Gửi lại mã sau 4:59"; hết giờ đổi thành Text button "Gửi lại mã". */
@Composable
private fun ResendNotice(state: LoginUiState, locked: Boolean, viewModel: LoginViewModel) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            locked -> Text(
                stringResource(R.string.otp_retry_in, mmss(state.lockedFor)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            state.resendIn > 0 -> Text(
                stringResource(R.string.otp_resend_in, mmss(state.resendIn)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            else -> TextButton(onClick = viewModel::sendCode, enabled = !state.sending) {
                Text(stringResource(R.string.login_resend), color = colors.secondary)
            }
        }
    }
}

/** Banner lỗi khi bị tạm khóa (Figma B24). */
@Composable
private fun LockedBanner() {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(Dimens.cardRadius),
        color = colors.surface,
        border = BorderStroke(Dimens.focusRingWidth, colors.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Dimens.spacingContent), verticalArrangement = Arrangement.spacedBy(Dimens.spacingInline)) {
            Text(
                stringResource(R.string.otp_locked_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.otp_locked_body),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun failureText(failure: LoginFailure): String = when (failure) {
    is LoginFailure.OtpInvalid -> failure.attemptsLeft
        ?.let { stringResource(R.string.otp_invalid_attempts, it) }
        ?: stringResource(R.string.otp_invalid)
    LoginFailure.RateLimited -> stringResource(R.string.login_rate_limited)
    LoginFailure.Unavailable -> stringResource(R.string.login_unavailable)
    LoginFailure.Network -> stringResource(R.string.error_network)
    LoginFailure.Generic -> stringResource(R.string.login_failed)
}

private fun mmss(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
