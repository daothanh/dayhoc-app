import Foundation

/// Các phụ thuộc dùng chung của app, tạo một lần ở `DayHocApp` và truyền qua init (ADR Mục 3).
struct AppEnvironment: Sendable {
    let api: APIClient
    let auth: AuthRepository
    /// Luồng Google Sign-In; `nil` ở test và UI test vì không dựng SDK/UI.
    let google: (any GoogleIdTokenProvider)?

    init(api: APIClient, tokenStore: any TokenStore, google: (any GoogleIdTokenProvider)? = nil) {
        self.api = api
        auth = AuthRepository(api: api, tokenStore: tokenStore)
        self.google = google
    }

    /// Bản chạy thật: `API_URL` lấy từ Info.plist (giá trị đặt trong `Config/*.xcconfig`).
    @MainActor
    static func live() -> AppEnvironment {
        let info = Bundle.main.infoDictionary ?? [:]
        guard let raw = info["API_URL"] as? String, let baseURL = URL(string: raw) else {
            fatalError("Thiếu API_URL trong Info.plist, xem Config/*.xcconfig")
        }
        let version = info["CFBundleShortVersionString"] as? String ?? "0"
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        let tokenStore = KeychainTokenStore()
        let api = APIClient(
            baseURL: baseURL,
            session: URLSession(configuration: configuration),
            tokenStore: tokenStore,
            clientInfo: "teachhub-app/\(version) (ios)"
        )
        return AppEnvironment(api: api, tokenStore: tokenStore, google: GoogleSignInProvider())
    }
}
