package vn.dayhoc.app.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Call
import retrofit2.HttpException
import retrofit2.Response
import androidx.lifecycle.SavedStateHandle
import vn.dayhoc.app.core.auth.AppleAndroidRedeemRequest
import vn.dayhoc.app.core.auth.AppleAndroidRedeemResponse
import vn.dayhoc.app.core.auth.AppleAndroidStartRequest
import vn.dayhoc.app.core.auth.AppleAndroidStartResponse
import vn.dayhoc.app.core.auth.AuthApi
import vn.dayhoc.app.core.auth.AuthRepository
import vn.dayhoc.app.core.auth.GoogleSsoRequest
import vn.dayhoc.app.core.auth.InMemoryTokenStore
import vn.dayhoc.app.core.auth.LoginRequest
import vn.dayhoc.app.core.auth.LoginResponse
import vn.dayhoc.app.core.auth.RefreshRequest
import vn.dayhoc.app.core.auth.SendOtpRequest
import vn.dayhoc.app.core.auth.TokenResponse
import vn.dayhoc.app.core.auth.TokenStore
import vn.dayhoc.app.core.auth.Tokens
import vn.dayhoc.app.core.auth.UserDto

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val api = FakeAuthApi()
    private val store = InMemoryTokenStore()
    private val saved = SavedStateHandle()
    private lateinit var vm: LoginViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        vm = LoginViewModel(AuthRepository(api, store), saved)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `gui ma thanh cong chuyen sang buoc nhap ma`() = runTest {
        vm.onEmailChange(" Co.Lan@Example.com ")
        vm.sendCode()
        runCurrent()

        val s = vm.state.value
        assertEquals("co.lan@example.com", s.sentTo)
        assertEquals(listOf("co.lan@example.com"), api.sentTo)
        assertEquals(LoginViewModel.RESEND_AFTER_SEC, s.resendIn)
        assertFalse(s.sending)
    }

    @Test
    fun `email sai dinh dang thi bao loi va khong goi api`() = runTest {
        vm.onEmailChange("co.lan@")
        vm.sendCode()
        runCurrent()

        assertTrue(vm.state.value.showEmailError)
        assertNull(vm.state.value.sentTo)
        assertTrue(api.sentTo.isEmpty())
    }

    @Test
    fun `sai ma hien loi OTP_INVALID kem so lan con lai`() = runTest {
        sendCodeTo("co.lan@example.com")
        api.loginError = httpError(401, """{"code":"OTP_INVALID","message":"x","details":{"attemptsLeft":3},"requestId":"r"}""")

        vm.onCodeChange("123456")
        runCurrent()

        val s = vm.state.value
        assertEquals(LoginFailure.OtpInvalid(attemptsLeft = 3), s.failure)
        assertFalse(s.loggedIn)
        assertFalse(s.verifying)
        assertNull(store.tokens)
    }

    @Test
    fun `nhap sai qua 5 lan thi tam khoa theo retryAfterSec`() = runTest {
        sendCodeTo("co.lan@example.com")
        api.loginError = httpError(429, """{"code":"OTP_LOCKED","message":"x","details":{"retryAfterSec":900},"requestId":"r"}""")

        vm.onCodeChange("123456")
        runCurrent()

        assertEquals(900, vm.state.value.lockedFor)
        assertNull(vm.state.value.failure)
    }

    @Test
    fun `dung ma thi dang nhap va luu token`() = runTest {
        sendCodeTo("co.lan@example.com")

        vm.onCodeChange("482913")
        runCurrent()

        assertTrue(vm.state.value.loggedIn)
        assertEquals(LoginRequest("email_otp", "co.lan@example.com", "482913"), api.lastLogin)
        assertEquals(Tokens("access", "refresh"), store.tokens)
    }

    @Test
    fun `nut gui lai bi khoa trong 5 phut`() = runTest {
        sendCodeTo("co.lan@example.com")

        advanceTimeBy(299_000)
        assertTrue(vm.state.value.resendIn > 0)
        vm.sendCode()
        runCurrent()
        assertEquals(1, api.sentTo.size)

        advanceTimeBy(1_001)
        assertEquals(0, vm.state.value.resendIn)
        vm.sendCode()
        runCurrent()
        assertEquals(2, api.sentTo.size)
    }

    @Test
    fun `gui ma bi RATE_LIMITED thi dem nguoc gui lai theo retryAfterSec`() = runTest {
        api.sendError = httpError(429, """{"code":"RATE_LIMITED","message":"x","details":{"retryAfterSec":42},"requestId":"r"}""")

        sendCodeTo("co.lan@example.com")

        assertEquals(LoginFailure.RateLimited, vm.state.value.failure)
        assertEquals(42, vm.state.value.resendIn)
    }

    @Test
    fun `luu token loi thi bao loi chung khong crash`() = runTest {
        val brokenStore = object : TokenStore {
            override val tokens: Tokens? = null
            override suspend fun load(): Tokens? = null
            override suspend fun save(tokens: Tokens) {
                throw java.security.KeyStoreException("keystore hỏng")
            }
            override suspend fun clear() = Unit
        }
        vm = LoginViewModel(AuthRepository(api, brokenStore))
        sendCodeTo("co.lan@example.com")

        vm.onCodeChange("482913")
        runCurrent()

        assertEquals(LoginFailure.Generic, vm.state.value.failure)
        assertFalse(vm.state.value.loggedIn)
        assertFalse(vm.state.value.verifying)
    }

    @Test
    fun `Google thanh cong thi luu phien va chan OTP trong luc dang nhap`() = runTest {
        vm.onEmailChange("co.lan@example.com")
        assertTrue(vm.startGoogleSignIn())
        assertFalse(vm.startGoogleSignIn())
        vm.sendCode()
        vm.signInWithGoogle("test-id-token", "co.lan@example.com")
        runCurrent()

        assertEquals(GoogleSsoRequest("test-id-token"), api.lastSso)
        assertTrue(api.sentTo.isEmpty())
        assertEquals(Tokens("access", "refresh"), store.tokens)
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.googleSigningIn)
    }

    @Test
    fun `Google yeu cau OTP thi dien san email nhung khong tu gui ma`() = runTest {
        api.ssoError = httpError(401, """{"code":"UNAUTHENTICATED","details":{"reason":"EMAIL_REQUIRES_OTP"}}""")
        vm.onEmailChange("sai")
        vm.sendCode()
        assertTrue(vm.startGoogleSignIn())
        vm.signInWithGoogle("test-id-token", "co.lan@example.com")
        runCurrent()

        val s = vm.state.value
        assertEquals(LoginFailure.EmailRequiresOtp, s.failure)
        assertEquals("co.lan@example.com", s.email)
        assertFalse(s.showEmailError)
        assertFalse(s.googleSigningIn)
        assertFalse(s.loggedIn)
        assertNull(s.sentTo)
        assertNull(store.tokens)
        assertTrue(api.sentTo.isEmpty())
    }

    @Test
    fun `Google tra 503 thi bao he thong ban va cho thu lai`() = runTest {
        api.ssoError = httpError(503, """{"code":"SERVICE_UNAVAILABLE"}""")
        assertTrue(vm.startGoogleSignIn())
        vm.signInWithGoogle("test-id-token", "co.lan@example.com")
        runCurrent()

        assertEquals(LoginFailure.Unavailable, vm.state.value.failure)
        assertFalse(vm.state.value.googleSigningIn)
        assertNull(store.tokens)
    }

    @Test
    fun `huy chon tai khoan thi khong bao loi va co the mo lai`() = runTest {
        assertTrue(vm.startGoogleSignIn())
        vm.finishGooglePicker(failed = false)

        assertNull(vm.state.value.failure)
        assertNull(api.lastSso)
        assertTrue(vm.startGoogleSignIn())
    }

    @Test
    fun `Apple gui challenge S256 cua verifier roi mo URL trang Apple`() = runTest {
        vm.startAppleSignIn()
        runCurrent()

        val verifier = saved.get<String>("apple_code_verifier")!!
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(verifier))
        assertEquals(AppleAndroidStartRequest(codeChallenge(verifier)), api.lastAppleStart)
        assertEquals(APPLE_URL, vm.state.value.appleAuthUrl)
        assertTrue(vm.state.value.appleSigningIn)

        vm.appleBrowserOpened()
        assertNull(vm.state.value.appleAuthUrl)
    }

    @Test
    fun `callback Apple doi ma bang dung verifier roi luu phien`() = runTest {
        openApple()
        val verifier = saved.get<String>("apple_code_verifier")!!

        vm.onAppleCallback("one-time-code")
        runCurrent()

        assertEquals(AppleAndroidRedeemRequest("one-time-code", verifier), api.lastAppleRedeem)
        assertEquals(Tokens("access", "refresh"), store.tokens)
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.appleSigningIn)
        assertNull(saved.get<String>("apple_code_verifier"))
    }

    @Test
    fun `huy o trang Apple thi khong bao loi va khong luu phien`() = runTest {
        api.appleRedeemResponse = AppleAndroidRedeemResponse(status = "cancelled")
        openApple()

        vm.onAppleCallback("one-time-code")
        runCurrent()

        assertNull(vm.state.value.failure)
        assertFalse(vm.state.value.loggedIn)
        assertFalse(vm.state.value.appleSigningIn)
        assertNull(store.tokens)
    }

    @Test
    fun `dong Custom Tab khong co callback thi tha nut sau mot nhip, khong bao loi`() = runTest {
        openApple()

        vm.onReturnedFromBrowser()
        advanceTimeBy(1_000)
        assertTrue(vm.state.value.appleSigningIn)
        advanceTimeBy(600)

        assertFalse(vm.state.value.appleSigningIn)
        assertNull(vm.state.value.failure)
        assertNull(saved.get<String>("apple_code_verifier"))
    }

    @Test
    fun `callback toi ngay sau khi quay lai thi khong bi coi la huy`() = runTest {
        openApple()

        vm.onReturnedFromBrowser()
        vm.onAppleCallback("one-time-code")
        advanceTimeBy(2_000)

        assertTrue(vm.state.value.loggedIn)
        assertNotNull(api.lastAppleRedeem)
    }

    @Test
    fun `verifier con sau khi he thong tat app thi van doi duoc ma`() = runTest {
        openApple()
        val verifier = saved.get<String>("apple_code_verifier")!!
        // ViewModel mới dựng lại từ cùng SavedStateHandle (process death).
        val restored = LoginViewModel(AuthRepository(api, store), saved)

        restored.onAppleCallback("one-time-code")
        runCurrent()

        assertEquals(AppleAndroidRedeemRequest("one-time-code", verifier), api.lastAppleRedeem)
        assertTrue(restored.state.value.loggedIn)
    }

    @Test
    fun `deep link Apple khong do luot nay mo thi bo qua`() = runTest {
        vm.onAppleCallback("stale-code")
        runCurrent()

        assertNull(api.lastAppleRedeem)
        assertNull(vm.state.value.failure)
        assertFalse(vm.state.value.appleSigningIn)
    }

    @Test
    fun `server chua bat Apple Android thi bao chua ho tro`() = runTest {
        api.appleStartError = httpError(404, """{"code":"NOT_FOUND"}""")
        vm.startAppleSignIn()
        runCurrent()

        assertEquals(LoginFailure.AppleUnsupported, vm.state.value.failure)
        assertFalse(vm.state.value.appleSigningIn)
        assertNull(vm.state.value.appleAuthUrl)
        assertNull(saved.get<String>("apple_code_verifier"))
    }

    @Test
    fun `doi ma Apple loi 401 thi bao loi Apple, 503 thi bao he thong ban`() = runTest {
        api.appleRedeemError = httpError(401, """{"code":"UNAUTHENTICATED"}""")
        openApple()
        vm.onAppleCallback("one-time-code")
        runCurrent()
        assertEquals(LoginFailure.AppleFailed, vm.state.value.failure)
        assertNull(store.tokens)

        api.appleRedeemError = httpError(503, """{"code":"SERVICE_UNAVAILABLE"}""")
        vm.failureShown()
        openApple()
        vm.onAppleCallback("one-time-code")
        runCurrent()
        assertEquals(LoginFailure.Unavailable, vm.state.value.failure)
        assertFalse(vm.state.value.appleSigningIn)
    }

    @Test
    fun `Apple yeu cau OTP thi bao dung ly do`() = runTest {
        api.appleRedeemError = httpError(401, """{"code":"UNAUTHENTICATED","details":{"reason":"EMAIL_REQUIRES_OTP"}}""")
        openApple()
        vm.onAppleCallback("one-time-code")
        runCurrent()

        assertEquals(LoginFailure.EmailRequiresOtp, vm.state.value.failure)
        assertFalse(vm.state.value.loggedIn)
    }

    @Test
    fun `dang dang nhap Apple thi chan Apple lan hai, Google va OTP`() = runTest {
        vm.onEmailChange("co.lan@example.com")
        vm.startAppleSignIn()
        runCurrent()

        vm.startAppleSignIn()
        assertFalse(vm.startGoogleSignIn())
        vm.sendCode()
        runCurrent()

        assertEquals(1, api.appleStartCount)
        assertTrue(api.sentTo.isEmpty())
        assertTrue(vm.state.value.appleSigningIn)
    }

    @Test
    fun `dang dang nhap Google thi khong mo duoc Apple`() = runTest {
        assertTrue(vm.startGoogleSignIn())

        vm.startAppleSignIn()
        runCurrent()

        assertEquals(0, api.appleStartCount)
        assertFalse(vm.state.value.appleSigningIn)
    }

    @Test
    fun `code challenge theo vi du RFC 7636`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.openApple() {
        vm.startAppleSignIn()
        runCurrent()
        vm.appleBrowserOpened()
    }

    private fun kotlinx.coroutines.test.TestScope.sendCodeTo(email: String) {
        vm.onEmailChange(email)
        vm.sendCode()
        runCurrent()
    }

    private fun httpError(status: Int, body: String) =
        HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))
}

