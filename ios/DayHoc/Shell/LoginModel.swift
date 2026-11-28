import AuthenticationServices
import CryptoKit
import Foundation
import Observation
import Security

/// State của [T01] Đăng nhập và [T01a] Nhập mã OTP.
@Observable @MainActor
final class LoginModel {
    enum Failure: Equatable {
        case invalidEmail
        /// Sai mã; `attemptsLeft` là số lần còn được nhập nếu server trả (`details.attemptsLeft`).
        case otpInvalid(attemptsLeft: Int?)
        case rateLimited
        case offline
        case unavailable
        case generic
        /// Backend yêu cầu đăng nhập bằng mã thay vì qua Apple hoặc Google.
        case emailRequiresOtp
        /// Không đăng nhập được bằng Google (picker lỗi, SDK không trả token…).
        case googleFailed
    }

    struct AppleCredential {
        let idToken: String
        let email: String?
        let fullName: String?
    }

    private enum AppleSignInError: Error {
        case nonceGenerationFailed
    }

    /// Gửi lại OTP: 5 phút/lần (spec Mục 5.2a).
    static let resendInterval: TimeInterval = 5 * 60
    /// Khóa khi server không trả `retryAfterSec`: 15 phút (spec Mục 5.2a).
    private static let defaultLock: TimeInterval = 15 * 60

    var email = ""
    private(set) var code = ""
    /// Email đã nhận mã; khác `nil` nghĩa là đang ở bước nhập OTP.
    private(set) var sentTo: String?
    private(set) var isSending = false
    private(set) var isVerifying = false
    /// Đang chờ Google Sign-In trả token (hoặc đang đổi token lấy phiên).
    private(set) var isGoogleSigningIn = false
    private(set) var isAppleSigningIn = false
    var failure: Failure?
    private(set) var resendAvailableAt: Date?
    private(set) var lockedUntil: Date?

    var isSubmitting: Bool { isSending || isVerifying || isGoogleSigningIn || isAppleSigningIn }
    var canStartSso: Bool { !isSubmitting && sentTo == nil }

    private let auth: AuthRepository
    private let google: (any GoogleIdTokenProvider)?
    private let now: () -> Date
    private let onSignedIn: @MainActor (UserDTO) async -> Void

    /// - Parameter now: đồng hồ, tiêm vào để test đếm ngược không phải chờ thật.
    /// - Parameter google: luồng Google Sign-In; `nil` thì nút Google không mở được.
    init(
        auth: AuthRepository,
        google: (any GoogleIdTokenProvider)? = nil,
        now: @escaping () -> Date = { .now },
        onSignedIn: @escaping @MainActor (UserDTO) async -> Void
    ) {
        self.auth = auth
        self.google = google
        self.now = now
        self.onSignedIn = onSignedIn
    }

