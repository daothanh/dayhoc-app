import Foundation

/// Hook tối thiểu cho UI test hermetic: đọc launch argument `-uiTestMode <mode>` để app khởi động
/// với máy chủ giả chạy trong tiến trình và token trong bộ nhớ, không gọi backend thật, không chạm Keychain.
/// Không có launch argument thì mọi thứ trả về `nil` và app chạy như bình thường.
enum UITestLaunch {
    enum Mode: String {
        case login
        case otpInvalid = "otp-invalid"
        case otpLocked = "otp-locked"
        case rolePicker = "role-picker"
        case onboarding
        case onboardingStudents = "onboarding-students"
        case onboardingSchedule = "onboarding-schedule"
    }

    /// `nil` khi chạy app thật.
    static let mode: Mode? = parse()

    private static func parse() -> Mode? {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "-uiTestMode"), index + 1 < args.count else { return nil }
        return Mode(rawValue: args[index + 1])
    }

    /// Xóa trạng thái đã lưu trên máy (vai trò đang mở, học phí Trọn khóa đang chờ) để mỗi bài UI test
    /// khởi động sạch, không phụ thuộc bài chạy trước.
    static func resetPersistedState() {
        guard let domain = Bundle.main.bundleIdentifier else { return }
        UserDefaults.standard.removePersistentDomain(forName: domain)
    }
}

/// Token trong bộ nhớ cho UI test: thay Keychain, an toàn về luồng.
final class UITestTokenStore: TokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var tokens: Tokens?

    init(_ tokens: Tokens? = nil) { self.tokens = tokens }

    func load() -> Tokens? { lock.withLock { tokens } }
    func save(_ tokens: Tokens) throws { lock.withLock { self.tokens = tokens } }
    func clear() { lock.withLock { tokens = nil } }
}

extension AppEnvironment {
    /// Môi trường cho UI test: `URLSession` dùng `UITestMockURLProtocol`, token trong bộ nhớ.
    /// Có phiên sẵn cho các màn cần đi qua cửa đăng nhập (chọn vai trò, onboarding).
    static func uiTest(mode: UITestLaunch.Mode) -> AppEnvironment {
        UITestBackend.shared.configure(for: mode)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UITestMockURLProtocol.self]
        let tokenStore = UITestTokenStore()
        switch mode {
        case .rolePicker, .onboarding, .onboardingStudents, .onboardingSchedule:
            try? tokenStore.save(Tokens(accessToken: "ui-test-access", refreshToken: "ui-test-refresh"))
        case .login, .otpInvalid, .otpLocked:
            break
        }
        let api = APIClient(
            baseURL: URL(string: "https://uitest.dayhoc.local/v1")!,
            session: URLSession(configuration: configuration),
            tokenStore: tokenStore,
            clientInfo: "teachhub-app/uitest (ios)"
        )
        return AppEnvironment(api: api, tokenStore: tokenStore)
    }
}

