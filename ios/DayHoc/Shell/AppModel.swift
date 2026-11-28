import Foundation
import Observation

/// Điều hướng gốc của app: khởi động, đăng nhập, chọn vai trò, không gian vai trò, onboarding, hết phiên.
@Observable @MainActor
final class AppModel {
    enum Route {
        case launching
        case login(LoginModel)
        /// [T01c] Chưa có vai trò đang mở.
        case rolePicker(RolePickerModel)
        case teacher
        /// [T02] Giáo viên còn bước onboarding dở.
        case onboarding(OnboardingModel)
        case parent
        case student
        /// Không tải được vai trò; `offline` để chọn câu báo.
        case failed(offline: Bool)
    }

    private(set) var route: Route = .launching
    /// Bản cài thấp hơn `minSupportedVersion` (Technical Spec 5.1): chặn mọi màn.
    private(set) var updateRequired = false
    /// "Thêm vai trò" mở từ T00: T01c hiện dạng sheet trên không gian đang mở.
    var addingRole: RolePickerModel?
    /// Toast ngắn ("Đang ở vai trò …").
    var toast: String?

    let memberships: MembershipStore
    private let env: AppEnvironment
    var api: APIClient { env.api }
    var attendance: AttendanceServices { env.attendance }
    /// [T03] của phiên hiện tại: tạo một lần cho mỗi phiên (đăng nhập lại thì tạo mới, không giữ dữ liệu của người cũ).
    private(set) var today: TodayModel

    /// Vòng nghe hết phiên do model giữ: nếu để `.task` của view giữ, view dựng lại sẽ hủy task
    /// và `AsyncStream` kết thúc, từ đó không còn nhận sự kiện nào.
    @ObservationIgnored private var expiryListener: Task<Void, Never>?

    init(env: AppEnvironment) {
        self.env = env
        memberships = MembershipStore(api: env.api)
        today = Self.makeToday(env)
        expiryListener = Task { [weak self, stream = env.api.sessionExpired] in
            for await _ in stream { self?.showLogin() }
        }
    }

    /// Có token thì tải vai trò rồi mở đúng nơi, không thì vào Đăng nhập.
    func start() async {
        // Không chờ, lỗi thì bỏ qua: app phải mở được khi mất mạng (NFR-17); lần mở sau kiểm lại.
        Task { await checkVersion() }
        await enter()
    }

    func retry() async { await enter() }

    func logout() async {
        await env.auth.logout()
        // Đăng xuất thì xóa dữ liệu ngoại tuyến (CSDL, khóa, chủ); hết phiên thì giữ lô để gửi sau khi đăng nhập lại.
        await env.attendance.wipe()
        showLogin()
    }

    /// [T00] Đổi vai trò: không đăng nhập lại, chỉ đổi không gian.
    func switchTo(_ membership: MembershipDTO) async {
        memberships.select(membership)
        toast = String(localized: "role.switchedTo \(String(localized: Roles.name(membership.role)))")
        await open(membership.role)
    }

    func addRole() {
        addingRole = RolePickerModel(store: memberships) { [weak self] m in
            self?.addingRole = nil
            await self?.open(m.role)
        }
    }

    /// Banner ở T03: mở lại onboarding đúng bước đã bỏ qua.
    func openOnboarding(startingAt step: OnboardingStep) {
        guard case .teacher = route else { return }
        route = .onboarding(OnboardingModel(api: OnboardingAPI(api: env.api), startStep: step))
    }

    func finishOnboarding() {
        // Phiên có thể vừa hết (route đã về Đăng nhập): callback đến muộn không được mở lại không gian.
        guard case .onboarding = route else { return }
        route = .teacher
    }

