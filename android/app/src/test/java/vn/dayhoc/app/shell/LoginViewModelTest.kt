package vn.dayhoc.app.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Call
import retrofit2.HttpException
import retrofit2.Response
import vn.dayhoc.app.core.auth.AppleSsoRequest
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
    private lateinit var vm: LoginViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        vm = LoginViewModel(AuthRepository(api, store))
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
    fun `Apple thanh cong thi gui nonce tho va ten da cat roi luu phien`() = runTest {
        val nonce = vm.startAppleSignIn()!!
        assertTrue(Regex("[0-9a-f]{64}").matches(nonce))

        vm.signInWithApple("apple-id-token", nonce, "co.lan@example.com", " " + "𠮷".repeat(60) + " Lan ")
        runCurrent()

        assertEquals(AppleSsoRequest("apple-id-token", nonce, "𠮷".repeat(60)), api.lastApple)
        assertEquals(Tokens("access", "refresh"), store.tokens)
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.appleSigningIn)
    }

    @Test
    fun `dang dang nhap Apple thi chan Apple lan hai, Google va OTP`() = runTest {
        vm.onEmailChange("co.lan@example.com")
        assertNotNull(vm.startAppleSignIn())

        assertNull(vm.startAppleSignIn())
        assertFalse(vm.startGoogleSignIn())
        vm.sendCode()
        runCurrent()

        assertTrue(api.sentTo.isEmpty())
        assertTrue(vm.state.value.appleSigningIn)
        assertFalse(vm.state.value.googleSigningIn)
    }

    @Test
    fun `dang dang nhap Google thi khong mo duoc Apple`() = runTest {
        assertTrue(vm.startGoogleSignIn())

        assertNull(vm.startAppleSignIn())
        assertFalse(vm.state.value.appleSigningIn)
    }

    @Test
    fun `huy xac thuc Apple thi khong bao loi va lan sau dung nonce moi`() = runTest {
        val first = vm.startAppleSignIn()
        vm.finishAppleSignIn(failed = false)

        assertNull(vm.state.value.failure)
        assertFalse(vm.state.value.appleSigningIn)
        assertNull(api.lastApple)
        val second = vm.startAppleSignIn()
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test
    fun `xac thuc Apple loi thi bao loi chung`() = runTest {
        assertNotNull(vm.startAppleSignIn())
        vm.finishAppleSignIn(failed = true)

        assertEquals(LoginFailure.Generic, vm.state.value.failure)
        assertFalse(vm.state.value.appleSigningIn)
    }

    @Test
    fun `Apple yeu cau OTP thi dien san email nhung khong tu gui ma`() = runTest {
        api.appleError = httpError(401, """{"code":"UNAUTHENTICATED","details":{"reason":"EMAIL_REQUIRES_OTP"}}""")
        vm.onEmailChange("sai")
        vm.sendCode()
        val nonce = vm.startAppleSignIn()!!
        vm.signInWithApple("apple-id-token", nonce, "co.lan@example.com", null)
        runCurrent()

        val s = vm.state.value
        assertEquals(LoginFailure.EmailRequiresOtp, s.failure)
        assertEquals("co.lan@example.com", s.email)
        assertFalse(s.showEmailError)
        assertFalse(s.appleSigningIn)
        assertFalse(s.loggedIn)
        assertNull(s.sentTo)
        assertNull(api.lastApple?.fullName)
        assertNull(store.tokens)
        assertTrue(api.sentTo.isEmpty())
    }

    @Test
    fun `Apple yeu cau OTP ma khong co email thi giu email dang nhap`() = runTest {
        api.appleError = httpError(401, """{"code":"UNAUTHENTICATED","details":{"reason":"EMAIL_REQUIRES_OTP"}}""")
        vm.onEmailChange("co.lan@example.com")
        val nonce = vm.startAppleSignIn()!!
        vm.signInWithApple("apple-id-token", nonce, "", null)
        runCurrent()

        assertEquals(LoginFailure.EmailRequiresOtp, vm.state.value.failure)
        assertEquals("co.lan@example.com", vm.state.value.email)
    }

    @Test
    fun `Apple tra 503 thi bao he thong ban va cho thu lai`() = runTest {
        api.appleError = httpError(503, """{"code":"SERVICE_UNAVAILABLE"}""")
        val nonce = vm.startAppleSignIn()!!
        vm.signInWithApple("apple-id-token", nonce, null, null)
        runCurrent()

        assertEquals(LoginFailure.Unavailable, vm.state.value.failure)
        assertFalse(vm.state.value.appleSigningIn)
        assertNull(store.tokens)
        assertNotNull(vm.startAppleSignIn())
    }

    @Test
    fun `Apple tra 401 khong co ly do thi bao loi chung`() = runTest {
        api.appleError = httpError(401, """{"code":"UNAUTHENTICATED"}""")
        val nonce = vm.startAppleSignIn()!!
        vm.signInWithApple("apple-id-token", nonce, "co.lan@example.com", null)
        runCurrent()

        assertEquals(LoginFailure.Generic, vm.state.value.failure)
        assertEquals("", vm.state.value.email)
        assertNull(store.tokens)
    }

    @Test
    fun `ban bam nonce gui Apple la SHA-256 hex chu thuong`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", appleNonceHash("abc"))
    }

    @Test
    fun `ten Apple duoc cat con 120 don vi UTF-16 va khong cat giua ky tu`() {
        assertNull(appleFullName(null))
        assertNull(appleFullName("   "))
        assertEquals("Cô Lan", appleFullName("  Cô Lan \n"))
        assertEquals("a".repeat(120), appleFullName("a".repeat(121)))
        // 𠮷 chiếm 2 đơn vị UTF-16: 119 chữ "a" chỉ còn chỗ cho 1 đơn vị nên bỏ hẳn ký tự kế tiếp.
        assertEquals("a".repeat(119), appleFullName("a".repeat(119) + "𠮷"))
        // Khoảng trắng lộ ra ở cuối sau khi cắt cũng phải bỏ.
        assertEquals("a".repeat(119), appleFullName("a".repeat(119) + " Lan"))
    }

    @Test
    fun `body Apple khong co fullName khi khong co ten`() {
        val json = Json { ignoreUnknownKeys = true }

        assertEquals(
            """{"id_token":"t","nonce":"n"}""",
            json.encodeToString(AppleSsoRequest.serializer(), AppleSsoRequest("t", "n")),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.sendCodeTo(email: String) {
        vm.onEmailChange(email)
        vm.sendCode()
        runCurrent()
    }

    private fun httpError(status: Int, body: String) =
        HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))
}

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

    var lastApple: AppleSsoRequest? = null
    var appleError: HttpException? = null

    override suspend fun ssoApple(body: AppleSsoRequest): LoginResponse {
        lastApple = body
        appleError?.let { throw it }
        return LoginResponse("access", "refresh", 900, UserDto("u1", "co.lan@example.com", "Cô Lan", emptyList()))
    }

    override fun refresh(body: RefreshRequest): Call<TokenResponse> = error("không dùng trong test này")

    override suspend fun me(): UserDto = error("không dùng trong test này")

    override suspend fun logout() = Unit
}
