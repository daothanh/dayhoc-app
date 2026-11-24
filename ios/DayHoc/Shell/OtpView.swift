import SwiftUI

/// Số ô mã OTP, khớp `LoginModel` (chỉ nhận tối đa 6 chữ số).
private let otpCodeLength = 6
/// Chiều cao App Bar Detail theo Figma `Navigation/App Bar` (chưa có token chiều cao).
private let otpAppBarHeight: CGFloat = 56

/// [T01a] Nhập mã OTP (Figma B02): App Bar Detail có nút lùi và tiêu đề, tiêu đề H1, dòng
/// "Mã 6 số đã gửi tới …" kèm "Đổi email", hàng 6 ô mã 44×52 căn giữa, caption đếm ngược và nút
/// "Xác nhận" ghim đáy.
///
/// Một ô nhập trong suốt phủ lên hàng ô để nhận gõ và dán mã; các ô chỉ vẽ ký tự. Ô nhập tự nhận
/// focus khi vào màn nên bàn phím mở sẵn, và tự xác thực khi đủ 6 số vì bàn phím số của iOS không
/// có phím Done như Android.
struct OtpView: View {
    let model: LoginModel

    @Environment(\.dhColors) private var colors
    @FocusState private var codeFieldFocused: Bool

    var body: some View {
        // Vẽ lại mỗi giây cho hai bộ đếm ngược; model chỉ giữ mốc thời gian.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let locked = model.isLocked(at: context.date)
            VStack(spacing: 0) {
                appBar
                content(now: context.date, locked: locked)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar(locked: locked) }
        }
        .dhScreen()
        .toolbar(.hidden, for: .navigationBar)
    }

    /// App Bar Detail (Figma `Navigation/App Bar`, node 43:5193): nền `surface`, nút lùi Solar,
    /// tiêu đề căn giữa, kẻ dưới 1.5px.
    private var appBar: some View {
        VStack(spacing: 0) {
            ZStack {
                // Figma Navigation/Title: 17/24 SemiBold, giãn chữ -0.01em.
                Text("otp.title")
                    .font(.system(size: 17, weight: .semibold))
                    .kerning(-0.17)
                    .foregroundStyle(colors.onSurface)
                HStack {
                    DhBackButton(action: model.changeEmail)
                    Spacer(minLength: 0)
                }
            }
            .frame(height: otpAppBarHeight)
            .padding(.horizontal, TeachHubTokens.Dimens.spacingInline)
            .background(colors.surface)
            Rectangle().fill(colors.outlineVariant).frame(height: 1.5)
        }
    }

    private func content(now: Date, locked: Bool) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingPage) {
                // Figma DayHoc/H1: 20/28 SemiBold, giãn chữ -0.015em.
                Text("otp.heading")
                    .font(.system(size: 20, weight: .semibold))
                    .kerning(-0.3)
                sentToRow(locked: locked)
                otpCells(locked: locked)
                if locked {
                    lockedBanner
                } else if let failure = model.failure, case .otpInvalid = failure {
                    // Server có thể trả kèm số lần còn lại hoặc không; `message` phủ cả hai.
                    Text(failure.message)
                        .font(.footnote)
                        .foregroundStyle(colors.error)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .accessibilityIdentifier("otp.code.error")
                }
                resendNotice(now: now, locked: locked)
            }
            .padding(.top, TeachHubTokens.Dimens.spacingPageLarge)
            .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
            .padding(.bottom, TeachHubTokens.Dimens.spacingPage)
        }
    }

    /// Dòng "Mã 6 số đã gửi tới …" kèm nút "Đổi email" (Figma B02).
    private func sentToRow(locked: Bool) -> some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            Text("otp.sentTo \(model.sentTo ?? "")")
                .font(.subheadline)
                .foregroundStyle(colors.onSurfaceVariant)
            Button(action: model.changeEmail) {
                Text("login.changeEmail")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(colors.secondary)
                    .frame(minHeight: 44)
                    .padding(.horizontal, TeachHubTokens.Dimens.spacingControl)
            }
            .buttonStyle(.plain)
            .disabled(locked || model.isVerifying)
            Spacer(minLength: 0)
        }
    }

    /// Hàng 6 ô mã (Figma B02): 6 ô 44×52 căn giữa, phủ một ô nhập trong suốt để nhận gõ và dán mã.
    /// Ô nhập ẩn mang định danh `otp.code` để UI test tìm và gõ mã.
    private func otpCells(locked: Bool) -> some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            ForEach(0..<otpCodeLength, id: \.self) { index in
                DhOtpCell(char: char(at: index), state: cellState(at: index, locked: locked))
            }
        }
        .frame(maxWidth: .infinity)
        .background {
            TextField("", text: codeBinding)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($codeFieldFocused)
                .foregroundStyle(.clear)
                .tint(.clear)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .disabled(locked || model.isVerifying)
                .opacity(0.02)
                .accessibilityIdentifier("otp.code")
        }
        // Figma B02: ô đầu ở trạng thái Focus kèm con trỏ, nên xin focus và mở bàn phím ngay khi vào màn.
        .onAppear { codeFieldFocused = !locked }
    }

    /// Caption đếm ngược "Gửi lại mã sau 5:00"; hết giờ đổi thành nút "Gửi lại mã" (Figma B02).
    private func resendNotice(now: Date, locked: Bool) -> some View {
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            if locked {
                Text("otp.retryIn \(Self.mmss(model.secondsUntilUnlock(at: now)))")
                    .font(.footnote)
                    .foregroundStyle(colors.onSurfaceVariant)
            } else if model.secondsUntilResend(at: now) > 0 {
                Text("otp.resendIn \(Self.mmss(model.secondsUntilResend(at: now)))")
                    .font(.footnote)
                    .foregroundStyle(colors.onSurfaceVariant)
            } else {
                Button {
                    Task { await model.resend() }
                } label: {
                    Text("login.resend")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(colors.secondary)
                        .frame(minHeight: 44)
                        .padding(.horizontal, TeachHubTokens.Dimens.spacingControl)
                }
                .buttonStyle(.plain)
                .disabled(model.isSending)
            }
            Spacer(minLength: 0)
        }
    }

    /// Banner lỗi khi bị tạm khóa (Figma B24): nền `surface`, viền `error` 2.
    private var lockedBanner: some View {
        let shape = RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.cardRadius)
        return VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingInline) {
            Text("otp.lockedTitle")
                .font(.subheadline.bold())
                .foregroundStyle(colors.onErrorContainer)
                .accessibilityIdentifier("otp.lockedTitle")
            Text("otp.lockedBody")
                .font(.footnote)
                .foregroundStyle(colors.onSurfaceVariant)
        }
        .padding(TeachHubTokens.Dimens.spacingContent)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.surface, in: shape)
        .overlay(shape.stroke(colors.error, lineWidth: TeachHubTokens.Dimens.focusRingWidth))
    }

    /// Bottom Action Bar (Figma 43:5237): nền `surface`, kẻ trên 1.5px, chừa chỗ cho bàn phím.
    private func bottomBar(locked: Bool) -> some View {
        VStack(spacing: 0) {
            Rectangle().fill(colors.outlineVariant).frame(height: 1.5)
            DhPrimaryButton(
                title: "otp.confirm",
                loadingTitle: "otp.verifying",
                isLoading: model.isVerifying,
                identifier: "otp.confirm",
                action: verify
            )
            .disabled(locked || model.code.count < otpCodeLength)
            .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
            .padding(.top, TeachHubTokens.Dimens.spacingContent)
            .padding(.bottom, TeachHubTokens.Dimens.spacingPage)
        }
        .background(colors.surface)
    }

    /// Chỉ giữ chữ số, tối đa 6; đủ 6 số thì tự xác thực.
    private var codeBinding: Binding<String> {
        Binding(
            get: { model.code },
            set: { value in
                let wasComplete = model.code.count == otpCodeLength
                model.enterCode(value)
                if !wasComplete, model.code.count == otpCodeLength { verify() }
            }
        )
    }

    private func char(at index: Int) -> Character? {
        let digits = Array(model.code)
        return index < digits.count ? digits[index] : nil
    }

    private func cellState(at index: Int, locked: Bool) -> DhOtpCellState {
        if locked { return .locked }
        if let failure = model.failure, case .otpInvalid = failure { return .error }
        if index < model.code.count { return .filled }
        if codeFieldFocused, index == model.code.count { return .focus }
        return .empty
    }

    private func verify() {
        Task { await model.verify() }
    }

    private static func mmss(_ seconds: Int) -> String {
        Duration.seconds(seconds).formatted(.time(pattern: .minuteSecond))
    }
}
