import SwiftUI

// Figma B01: khoảng 18 giữa hình minh họa và khối đăng nhập (chưa có token tương ứng).
private let heroActionGap: CGFloat = 18

// Trang pháp lý của DayHoc (teachhub-site). Xem Figma B01, dòng điều khoản.
private let termsUrl = "https://dayhoc.app/terms"
private let privacyUrl = "https://dayhoc.app/privacy-policy"

/// [T01] Chào & Đăng nhập (Figma B01): logo, khẩu hiệu, hình minh họa, hai nút SSO, chia cách
/// "hoặc dùng email", ô email, nút gửi mã và dòng điều khoản.
///
/// Nút Google đã nối SDK Sign-In; nút Apple dựng theo thiết kế nhưng chưa nối, bấm vào chỉ báo dùng
/// Google hoặc email.
struct LoginView: View {
    @Bindable var model: LoginModel

    @State private var toast: String?
    @Environment(\.dhColors) private var colors

    var body: some View {
        NavigationStack {
            ScrollView {
                emailStep
            }
            .scrollDismissesKeyboard(.interactively)
            .dhScreen()
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: otpPresented) { OtpView(model: model) }
        }
        .overlay(alignment: .bottom) { Toast(text: $toast) }
        // Lỗi không gắn với ô nhập (quá nhanh, mất mạng…) báo bằng toast để không làm nhảy bố cục.
        .onChange(of: model.failure) { _, failure in
            if let message = failure?.toastMessage { toast = message }
        }
    }

    private var emailStep: some View {
        VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
            VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingControl) {
                DhBrandLogo()
                VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingInline) {
                    Text("login.slogan")
                        .font(.title2.weight(.semibold))
                    Text("login.tagline")
                        .font(.subheadline)
                        .foregroundStyle(colors.onSurfaceVariant)
                }
                .padding(.top, TeachHubTokens.Dimens.spacingControl)
            }
            DhHeroClasses()
            Color.clear.frame(height: heroActionGap)
            DhAppleButton(title: "login.continueApple", enabled: !model.isGoogleSigningIn, action: showAppleSoon)
            DhGoogleButton(
                title: model.isGoogleSigningIn ? "login.googleSigningIn" : "login.continueGoogle",
                enabled: !model.isGoogleSigningIn && !model.isSending,
                action: signInWithGoogle
            )
            DhDivider(label: "login.orEmail")
            DhTextField(
                label: "login.email",
                text: $model.email,
                prompt: "login.emailHint",
                helper: "login.emailHelper",
                error: model.failure == .invalidEmail ? model.failure?.message : nil,
                identifier: "login.email"
            )
            .keyboardType(.emailAddress)
            .textContentType(.emailAddress)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.send)
            .onSubmit(send)
            .disabled(model.isSending || model.isGoogleSigningIn)
            DhPrimaryButton(
                title: "login.sendOtp",
                loadingTitle: "login.sending",
                isLoading: model.isSending,
                identifier: "login.sendOtp",
                action: send
            )
            .disabled(!model.isEmailValid || model.isGoogleSigningIn)
            termsNotice
        }
        .padding(TeachHubTokens.Dimens.spacingPage)
    }

    /// Dòng điều khoản (Figma B01): hai nhãn gạch chân tô màu link và bấm mở trang pháp lý.
    private var termsNotice: some View {
        let sentence = String(localized: "login.terms")
        let terms = String(localized: "login.termsLinkTerms")
        let privacy = String(localized: "login.termsLinkPrivacy")
        var text = AttributedString(sentence)
        link(&text, label: terms, url: termsUrl)
        link(&text, label: privacy, url: privacyUrl)
        return Text(text)
            .font(.footnote)
            .foregroundStyle(colors.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func link(_ text: inout AttributedString, label: String, url: String) {
        guard let range = text.range(of: label) else { return }
        text[range].link = URL(string: url)
        text[range].underlineStyle = .single
        text[range].foregroundColor = colors.secondary
    }

    private func showAppleSoon() {
        toast = String(localized: "login.appleSoon")
    }

    private func signInWithGoogle() {
        Task { await model.continueWithGoogle() }
    }

    /// Bước OTP hiện khi đã gửi mã; bấm "Đổi email" hoặc quay lại là về bước nhập email.
    private var otpPresented: Binding<Bool> {
        Binding(
            get: { model.sentTo != nil },
            set: { if !$0 { model.changeEmail() } }
        )
    }

    private func send() {
        Task { await model.sendCode() }
    }
}

extension LoginModel.Failure {
    /// Câu báo tiếng Việt (Localizable.xcstrings) cho từng lỗi.
    var message: LocalizedStringKey {
        switch self {
        case .invalidEmail: "login.emailInvalid"
        case .otpInvalid(let attemptsLeft):
            if let attemptsLeft {
                "otp.invalidAttempts \(attemptsLeft)"
            } else {
                "otp.invalid"
            }
        case .rateLimited: "login.rateLimited"
        case .offline: "error.offline"
        case .unavailable: "login.unavailable"
        case .generic: "login.failed"
        case .emailRequiresOtp: "login.googleRequiresOtp"
        case .googleFailed: "login.googleFailed"
        }
    }

    /// Lỗi báo bằng toast vì không gắn với ô nhập nào; `nil` cho lỗi đã hiện ngay trong màn.
    var toastMessage: String? {
        switch self {
        case .invalidEmail, .otpInvalid: nil
        case .rateLimited: String(localized: "login.rateLimited")
        case .offline: String(localized: "error.offline")
        case .unavailable: String(localized: "login.unavailable")
        case .generic: String(localized: "login.failed")
        case .emailRequiresOtp: String(localized: "login.googleRequiresOtp")
        case .googleFailed: String(localized: "login.googleFailed")
        }
    }
}
