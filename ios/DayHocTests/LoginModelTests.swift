import AuthenticationServices
import CryptoKit
import Foundation
import Testing
@testable import DayHoc

@MainActor
struct LoginModelTests {
    private let store = InMemoryTokenStore()

    private func makeModel(
        _ server: MockServer,
        google: (any GoogleIdTokenProvider)? = nil,
        now: @escaping () -> Date = { .now },
        onSignedIn: @escaping @MainActor (UserDTO) async -> Void = { _ in }
    ) -> LoginModel {
        let auth = AuthRepository(api: server.makeClient(tokenStore: store), tokenStore: store)
        let model = LoginModel(auth: auth, google: google, now: now, onSignedIn: onSignedIn)
        model.email = " Co.Lan@Example.com "
        return model
    }

    @Test func sendCodeMovesToOtpStep() async {
        let server = MockServer { _ in .noContent }
        let model = makeModel(server)

        await model.sendCode()

        #expect(model.sentTo == "co.lan@example.com")
        #expect(model.failure == nil)
        #expect(server.requests(to: "auth/otp/send").first?.jsonBody == ["email": "co.lan@example.com"])
    }

    @Test func wrongCodeShowsError() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/login" ? .error(401, "OTP_INVALID") : .noContent
        }
        let model = makeModel(server)
        await model.sendCode()

        model.enterCode("12a3456")
        await model.verify()

        #expect(model.failure == .otpInvalid(attemptsLeft: nil))
        #expect(model.sentTo != nil)
        // Sai mã thì xoá ô nhập để người dùng gõ lại được ngay.
        #expect(model.code.isEmpty)
        #expect(server.requests(to: "auth/login").first?.jsonBody
            == ["method": "email_otp", "email": "co.lan@example.com", "code": "123456"])
        // Endpoint công khai: 401 không kích hoạt làm mới token.
        #expect(server.requests(to: "auth/refresh").isEmpty)
    }

    @Test func invalidCodeKeepsAttemptsLeft() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/login"
                ? .error(401, "OTP_INVALID", attemptsLeft: 3)
                : .noContent
        }
        let model = makeModel(server)
        await model.sendCode()

        model.enterCode("123456")
        await model.verify()

        #expect(model.failure == .otpInvalid(attemptsLeft: 3))
        #expect(model.invalidAttemptsLeft == 3)

        // Gõ lại thì xoá báo lỗi cũ để ô mã không còn ở trạng thái lỗi.
        model.enterCode("12345")
        #expect(model.failure == nil)
        #expect(model.invalidAttemptsLeft == nil)
    }

    @Test func enterNewCodeAfterInvalidSucceeds() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/login" ? .error(401, "OTP_INVALID") : .noContent
        }
        let model = makeModel(server)
        await model.sendCode()

        model.enterCode("123456")
        await model.verify()
        #expect(model.code.isEmpty)

        // Gõ lại mã mới phải nhận đủ 6 số, không bị giữ mã sai cũ.
        model.enterCode("654321")
        #expect(model.code == "654321")
    }

    @Test func resendBlockedUntilIntervalElapses() async {
        let server = MockServer { _ in .noContent }
        var clock = Date(timeIntervalSince1970: 1_000)
        let model = makeModel(server, now: { clock })
        await model.sendCode()

        clock += 299
        #expect(model.secondsUntilResend(at: clock) == 1)
        await model.resend()
        #expect(server.requests(to: "auth/otp/send").count == 1)

        clock += 1
        await model.resend()
        #expect(server.requests(to: "auth/otp/send").count == 2)
    }

    @Test func lockedOtpWaitsRetryAfter() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/login" ? .error(429, "OTP_LOCKED", retryAfterSec: 900) : .noContent
        }
        let clock = Date(timeIntervalSince1970: 1_000)
        let model = makeModel(server, now: { clock })
        await model.sendCode()

        model.enterCode("123456")
        await model.verify()

        #expect(model.isLocked(at: clock + 899))
        #expect(!model.isLocked(at: clock + 900))
        #expect(model.code.isEmpty)
    }

    @Test func correctCodeSavesTokensAndSignsIn() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/login"
                ? Stub(status: 200, json: #"{"access_token":"a1","refresh_token":"r1","expires_in":900,"user":\#(userJSON)}"#)
                : .noContent
        }
        var signedIn: UserDTO?
        let model = makeModel(server, onSignedIn: { signedIn = $0 })
        await model.sendCode()

        model.enterCode("123456")
        await model.verify()

        #expect(signedIn?.displayName == "Cô Lan")
        #expect(store.load() == Tokens(accessToken: "a1", refreshToken: "r1"))
    }

    @Test func googleSignInSavesSessionAndBlocksOtp() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/sso/google"
                ? Stub(status: 200, json: #"{"access_token":"a1","refresh_token":"r1","expires_in":900,"user":\#(userJSON)}"#)
                : .noContent
        }
        var signedIn: UserDTO?
        let model = makeModel(server, google: FakeGoogleProvider(), onSignedIn: { signedIn = $0 })

        #expect(model.startGoogleSignIn())
        // Đang chờ Google Sign-In thì không mở lại và không gửi mã qua email.
        #expect(!model.startGoogleSignIn())
        await model.sendCode()
        await model.signInWithGoogle(idToken: "test-id-token", email: "co.lan@example.com")

        #expect(server.requests(to: "auth/sso/google").first?.jsonBody == ["id_token": "test-id-token"])
        #expect(server.requests(to: "auth/otp/send").isEmpty)
        #expect(signedIn?.displayName == "Cô Lan")
        #expect(store.load() == Tokens(accessToken: "a1", refreshToken: "r1"))
        #expect(!model.isGoogleSigningIn)
    }

    @Test func googleRequiresOtpFillsEmailWithoutSendingCode() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/sso/google"
                ? .error(401, "UNAUTHENTICATED", reason: "EMAIL_REQUIRES_OTP")
                : .noContent
        }
        let model = makeModel(server, google: FakeGoogleProvider())
        model.email = "sai"

        #expect(model.startGoogleSignIn())
        await model.signInWithGoogle(idToken: "test-id-token", email: "co.lan@example.com")

        #expect(model.failure == .emailRequiresOtp)
        #expect(model.email == "co.lan@example.com")
        #expect(model.sentTo == nil)
        #expect(server.requests(to: "auth/otp/send").isEmpty)
        #expect(store.load() == nil)
        #expect(!model.isGoogleSigningIn)
    }

    @Test func googleServiceUnavailableShowsUnavailable() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/sso/google" ? .error(503, "SERVICE_UNAVAILABLE") : .noContent
        }
        let model = makeModel(server, google: FakeGoogleProvider())

        #expect(model.startGoogleSignIn())
        await model.signInWithGoogle(idToken: "test-id-token", email: nil)

        #expect(model.failure == .unavailable)
        #expect(!model.isGoogleSigningIn)
        #expect(store.load() == nil)
    }

    @Test func googleCancelKeepsNoErrorAndAllowsReopen() async {
        let server = MockServer { _ in .noContent }
        let model = makeModel(server, google: FakeGoogleProvider())

        #expect(model.startGoogleSignIn())
        model.finishGooglePicker(failed: false)

        #expect(model.failure == nil)
        #expect(model.startGoogleSignIn())
        #expect(server.requests(to: "auth/sso/google").isEmpty)
    }

    @Test func googleBlockedWithoutProvider() async {
        let server = MockServer { _ in .noContent }
        let model = makeModel(server)

        #expect(!model.startGoogleSignIn())
    }

    @Test func continueWithGoogleSignsInThroughProvider() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/sso/google"
                ? Stub(status: 200, json: #"{"access_token":"a1","refresh_token":"r1","expires_in":900,"user":\#(userJSON)}"#)
                : .noContent
        }
        let provider = FakeGoogleProvider()
        let model = makeModel(server, google: provider)

        await model.continueWithGoogle()

        #expect(provider.calls == 1)
        #expect(server.requests(to: "auth/sso/google").first?.jsonBody == ["id_token": "test-id-token"])
        #expect(!model.isGoogleSigningIn)
    }

    @Test func continueWithGoogleCanceledIsSilent() async {
        let server = MockServer { _ in .noContent }
        let provider = FakeGoogleProvider()
        provider.result = .failure(GoogleSignInError.canceled)
        let model = makeModel(server, google: provider)

        await model.continueWithGoogle()

        #expect(model.failure == nil)
        #expect(!model.isGoogleSigningIn)
    }

    @Test func continueWithGoogleFailureShowsGoogleError() async {
        let server = MockServer { _ in .noContent }
        let provider = FakeGoogleProvider()
        provider.result = .failure(GoogleSignInError.unavailable)
        let model = makeModel(server, google: provider)

        await model.continueWithGoogle()

        #expect(model.failure == .googleFailed)
        #expect(!model.isGoogleSigningIn)
    }

    @Test func appleSignInSendsRawNonceAndSavesSession() async throws {
        let server = MockServer { _ in
            Stub(status: 200, json: #"{"access_token":"a1","refresh_token":"r1","expires_in":900,"user":\#(userJSON)}"#)
        }
        var signedIn: UserDTO?
        let model = makeModel(server, onSignedIn: { signedIn = $0 })
        var hashSentToApple: String?
        let name = String(repeating: "𠮷", count: 60)

        await model.continueWithApple { hash in
            hashSentToApple = hash
            return .init(idToken: "test-apple-token", email: nil, fullName: " \(name) Lan ")
        }

        let request = try #require(server.requests(to: "auth/sso/apple").first)
        let nonce = try #require(request.jsonBody["nonce"])
        #expect(nonce.wholeMatch(of: #/[0-9a-f]{64}/#) != nil)
        let expectedHash = SHA256.hash(data: Data(nonce.utf8)).map { String(format: "%02x", $0) }.joined()
        #expect(hashSentToApple == expectedHash)
        #expect(hashSentToApple != nonce)
        #expect(request.httpMethod == "POST")
        #expect(request.jsonBody == ["id_token": "test-apple-token", "nonce": nonce, "fullName": name])
        #expect(signedIn?.displayName == "Cô Lan")
        #expect(store.load() == Tokens(accessToken: "a1", refreshToken: "r1"))
        #expect(!model.isAppleSigningIn)
    }

    @Test func appleCancellationIsSilentAndBlocksOtherSubmissionsUntilDismissed() async {
        let server = MockServer { _ in .noContent }
        let provider = FakeGoogleProvider()
        let model = makeModel(server, google: provider)
        var hashes: [String] = []

        await model.continueWithApple { hash in
            hashes.append(hash)
            #expect(model.isAppleSigningIn)
            await model.continueWithGoogle()
            await model.sendCode()
            await model.continueWithApple { _ in
                Issue.record("Không được mở thêm một phiên xác thực Apple.")
                throw ASAuthorizationError(.canceled)
            }
            throw ASAuthorizationError(.canceled)
        }

        #expect(model.failure == nil)
        #expect(!model.isAppleSigningIn)
        #expect(provider.calls == 0)
        #expect(server.requests(to: "auth/otp/send").isEmpty)
        #expect(server.requests(to: "auth/sso/apple").isEmpty)

        await model.continueWithApple { hash in
            hashes.append(hash)
            throw ASAuthorizationError(.canceled)
        }
        #expect(Set(hashes).count == 2)
    }

    @Test func appleRequiresOtpFillsEmailWithoutSendingCodeAndOmitsMissingName() async {
        let server = MockServer { request in
            request.url?.path == "/v1/auth/sso/apple"
                ? .error(401, "UNAUTHENTICATED", reason: "EMAIL_REQUIRES_OTP")
                : .noContent
        }
        let model = makeModel(server)
        model.email = ""

        await model.continueWithApple { _ in
            .init(idToken: "test-apple-token", email: "co.lan@example.com", fullName: nil)
        }

        let requests = server.requests(to: "auth/sso/apple")
        #expect(requests.count == 1)
        #expect(requests.first?.jsonBody["fullName"] == nil)
        #expect(model.failure == .emailRequiresOtp)
        #expect(model.email == "co.lan@example.com")
        #expect(model.sentTo == nil)
        #expect(server.requests(to: "auth/otp/send").isEmpty)
        #expect(server.requests(to: "auth/refresh").isEmpty)
        #expect(store.load() == nil)
        #expect(!model.isAppleSigningIn)
    }

    @Test func appleServiceUnavailableUsesExistingErrorState() async {
        let server = MockServer { _ in .error(503, "SERVICE_UNAVAILABLE") }
        let model = makeModel(server)

        await model.continueWithApple { _ in
            .init(idToken: "test-apple-token", email: nil, fullName: nil)
        }

        #expect(model.failure == .unavailable)
        #expect(model.canStartSso)
        #expect(store.load() == nil)
    }
}

/// Thay SDK GoogleSign-In trong test: trả sẵn credential hoặc lỗi.
@MainActor
private final class FakeGoogleProvider: GoogleIdTokenProvider {
    var result: Result<GoogleCredential, any Error> =
        .success(GoogleCredential(idToken: "test-id-token", email: "co.lan@example.com"))
    private(set) var calls = 0

    func credential() async throws -> GoogleCredential {
        calls += 1
        return try result.get()
    }
}
