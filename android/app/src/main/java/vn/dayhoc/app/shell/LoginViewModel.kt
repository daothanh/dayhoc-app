package vn.dayhoc.app.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.dayhoc.app.core.auth.AuthRepository
import vn.dayhoc.app.core.network.ApiError

sealed interface LoginFailure {
    /** Sai mã: hiện tại ô nhập, không qua snackbar. */
    data class OtpInvalid(val attemptsLeft: Int?) : LoginFailure
    data object RateLimited : LoginFailure
    data object Unavailable : LoginFailure
    data object Network : LoginFailure
    data object Generic : LoginFailure
    data object EmailRequiresOtp : LoginFailure
    data object GoogleFailed : LoginFailure
    data object AppleFailed : LoginFailure
    /** Server chưa bật Apple cho Android (404). */
    data object AppleUnsupported : LoginFailure
}

data class LoginUiState(
    val email: String = "",
    val showEmailError: Boolean = false,
    /** Email đã gửi mã; `null` = đang ở bước nhập email (T01), khác `null` = bước nhập mã (T01a). */
    val sentTo: String? = null,
    val code: String = "",
    val sending: Boolean = false,
    val verifying: Boolean = false,
    val googleSigningIn: Boolean = false,
    val appleSigningIn: Boolean = false,
    /** URL trang Apple chờ màn hình mở trong Custom Tab; màn hình gọi [LoginViewModel.appleBrowserOpened] sau khi mở. */
    val appleAuthUrl: String? = null,
    val resendIn: Int = 0,
    val lockedFor: Int = 0,
    val failure: LoginFailure? = null,
    val loggedIn: Boolean = false,
) {
    val emailValid: Boolean get() = EMAIL_PATTERN.matches(email.trim().lowercase())

    private companion object {
        val EMAIL_PATTERN = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    }
}

