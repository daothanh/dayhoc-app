import UIKit
@preconcurrency import GoogleSignIn

/// Dữ liệu Google Sign-In trả về: ID token gửi lên backend và email tài khoản (nếu SDK có).
struct GoogleCredential: Sendable {
    let idToken: String
    let email: String?
}

/// Cổng lấy ID token Google. Tách thành protocol để `LoginModel` test được mà không cần SDK hay UI.
@MainActor
protocol GoogleIdTokenProvider: Sendable {
    /// Mở trình chọn tài khoản; người dùng hủy thì ném `GoogleSignInError.canceled`.
    func credential() async throws -> GoogleCredential
}

enum GoogleSignInError: Error, Equatable {
    /// Người dùng đóng trình chọn tài khoản: không hiện lỗi.
    case canceled
    /// Không lấy được ID token (thiếu cấu hình, SDK trả rỗng…).
    case unavailable
}

/// Bản chạy thật của `GoogleIdTokenProvider` dùng SDK GoogleSignIn-iOS.
/// Client ID đọc từ Info.plist (`GIDClientID`, `GIDServerClientID`) do `Config/*.xcconfig` đặt.
@MainActor
final class GoogleSignInProvider: GoogleIdTokenProvider {
    func credential() async throws -> GoogleCredential {
        guard let presenter = Self.topViewController() else { throw GoogleSignInError.unavailable }
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
            guard let idToken = result.user.idToken?.tokenString else { throw GoogleSignInError.unavailable }
            return GoogleCredential(idToken: idToken, email: result.user.profile?.email)
        } catch let error as NSError
            where error.domain == kGIDSignInErrorDomain && error.code == GIDSignInError.canceled.rawValue {
            throw GoogleSignInError.canceled
        } catch {
            throw GoogleSignInError.unavailable
        }
    }

    /// ViewController đang hiển thị để SDK bắc cầu sang trình chọn tài khoản.
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

/// Nhận URL quay lại từ Google Sign-In; gọi trong `onOpenURL` của app.
enum GoogleSignInSupport {
    @MainActor
    static func handle(_ url: URL) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }
}