private const val APPLE_URL = "https://appleid.apple.com/auth/authorize?client_id=vn.dayhoc.app.web"

private class FakeAuthApi : AuthApi {
    val sentTo = mutableListOf<String>()
    var lastLogin: LoginRequest? = null
    var loginError: HttpException? = null
    var sendError: HttpException? = null

    override suspend fun sendOtp(body: SendOtpRequest) {
        sentTo += body.email
        sendError?.let { throw it }
    }

    override suspend fun login(body: LoginRequest): LoginResponse {
        lastLogin = body
        loginError?.let { throw it }
        return LoginResponse("access", "refresh", 900, UserDto("u1", body.email, "Cô Lan", emptyList()))
    }

    var lastSso: GoogleSsoRequest? = null
    var ssoError: HttpException? = null

    override suspend fun ssoGoogle(body: GoogleSsoRequest): LoginResponse {
        lastSso = body
        ssoError?.let { throw it }
        return LoginResponse("access", "refresh", 900, UserDto("u1", "co.lan@example.com", "Cô Lan", emptyList()))
    }

    var lastAppleStart: AppleAndroidStartRequest? = null
    var appleStartCount = 0
    var appleStartError: HttpException? = null

    override suspend fun appleAndroidStart(body: AppleAndroidStartRequest): AppleAndroidStartResponse {
        lastAppleStart = body
        appleStartCount++
        appleStartError?.let { throw it }
        return AppleAndroidStartResponse(APPLE_URL)
    }

    var lastAppleRedeem: AppleAndroidRedeemRequest? = null
    var appleRedeemError: HttpException? = null
    var appleRedeemResponse = AppleAndroidRedeemResponse(
        accessToken = "access",
        refreshToken = "refresh",
        user = UserDto("u1", "co.lan@example.com", "Cô Lan", emptyList()),
    )

    override suspend fun appleAndroidRedeem(body: AppleAndroidRedeemRequest): AppleAndroidRedeemResponse {
        lastAppleRedeem = body
        appleRedeemError?.let { throw it }
        return appleRedeemResponse
    }

    override fun refresh(body: RefreshRequest): Call<TokenResponse> = error("không dùng trong test này")

    override suspend fun me(): UserDto = error("không dùng trong test này")

    override suspend fun logout() = Unit
}
