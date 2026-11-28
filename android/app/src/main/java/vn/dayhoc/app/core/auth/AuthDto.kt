package vn.dayhoc.app.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTO viết tay theo teachhub-backend/contracts/openapi.json (tag auth, me).

@Serializable
data class SendOtpRequest(val email: String)

@Serializable
data class LoginRequest(val method: String, val email: String, val code: String)

@Serializable
data class GoogleSsoRequest(@SerialName("id_token") val idToken: String)

/** Body `POST /v1/auth/sso/apple/android/start`: [codeChallenge] = base64url(SHA-256(code_verifier)). */
@Serializable
data class AppleAndroidStartRequest(@SerialName("code_challenge") val codeChallenge: String)

@Serializable
data class AppleAndroidStartResponse(@SerialName("authorization_url") val authorizationUrl: String)

/** Body `POST /v1/auth/sso/apple/android/redeem`: [code] lấy từ deep link `dayhoc://auth/apple/callback`. */
@Serializable
data class AppleAndroidRedeemRequest(val code: String, @SerialName("code_verifier") val codeVerifier: String)

/** Hợp của [LoginResponse] và `{"status":"cancelled"}` (người dùng hủy ở trang Apple). */
@Serializable
data class AppleAndroidRedeemResponse(
    val status: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val user: UserDto? = null,
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val user: UserDto,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String?,
    val permissions: List<String>,
)
