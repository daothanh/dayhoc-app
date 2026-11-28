package vn.dayhoc.app.core.auth

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.apiCall

/**
 * Đăng nhập email OTP và phiên. Lỗi API ném ra là [ApiError].
 * [sessionMutex] dùng chung với [vn.dayhoc.app.core.network.TokenAuthenticator]: chỉ khóa quanh lúc ghi token,
 * không khóa quanh lời gọi mạng (request có thể 401 và cần chính khóa này để refresh).
 */
class AuthRepository(
    private val api: AuthApi,
    private val tokenStore: TokenStore,
    private val sessionMutex: Mutex = Mutex(),
) {
    suspend fun sendOtp(email: String) = apiCall { api.sendOtp(SendOtpRequest(email)) }

    suspend fun login(email: String, code: String): UserDto {
        val response = apiCall { api.login(LoginRequest(method = "email_otp", email = email, code = code)) }
        return saveSession(response)
    }

    suspend fun loginWithGoogle(idToken: String): UserDto {
        val response = apiCall { api.ssoGoogle(GoogleSsoRequest(idToken)) }
        return saveSession(response)
    }

    /** Trả URL trang đăng nhập Apple để mở trong Custom Tab. */
    suspend fun startAppleAndroid(codeChallenge: String): String =
        apiCall { api.appleAndroidStart(AppleAndroidStartRequest(codeChallenge)) }.authorizationUrl

    /** `null` khi người dùng hủy ở trang Apple. */
    suspend fun redeemAppleAndroid(code: String, codeVerifier: String): UserDto? {
        val r = apiCall { api.appleAndroidRedeem(AppleAndroidRedeemRequest(code, codeVerifier)) }
        if (r.status == "cancelled") return null
        val user = r.user ?: throw ApiError.InvalidResponse
        return saveSession(r.accessToken ?: throw ApiError.InvalidResponse, r.refreshToken ?: throw ApiError.InvalidResponse, user)
    }

    private suspend fun saveSession(response: LoginResponse): UserDto =
        saveSession(response.accessToken, response.refreshToken, response.user)

    private suspend fun saveSession(access: String, refresh: String, user: UserDto): UserDto {
        sessionMutex.withLock { tokenStore.save(Tokens(access, refresh)) }
        return user
    }

    suspend fun me(): UserDto = apiCall { api.me() }

    /** Thu hồi phiên trên server nếu được; token trên máy luôn bị xóa. */
    suspend fun logout() {
        try {
            apiCall { api.logout() }
        } catch (e: ApiError) {
            // Mất mạng hoặc phiên đã hết hạn: vẫn đăng xuất trên máy.
        } finally {
            // NonCancellable: rời màn hình giữa chừng vẫn phải xóa token.
            withContext(NonCancellable) { sessionMutex.withLock { tokenStore.clear() } }
        }
    }
}
