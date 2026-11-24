import SwiftUI

/// Gốc giao diện: đổi màn theo `AppModel.route`; bản cài quá cũ thì chặn bằng màn cập nhật.
struct RootView: View {
    let model: AppModel
    private var api: APIClient { model.api }

    var body: some View {
        content
            .overlay(alignment: .bottom) { Toast(text: Binding(get: { model.toast }, set: { model.toast = $0 })) }
            .sheet(item: Binding(get: { model.addingRole }, set: { model.addingRole = $0 })) { picker in
                RolePickerView(model: picker, api: api) { model.addingRole = nil }
            }
            .dhTheme()
            .task { await model.start() }
    }

    @ViewBuilder
    private var content: some View {
        if model.updateRequired {
            UpdateRequiredView()
        } else {
            switch model.route {
            case .launching:
                SplashView(failed: nil) {}
            case .failed(let offline):
                SplashView(failed: offline ? "error.offline" : "splash.loadFailed") {
                    Task { await model.retry() }
                }
            case .login(let login):
                LoginView(model: login)
            case .rolePicker(let picker):
                RolePickerView(model: picker, api: api)
            case .teacher:
                TeacherSpaceView(app: model)
            case .onboarding(let onboarding):
                OnboardingView(model: onboarding, onFinished: model.finishOnboarding, onExit: model.exitOnboarding)
            case .parent, .student:
                PlaceholderSpaceView(app: model)
            }
        }
    }
}

/// [T00s] Splash (Figma B00a): nền lime, tên app, khẩu hiệu; lỗi tải vai trò thì có nút thử lại.
private struct SplashView: View {
    let failed: LocalizedStringKey?
    let retry: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            Text("app.name").font(.largeTitle.bold())
            Text("login.slogan")
            Text("login.tagline").multilineTextAlignment(.center)
            if let failed {
                Text(failed)
                    .multilineTextAlignment(.center)
                    .padding(.top, TeachHubTokens.Dimens.spacingSection)
                // Nền lime: nút viền ink để tách khỏi nền.
                Button(action: retry) {
                    Text("retry")
                        .font(.body.weight(.semibold))
                        .frame(minWidth: 160, minHeight: 48)
                        .overlay(Capsule().stroke(colors.onPrimary, lineWidth: 2))
                }
                .buttonStyle(.plain)
                .padding(.top, TeachHubTokens.Dimens.spacingContent)
            }
        }
        .foregroundStyle(colors.onPrimary)
        .padding(TeachHubTokens.Dimens.spacingSection)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(colors.primary.ignoresSafeArea())
    }
}

/// Bản cài thấp hơn `minSupportedVersion` (Technical Spec 5.1): chặn mọi màn.
/// shortcut: chưa có nút mở App Store vì app chưa lên store; thêm khi phát hành.
private struct UpdateRequiredView: View {
    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            Text("update.title").font(.title2.bold()).multilineTextAlignment(.center)
            Text("update.body").foregroundStyle(colors.onSurfaceVariant).multilineTextAlignment(.center)
        }
        .padding(TeachHubTokens.Dimens.spacingSection)
        .dhScreen()
    }
}

/// Toast ngắn ở đáy màn, tự ẩn sau 2,5 giây.
struct Toast: View {
    @Binding var text: String?

    @Environment(\.dhColors) private var colors

    var body: some View {
        if let text {
            Text(text)
                .font(.footnote.weight(.medium))
                .foregroundStyle(colors.onInverseSurface)
                .padding(.horizontal, TeachHubTokens.Dimens.spacingContent)
                .padding(.vertical, TeachHubTokens.Dimens.spacingControl)
                .background(colors.onSurface, in: Capsule())
                .padding(.bottom, 96)
                .transition(.opacity)
                .task(id: text) {
                    try? await Task.sleep(for: .seconds(2.5))
                    self.text = nil
                }
        }
    }
}