/** [T01] Đăng nhập + [T01a] Nhập mã OTP. */
class LoginViewModel(
    private val auth: AuthRepository,
    // Giữ code_verifier qua lúc hệ thống tắt app khi người dùng đang ở trang Apple.
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var resendJob: Job? = null
    private var lockJob: Job? = null
    private var appleReturnJob: Job? = null

    fun onEmailChange(email: String) = _state.update { it.copy(email = email, showEmailError = false) }

    /** Gửi mã lần đầu và gửi lại; bị chặn khi còn đếm ngược. */
    fun sendCode() {
        val s = _state.value
        if (s.sending || s.googleSigningIn || s.appleSigningIn || s.loggedIn || s.resendIn > 0) return
        if (!s.emailValid) {
            _state.update { it.copy(showEmailError = true) }
            return
        }
        val email = s.email.trim().lowercase()
        _state.update { it.copy(sending = true, failure = null) }
        viewModelScope.launch {
            try {
                auth.sendOtp(email)
                _state.update { it.copy(sentTo = email, code = "") }
                startResendCountdown(RESEND_AFTER_SEC)
            } catch (e: ApiError) {
                _state.update { it.copy(failure = e.toFailure()) }
                // Server bảo chờ bao lâu thì khóa nút gửi lại đúng bấy lâu.
                if (e is ApiError.Http && (e.code == ApiError.RATE_LIMITED || e.httpStatus == 429)) {
                    e.retryAfterSec?.let(::startResendCountdown)
                }
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    fun onCodeChange(input: String) {
        val code = input.filter(Char::isDigit).take(CODE_LENGTH)
        _state.update { it.copy(code = code, failure = it.failure.takeUnless { f -> f is LoginFailure.OtpInvalid }) }
        if (code.length == CODE_LENGTH) verify()
    }

    fun verify() {
        val s = _state.value
        val email = s.sentTo ?: return
        if (s.code.length != CODE_LENGTH || s.lockedFor > 0 || s.verifying) return
        _state.update { it.copy(verifying = true, failure = null) }
        viewModelScope.launch {
            try {
                auth.login(email, s.code)
                _state.update { it.copy(loggedIn = true) }
            } catch (e: ApiError) {
                onLoginFailed(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lưu token lỗi (Keystore/đĩa): báo lỗi chung, không crash.
                _state.update { it.copy(failure = LoginFailure.Generic) }
            } finally {
                _state.update { it.copy(verifying = false) }
            }
        }
    }

    fun startGoogleSignIn(): Boolean {
        val s = _state.value
        if (s.googleSigningIn || s.appleSigningIn || s.sending || s.verifying || s.sentTo != null || s.loggedIn) return false
        _state.update { it.copy(googleSigningIn = true, failure = null) }
        return true
    }

    fun finishGooglePicker(failed: Boolean) {
        _state.update { it.copy(googleSigningIn = false, failure = if (failed) LoginFailure.GoogleFailed else null) }
    }

    fun signInWithGoogle(idToken: String, email: String?) {
        viewModelScope.launch {
            try {
                auth.loginWithGoogle(idToken)
                _state.update { it.copy(loggedIn = true) }
            } catch (e: ApiError) {
                if (e is ApiError.Http && e.httpStatus == 401 && e.code == ApiError.UNAUTHENTICATED &&
                    e.detailString("reason") == "EMAIL_REQUIRES_OTP"
                ) {
                    _state.update {
                        it.copy(email = email ?: it.email, showEmailError = false, failure = LoginFailure.EmailRequiresOtp)
                    }
                } else {
                    val failure = e.toFailure()
                    _state.update { it.copy(failure = if (failure == LoginFailure.Generic) LoginFailure.GoogleFailed else failure) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(failure = LoginFailure.GoogleFailed) }
            } finally {
                _state.update { it.copy(googleSigningIn = false) }
            }
        }
    }

    /** Xin URL trang Apple (S256); màn hình mở URL đó trong Custom Tab khi [LoginUiState.appleAuthUrl] khác `null`. */
    fun startAppleSignIn() {
        val s = _state.value
        if (s.appleSigningIn || s.googleSigningIn || s.sending || s.verifying || s.sentTo != null || s.loggedIn) return
        _state.update { it.copy(appleSigningIn = true, failure = null) }
        val verifier = newCodeVerifier()
        saved[KEY_APPLE_VERIFIER] = verifier
        viewModelScope.launch {
            try {
                val url = auth.startAppleAndroid(codeChallenge(verifier))
                _state.update { it.copy(appleAuthUrl = url) }
            } catch (e: ApiError) {
                saved.remove<String>(KEY_APPLE_VERIFIER)
                _state.update { it.copy(appleSigningIn = false, failure = e.toAppleFailure()) }
            }
        }
    }

    fun appleBrowserOpened() {
        _state.update { it.copy(appleAuthUrl = null) }
        saved[KEY_APPLE_IN_BROWSER] = true
    }

    /** Không mở được trình duyệt: bỏ lượt này. */
    fun appleBrowserFailed() {
        saved.remove<String>(KEY_APPLE_VERIFIER)
        _state.update { it.copy(appleAuthUrl = null, appleSigningIn = false, failure = LoginFailure.AppleFailed) }
    }

    /**
     * Màn hình quay lại sau Custom Tab. Có kết quả thì deep link tới trước đó (activity singleTask nhận onNewIntent
     * trước onResume); chờ thêm một nhịp rồi coi như người dùng đóng tab, không báo lỗi.
     */
    fun onReturnedFromBrowser() {
        if (saved.get<Boolean>(KEY_APPLE_IN_BROWSER) != true) return
        appleReturnJob?.cancel()
        appleReturnJob = viewModelScope.launch {
            delay(APPLE_RETURN_GRACE_MS)
            saved.remove<Boolean>(KEY_APPLE_IN_BROWSER)
            saved.remove<String>(KEY_APPLE_VERIFIER)
            _state.update { it.copy(appleSigningIn = false) }
        }
    }

    /** Mã một lần từ deep link `dayhoc://auth/apple/callback?code=…`. */
    fun onAppleCallback(code: String) {
        appleReturnJob?.cancel()
        saved.remove<Boolean>(KEY_APPLE_IN_BROWSER)
        val verifier = saved.get<String>(KEY_APPLE_VERIFIER)
        saved.remove<String>(KEY_APPLE_VERIFIER)
        if (verifier == null || _state.value.loggedIn) {
            // Deep link cũ hoặc không do lượt này mở: bỏ qua.
            _state.update { it.copy(appleSigningIn = false) }
            return
        }
        _state.update { it.copy(appleSigningIn = true, failure = null) }
        viewModelScope.launch {
            try {
                val user = auth.redeemAppleAndroid(code, verifier)
                if (user != null) _state.update { it.copy(loggedIn = true) }
            } catch (e: ApiError) {
                val requiresOtp = e is ApiError.Http && e.httpStatus == 401 && e.code == ApiError.UNAUTHENTICATED &&
                    e.detailString("reason") == "EMAIL_REQUIRES_OTP"
                _state.update { it.copy(failure = if (requiresOtp) LoginFailure.EmailRequiresOtp else e.toAppleFailure()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(failure = LoginFailure.AppleFailed) }
            } finally {
                _state.update { it.copy(appleSigningIn = false) }
            }
        }
    }

    fun changeEmail() {
        resendJob?.cancel()
        lockJob?.cancel()
        _state.update { it.copy(sentTo = null, code = "", failure = null, resendIn = 0, lockedFor = 0) }
    }

    /** Màn hình đã hiện snackbar lỗi. */
    fun failureShown() = _state.update { it.copy(failure = it.failure as? LoginFailure.OtpInvalid) }

    private fun onLoginFailed(e: ApiError) {
        when ((e as? ApiError.Http)?.code) {
            ApiError.OTP_INVALID -> _state.update {
                it.copy(failure = LoginFailure.OtpInvalid((e as ApiError.Http).detailInt("attemptsLeft")))
            }
            ApiError.OTP_LOCKED -> {
                lockJob?.cancel()
                lockJob = countdown((e as ApiError.Http).retryAfterSec ?: LOCK_DEFAULT_SEC) { st, sec ->
                    st.copy(lockedFor = sec, code = if (sec == 0) "" else st.code)
                }
            }
            else -> _state.update { it.copy(failure = e.toFailure()) }
        }
    }

    private fun startResendCountdown(seconds: Int) {
        resendJob?.cancel()
        resendJob = countdown(seconds) { st, sec -> st.copy(resendIn = sec) }
    }

    private fun countdown(seconds: Int, set: (LoginUiState, Int) -> LoginUiState): Job = viewModelScope.launch {
        for (sec in seconds downTo 1) {
            _state.update { set(it, sec) }
            delay(1_000)
        }
        _state.update { set(it, 0) }
    }

    companion object {
        /** Đếm ngược "Gửi lại mã sau 4:59" (Figma B02): chờ mã hết hạn 5 phút mới cho gửi lại. */
        const val RESEND_AFTER_SEC = 5 * 60
        const val LOCK_DEFAULT_SEC = 15 * 60
        const val CODE_LENGTH = 6
    }
}

private const val KEY_APPLE_VERIFIER = "apple_code_verifier"
private const val KEY_APPLE_IN_BROWSER = "apple_in_browser"
private const val APPLE_RETURN_GRACE_MS = 1_500L

private val base64Url = Base64.getUrlEncoder().withoutPadding()

/** code_verifier RFC 7636: 32 byte ngẫu nhiên, base64url không đệm (43 ký tự). */
internal fun newCodeVerifier(): String = base64Url.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

/** code_challenge S256 = base64url(SHA-256(verifier)), không đệm. */
internal fun codeChallenge(verifier: String): String =
    base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

private fun ApiError.toAppleFailure(): LoginFailure = when {
    this is ApiError.Http && httpStatus == 404 -> LoginFailure.AppleUnsupported
    else -> toFailure().let { if (it == LoginFailure.Generic) LoginFailure.AppleFailed else it }
}

private fun ApiError.toFailure(): LoginFailure = when (this) {
    ApiError.Network, ApiError.Timeout -> LoginFailure.Network
    ApiError.InvalidResponse -> LoginFailure.Generic
    is ApiError.Http -> when {
        code == ApiError.RATE_LIMITED || code == ApiError.OTP_LOCKED -> LoginFailure.RateLimited
        code == ApiError.SERVICE_UNAVAILABLE || httpStatus >= 500 -> LoginFailure.Unavailable
        else -> LoginFailure.Generic
    }
}