/// Chặn mọi request của app khi chạy UI test và trả phản hồi giả từ `UITestBackend`.
final class UITestMockURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let stub = UITestBackend.shared.respond(to: request, mode: UITestLaunch.mode ?? .login)
        let url = request.url ?? URL(string: "https://uitest.dayhoc.local")!
        let response = HTTPURLResponse(url: url, statusCode: stub.status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(stub.json.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

/// Phản hồi giả.
struct UITestStub: Sendable {
    var status: Int
    var json = ""

    static let noContent = UITestStub(status: 204)

    static func ok(_ json: String) -> UITestStub { UITestStub(status: 200, json: json) }

    static func created(_ json: String) -> UITestStub { UITestStub(status: 201, json: json) }

    static func error(_ status: Int, _ code: String, retryAfterSec: Int? = nil) -> UITestStub {
        let details = retryAfterSec.map { #","details":{"retryAfterSec":\#($0)}"# } ?? ""
        return UITestStub(status: status, json: #"{"code":"\#(code)","message":"","requestId":"ui-test"\#(details)}"#)
    }
}

/// Máy chủ giả trong tiến trình app cho UI test. Trạng thái onboarding tiến theo từng bước
/// giống backend thật để một bài test có thể đi hết 3 bước.
final class UITestBackend: @unchecked Sendable {
    static let shared = UITestBackend()

    private static let order = ["create_class", "add_students", "set_schedule"]
    private static let oneClass = #"{"id":"c1","name":"Toán 9A","startDate":"2026-11-23"}"#
    private static let userJSON = #"{"id":"u1","email":"co.lan@example.com","displayName":"Cô Lan","permissions":[]}"#

    private let lock = NSLock()
    private var onboardingCurrent: String? = "create_class"
    private var classes: [String] = []

    /// Đặt trạng thái ban đầu theo kịch bản được chọn qua launch argument.
    func configure(for mode: UITestLaunch.Mode) {
        lock.withLock {
            switch mode {
            case .onboardingStudents:
                onboardingCurrent = "add_students"
                classes = [Self.oneClass]
            case .onboardingSchedule:
                onboardingCurrent = "set_schedule"
                classes = [Self.oneClass]
            default:
                onboardingCurrent = "create_class"
                classes = []
            }
        }
    }

    func respond(to request: URLRequest, mode: UITestLaunch.Mode) -> UITestStub {
        let method = request.httpMethod ?? "GET"
        let path = request.url?.path ?? ""
        switch (method, path) {
        case (_, "/v1/app-config"):
            return .ok(#"{"minSupportedVersion":{"android":"0.0.0","ios":"0.0.0"}}"#)
        case (_, "/v1/me/memberships"):
            return .ok(memberships(for: mode))
        case ("POST", "/v1/auth/otp/send"):
            return .noContent
        case ("POST", "/v1/auth/login"):
            return login(for: mode)
        case ("GET", "/v1/onboarding"):
            return .ok(lock.withLock { Self.onboarding(current: onboardingCurrent) })
        case ("PATCH", let p) where p.hasPrefix("/v1/onboarding/steps/"):
            let next = Self.nextStep(after: String(p.dropFirst("/v1/onboarding/steps/".count)))
            lock.withLock { onboardingCurrent = next }
            return .ok(Self.onboarding(current: next))
        case ("GET", "/v1/classes"):
            return .ok(lock.withLock { #"{"items":[\#(classes.joined(separator: ","))]}"# })
        case ("POST", "/v1/classes"):
            return lock.withLock {
                if classes.isEmpty { classes = [Self.oneClass] }
                return UITestStub(status: 201, json: "{}")
            }
        case ("POST", let p) where p.hasSuffix("/enrollments"):
            return .noContent
        case ("PUT", let p) where p.hasSuffix("/schedule-rules"):
            return .noContent
        case ("PATCH", let p) where p.hasPrefix("/v1/classes/"):
            return .noContent
        case ("GET", "/v1/students"):
            return .ok(#"{"items":[]}"#)
        case ("POST", "/v1/students"):
            return .created(#"{"id":"s1","fullName":"Nguyễn Văn Nam","guardians":[]}"#)
        default:
            return .ok("{}")
        }
    }

    private func login(for mode: UITestLaunch.Mode) -> UITestStub {
        switch mode {
        case .otpInvalid: return .error(422, "OTP_INVALID")
        case .otpLocked: return .error(429, "OTP_LOCKED", retryAfterSec: 900)
        default: return .ok(#"{"access_token":"a","refresh_token":"r","user":\#(Self.userJSON)}"#)
        }
    }

    private func memberships(for mode: UITestLaunch.Mode) -> String {
        switch mode {
        case .rolePicker:
            return #"{"items":[{"id":"m1","role":"tenant_owner","label":"Giáo viên · Cô Lan","tenantId":"t1"},{"id":"m2","role":"parent","label":"Phụ huynh · Cô Lan"}],"lastMembershipId":null}"#
        default:
            return #"{"items":[{"id":"m1","role":"tenant_owner","label":"Giáo viên · Cô Lan","tenantId":"t1"}],"lastMembershipId":"m1"}"#
        }
    }

    private static func onboarding(current: String?) -> String {
        let steps = order.map { step -> String in
            let done = current.map { order.firstIndex(of: step)! < order.firstIndex(of: $0)! } ?? true
            let status = done ? "done" : "pending"
            return #"{"step":"\#(step)","status":"\#(status)"}"#
        }
        let value = current.map { #""\#($0)""# } ?? "null"
        return #"{"steps":[\#(steps.joined(separator: ","))],"currentStep":\#(value)}"#
    }

    private static func nextStep(after step: String) -> String? {
        order.firstIndex(of: step).flatMap { $0 + 1 < order.count ? order[$0 + 1] : nil }
    }
}