    private func enter() async {
        guard env.auth.hasSession else { return showLogin() }
        route = .launching
        do {
            try await memberships.load()
        } catch {
            // shortcut: chưa cache vai trò nên mất mạng lúc mở app thì báo lỗi; làm cache ở NA-07 (ngoại tuyến).
            return fail(error)
        }
        if let active = memberships.active {
            await open(active.role)
        } else {
            route = .rolePicker(RolePickerModel(store: memberships) { [weak self] m in await self?.open(m.role) })
        }
    }

    private func open(_ role: String) async {
        switch role {
        case Roles.teacher:
            // Gắn chủ dữ liệu trước khi vào không gian giáo viên (và mỗi khi đổi tenant).
            await bindAttendanceOwner(tenantId: memberships.active?.tenantId)
            route = .teacher
            await openOnboardingIfPending()
        case Roles.parent: route = .parent
        default: route = .student
        }
    }

    /// Chủ của dữ liệu ngoại tuyến = người dùng (lưu bền từ lúc đăng nhập; chưa có thì hỏi `auth/me`) + tenant đang mở.
    /// Chưa xác định được thì không gắn: T04 không cho lưu và SyncEngine không gửi gì (đóng kín khi chưa biết chủ).
    private func bindAttendanceOwner(tenantId: String?) async {
        guard let tenantId else { return }
        var userId = env.attendance.userId
        if userId == nil, let me = try? await env.auth.me() {
            env.attendance.rememberUser(me.id)
            userId = me.id
        }
        guard let userId else { return }
        await env.attendance.adopt(OutboxOwner(userId: userId, tenantId: tenantId))
    }

    /// Chủ hiện tại; chưa gắn thì thử gắn lại theo vai trò đang mở (nút "Thử lại" của T04).
    func currentOwner() async -> OutboxOwner? {
        if let owner = env.attendance.currentOwner { return owner }
        await bindAttendanceOwner(tenantId: memberships.active?.tenantId)
        return env.attendance.currentOwner
    }

    private static func makeToday(_ env: AppEnvironment) -> TodayModel {
        TodayModel(source: TodayAPI(api: env.api), outbox: env.attendance.store.batches(), owner: env.attendance.ownerStream())
    }

    /// Giáo viên mới hoặc còn bước onboarding dở: vào onboarding, tiếp đúng bước dở (US-0.2 AC1, AC3).
    private func openOnboardingIfPending() async {
        let api = OnboardingAPI(api: env.api)
        // Lỗi mạng: vào màn chính, lần mở sau kiểm lại.
        guard (try? await api.get())?.current != nil, case .teacher = route else { return }
        route = .onboarding(OnboardingModel(api: api))
    }

    private func checkVersion() async {
        guard let config: AppConfigDTO = try? await env.api.request("GET", "app-config", authorized: false) else { return }
        let current = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        updateRequired = isVersionBelow(current, config.minSupportedVersion.ios)
    }

    private func fail(_ error: any Error) {
        // Hết phiên thì APIClient đã xóa token và phát sự kiện: về Đăng nhập.
        guard env.auth.hasSession else { return showLogin() }
        let kind = (error as? APIError)?.kind
        route = .failed(offline: kind == .offline || kind == .timeout)
    }

    /// Về Đăng nhập và quên mọi trạng thái của phiên trên máy.
    private func showLogin() {
        // Học phí Trọn khóa đang chờ thì giữ: chỉ có id lớp và số tiền, đăng nhập lại vẫn ghi được ở bước 3.
        memberships.clear()
        addingRole = nil
        // Hết phiên hay đăng xuất: bỏ gắn chủ để SyncEngine không gửi gì; lô giữ nguyên chờ chủ đó đăng nhập lại.
        env.attendance.unbindOwner()
        today = Self.makeToday(env)
        if case .login = route { return }
        route = .login(LoginModel(auth: env.auth, google: env.google) { [weak self] user in
            // Đăng nhập xong: tải vai trò rồi mở đúng nơi.
            self?.env.attendance.rememberUser(user.id)
            await self?.enter()
        })
    }
}