    private var normalizedEmail: String {
        email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    var isEmailValid: Bool {
        normalizedEmail.wholeMatch(of: #/[^@\s]+@[^@\s]+\.[^@\s]+/#) != nil
    }

    func sendCode() async {
        guard !isSubmitting else { return }
        guard isEmailValid else {
            failure = .invalidEmail
            return
        }
        let target = normalizedEmail
        isSending = true
        failure = nil
        defer { isSending = false }
        do {
            try await auth.sendOtp(email: target)
            sentTo = target
            code = ""
            resendAvailableAt = now() + Self.resendInterval
        } catch {
            failure = Self.failure(for: error)
            if let wait = (error as? APIError)?.retryAfterSec {
                resendAvailableAt = now() + TimeInterval(wait)
            }
        }
    }

    /// Gửi lại mã; bỏ qua khi chưa hết 5 phút.
    func resend() async {
        guard sentTo != nil, secondsUntilResend(at: now()) == 0 else { return }
        await sendCode()
    }

    /// Chỉ giữ chữ số, tối đa 6.
    func enterCode(_ value: String) {
        code = String(value.filter { $0.isASCII && $0.isNumber }.prefix(6))
        if case .otpInvalid = failure { failure = nil }
    }

    /// Số lần còn được nhập khi sai mã; `nil` nếu lỗi khác hoặc server không trả.
    var invalidAttemptsLeft: Int? {
        if case .otpInvalid(let attemptsLeft) = failure { return attemptsLeft }
        return nil
    }

    func verify() async {
        guard let sentTo, code.count == 6, !isSubmitting, !isLocked(at: now()) else { return }
        isVerifying = true
        failure = nil
        defer { isVerifying = false }
        do {
            let user = try await auth.login(email: sentTo, code: code)
            await onSignedIn(user)
        } catch let error as APIError where error.code == "OTP_LOCKED" {
            lockedUntil = now() + (error.retryAfterSec.map(TimeInterval.init) ?? Self.defaultLock)
            code = ""
        } catch {
            failure = Self.failure(for: error)
            // Xoá mã sai để ô nhập trở về rỗng; giữ mã cũ thì lần gõ sau bị cắt ở 6 ký tự nên không nhập lại được.
            if case .otpInvalid = failure { code = "" }
        }
    }

    /// Quay lại bước nhập email.
    func changeEmail() {
        sentTo = nil
        code = ""
        failure = nil
        lockedUntil = nil
    }

    /// Bấm nút Google: khoá nút và trả `true`; `false` khi đang có thao tác khác hoặc chưa có SDK.
    func startGoogleSignIn() -> Bool {
        guard google != nil, canStartSso else { return false }
        isGoogleSigningIn = true
        failure = nil
        return true
    }

    /// Trình chọn tài khoản đóng lại. Hủy thì im lặng, lỗi thì báo.
    func finishGooglePicker(failed: Bool) {
        isGoogleSigningIn = false
        failure = failed ? .googleFailed : nil
    }

    /// Đổi ID token lấy phiên. Email cần OTP thì điền sẵn email nhưng không tự gửi mã.
    func signInWithGoogle(idToken: String, email: String?) async {
        guard isGoogleSigningIn else { return }
        defer { isGoogleSigningIn = false }
        do {
            let user = try await auth.loginWithGoogle(idToken: idToken)
            await onSignedIn(user)
        } catch {
            handleSsoFailure(error, email: email, fallback: .googleFailed)
        }
    }

    /// Nút Google ở màn đăng nhập: mở trình chọn tài khoản rồi đổi ID token lấy phiên.
    func continueWithGoogle() async {
        guard startGoogleSignIn(), let google else { return }
        do {
            let credential = try await google.credential()
            await signInWithGoogle(idToken: credential.idToken, email: credential.email)
        } catch GoogleSignInError.canceled {
            finishGooglePicker(failed: false)
        } catch {
            finishGooglePicker(failed: true)
        }
    }

    func continueWithApple(
        authorize: @MainActor (String) async throws -> AppleCredential
    ) async {
        guard canStartSso else { return }
        isAppleSigningIn = true
        failure = nil
        defer { isAppleSigningIn = false }

        let nonce: String
        let credential: AppleCredential
        do {
            nonce = try Self.makeAppleNonce()
            let hashedNonce = Self.hex(SHA256.hash(data: Data(nonce.utf8)))
            credential = try await authorize(hashedNonce)
        } catch let error as ASAuthorizationError where error.code == .canceled {
            return
        } catch {
            failure = .generic
            return
        }

        do {
            let user = try await auth.loginWithApple(
                idToken: credential.idToken,
                nonce: nonce,
                fullName: Self.appleFullName(credential.fullName)
            )
            await onSignedIn(user)
        } catch {
            handleSsoFailure(error, email: credential.email, fallback: .generic)
        }
    }

    private static func makeAppleNonce() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else { throw AppleSignInError.nonceGenerationFailed }
        return hex(bytes)
    }

    private static func hex(_ bytes: some Sequence<UInt8>) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func appleFullName(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        var name = ""
        var remaining = 120
        // Backend đo độ dài theo UTF-16; không cắt giữa một ký tự.
        for character in trimmed {
            let length = String(character).utf16.count
            guard length <= remaining else { break }
            name.append(character)
            remaining -= length
        }
        name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? nil : name
    }

    private func handleSsoFailure(_ error: any Error, email: String?, fallback: Failure) {
        if let apiError = error as? APIError,
           apiError.status == 401, apiError.code == "UNAUTHENTICATED", apiError.reason == "EMAIL_REQUIRES_OTP" {
            if let email, !email.isEmpty { self.email = email }
            failure = .emailRequiresOtp
            return
        }
        switch Self.failure(for: error) {
        case .generic, .invalidEmail, .otpInvalid: failure = fallback
        case let other: failure = other
        }
    }

    func secondsUntilResend(at date: Date) -> Int { Self.seconds(until: resendAvailableAt, from: date) }
    func secondsUntilUnlock(at date: Date) -> Int { Self.seconds(until: lockedUntil, from: date) }
    func isLocked(at date: Date) -> Bool { secondsUntilUnlock(at: date) > 0 }

    private static func seconds(until deadline: Date?, from date: Date) -> Int {
        guard let deadline else { return 0 }
        return max(0, Int(deadline.timeIntervalSince(date).rounded(.up)))
    }

    // shortcut: so `code` bằng chuỗi; đổi sang hằng số sinh từ error_codes.json khi có script sinh Swift.
    private static func failure(for error: any Error) -> Failure {
        guard let error = error as? APIError else { return .generic }
        switch (error.kind, error.code) {
        case (.offline, _), (.timeout, _): return .offline
        case (_, "OTP_INVALID"): return .otpInvalid(attemptsLeft: error.attemptsLeft)
        case (_, "RATE_LIMITED"), (_, "OTP_LOCKED"): return .rateLimited
        case (_, "VALIDATION_FAILED"): return .invalidEmail
        default: return (error.status ?? 0) >= 500 ? .unavailable : .generic
        }
    }
}
