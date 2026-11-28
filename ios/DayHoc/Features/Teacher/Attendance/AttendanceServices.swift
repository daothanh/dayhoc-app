import BackgroundTasks
import Foundation

private struct Unchecked<T>: @unchecked Sendable { let value: T }

/// Gói ngoại tuyến của điểm danh: CSDL mã hóa, SyncEngine, theo dõi mạng và tác vụ nền (US-3.5).
/// Tạo một lần ở `AppEnvironment`; CSDL chỉ mở khi dùng lần đầu.
final class AttendanceServices: Sendable {
    /// Phải khai trong `BGTaskSchedulerPermittedIdentifiers` của Info.plist.
    static let backgroundTaskId = "vn.dayhoc.app.attendance-sync"
    private static let ownerKey = "attendance.owner"

    let store: LocalStore
    let service: any AttendanceService
    let engine: AttendanceSyncEngine
    let connectivity: any ConnectivityMonitor
    private let tokenStore: any TokenStore
    nonisolated(unsafe) private let defaults: UserDefaults // UserDefaults an toàn đa luồng; SDK chưa khai Sendable

    init(api: APIClient, tokenStore: any TokenStore) {
        let defaults = UserDefaults.standard
        self.tokenStore = tokenStore
        self.defaults = defaults
        let store = LocalStore()
        let service = AttendanceAPI(api: api)
        let box = Unchecked(value: defaults)
        let engine = AttendanceSyncEngine(api: service, outbox: store, roster: store) { Self.owner(box.value) }
        self.store = store
        self.service = service
        self.engine = engine
        connectivity = PathConnectivityMonitor { [tokenStore] in
            Task { await Self.sync(engine, tokenStore) }
        }
    }

    // MARK: Chủ dữ liệu

    /// Người dùng và tenant của phiên hiện tại, lưu bền để tác vụ nền (app chưa mở) biết lô nào được gửi.
    var currentOwner: OutboxOwner? { Self.owner(defaults) }

    private static func owner(_ defaults: UserDefaults) -> OutboxOwner? {
        defaults.data(forKey: ownerKey).flatMap { try? JSONDecoder().decode(OutboxOwner.self, from: $0) }
    }

    /// Đăng nhập hoặc đổi tenant: nhớ chủ mới và bỏ dữ liệu của chủ khác.
    func adopt(_ owner: OutboxOwner) async {
        defaults.set(try? JSONEncoder().encode(owner), forKey: Self.ownerKey)
        try? await store.purgeForeign(owner: owner)
    }

    /// Số buổi còn điểm danh chưa đồng bộ (đang chờ hoặc lỗi) của phiên hiện tại, cho hộp thoại đăng xuất.
    func unsyncedSessionCount() async -> Int {
        guard let owner = currentOwner, let all = try? await store.all() else { return 0 }
        return Set(all.filter { $0.owner == owner }.map(\.sessionId)).count
    }

    /// Đăng xuất: hủy tác vụ nền, xóa CSDL và khóa. Hết phiên thì không gọi (giữ lô để gửi sau khi đăng nhập lại).
    func wipe() async {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.backgroundTaskId)
        defaults.removeObject(forKey: Self.ownerKey)
        await store.wipe()
    }

    // MARK: Đồng bộ

    private static func sync(_ engine: AttendanceSyncEngine, _ tokenStore: any TokenStore) async -> FlushResult {
        guard tokenStore.load() != nil else { return .done }
        let result = await engine.flush().result
        if result == .retry { scheduleBackground() }
        return result
    }

    /// Mở app, quay lại màn, có mạng trở lại: gửi ngay; còn dở thì hẹn tác vụ nền.
    func requestSync() {
        let (engine, tokenStore) = (engine, tokenStore)
        Task { _ = await Self.sync(engine, tokenStore) }
    }

    /// Vừa lưu một lô vào outbox: hẹn chạy nền khi có mạng.
    func onEnqueued() { Self.scheduleBackground() }

    static func scheduleBackground() {
        let request = BGProcessingTaskRequest(identifier: backgroundTaskId)
        request.requiresNetworkConnectivity = true
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// Gọi một lần lúc khởi động app, trước khi app chạy xong `didFinishLaunching`.
    func registerBackgroundTask() {
        let (engine, tokenStore) = (engine, tokenStore)
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.backgroundTaskId, using: nil) { task in
            let box = Unchecked(value: task)
            let work = Task {
                let result = await Self.sync(engine, tokenStore)
                box.value.setTaskCompleted(success: result == .done)
            }
            box.value.expirationHandler = { work.cancel() }
        }
    }
}
