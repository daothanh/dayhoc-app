/// Đăng nhập email OTP, SSO và quản lý phiên (spec Mục 5.2a).
struct AuthRepository: Sendable {
    let api: APIClient
    let tokenStore: any TokenStore

    var hasSession: Bool { tokenStore.load() != nil }

    /// Gửi mã 6 số tới email. Server giới hạn 5 phút/lần, 5 lần/giờ (429 `RATE_LIMITED`).
    func sendOtp(email: String) async throws {
        try await api.send("POST", "auth/otp/send", body: SendOtpRequest(email: email), authorized: false)
    }

    /// Đăng nhập (email chưa có tài khoản thì là đăng ký) và lưu token.
    func login(email: String, code: String) async throws -> UserDTO {
        try await openSession("auth/login", body: LoginRequest(email: email, code: code))
    }

    /// Đổi ID token Google lấy phiên. Email cần OTP thì server trả 401 `UNAUTHENTICATED`
    /// kèm `details.reason = EMAIL_REQUIRES_OTP`.
    func loginWithGoogle(idToken: String) async throws -> UserDTO {
        try await openSession("auth/sso/google", body: GoogleSsoRequest(idToken: idToken))
    }

    func loginWithApple(idToken: String, nonce: String, fullName: String?) async throws -> UserDTO {
        try await openSession("auth/sso/apple", body: AppleSsoRequest(idToken: idToken, nonce: nonce, fullName: fullName))
    }

    private func openSession(_ path: String, body: some Encodable & Sendable) async throws -> UserDTO {
        let response: LoginResponse = try await api.request("POST", path, body: body, authorized: false)
        try tokenStore.save(Tokens(accessToken: response.accessToken, refreshToken: response.refreshToken))
        return response.user
    }

    func me() async throws -> UserDTO {
        try await api.request("GET", "auth/me")
    }

    /// Thu hồi phiên ở server rồi xóa token trên máy; lỗi mạng không chặn đăng xuất.
    func logout() async {
        try? await api.send("POST", "auth/logout")
        tokenStore.clear()
    }
}
