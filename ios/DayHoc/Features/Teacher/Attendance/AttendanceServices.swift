import BackgroundTasks
import Foundation
import os

private struct Unchecked<T>: @unchecked Sendable { let value: T }

/// Gói ngoại tuyến của điểm danh: CSDL mã hóa, SyncEngine, theo dõi mạng và tác vụ nền (US-3.5).
/// Tạo một lần ở `AppEnvironment`; CSDL chỉ mở khi dùng lần đầu.
final class AttendanceServices: Sendable {
    /// Phải khai trong `BGTaskSchedulerPermittedIdentifiers` của Info.plist.
    static let backgroundTaskId = "vn.dayhoc.app.attendance-sync"
    private static let ownerKey = "attendance.owner"
    private static let userKey = "attendance.userId"
    private static let registered = OSAllocatedUnfairLock(initialState: false)

    let store: LocalStore
    let service: any AttendanceService
    let engine: AttendanceSyncEngine
    let connectivity: any ConnectivityMonitor
    private let tokenStore: any TokenStore
    nonisolated(unsafe) private let defaults: UserDefaults // UserDefaults an toàn đa luồng; SDK chưa khai Sendable
    private let ownerChanges = Broadcaster<OutboxOwner?>(replayLast: true, buffer: 1)
    /// Số lần gửi đang chạy hoặc đang chờ: nhiều yêu cầu dồn lại, tối đa một lần chạy và một lần chờ.
    private let syncRequests = OSAllocatedUnfairLock(initialState: 0)

    init(api: APIClient, tokenStore: any TokenStore) {
        self.tokenStore = tokenStore
        let defaults = UserDefaults.standard
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
        ownerChanges.send(Self.owner(defaults))
    }

    // MARK: Chủ dữ liệu

    /// Người dùng và tenant của phiên hiện tại, lưu bền để tác vụ nền (app chưa mở) biết lô nào được gửi.
    /// `nil` = chưa xác định: T04 không cho lưu và SyncEngine không gửi gì (đóng kín).
    var currentOwner: OutboxOwner? { Self.owner(defaults) }

    /// Phát chủ hiện tại ngay và mỗi khi đổi (T03 lọc lô theo chủ).
    func ownerStream() -> AsyncStream<OutboxOwner?> { ownerChanges.stream() }

    private static func owner(_ defaults: UserDefaults) -> OutboxOwner? {
        defaults.data(forKey: ownerKey).flatMap { try? JSONDecoder().decode(OutboxOwner.self, from: $0) }
    }

    /// Mã người dùng từ phản hồi đăng nhập (hoặc `auth/me`), lưu bền để gắn chủ cả khi mở app ngoại tuyến.
    var userId: String? { defaults.string(forKey: Self.userKey) }
    func rememberUser(_ id: String) { defaults.set(id, forKey: Self.userKey) }

    /// Gắn chủ (người dùng + tenant đang mở; gắn lại khi đổi tenant). Lô của chủ cũ giữ nguyên trong outbox, không
    /// gửi; chỉ bản chụp danh sách của chủ khác bị xóa.
    func adopt(_ owner: OutboxOwner) async {
        defaults.set(try? JSONEncoder().encode(owner), forKey: Self.ownerKey)
        ownerChanges.send(owner)
        try? await store.purgeForeignRosters(owner: owner)
    }

    /// Hết phiên: bỏ gắn chủ để SyncEngine không gửi gì; lô giữ nguyên chờ chủ đó đăng nhập lại.
    func unbindOwner() {
        defaults.removeObject(forKey: Self.ownerKey)
        defaults.removeObject(forKey: Self.userKey)
        ownerChanges.send(nil)
    }

    /// Số buổi còn điểm danh chưa đồng bộ (đang chờ hoặc lỗi) của phiên hiện tại, cho hộp thoại đăng xuất.
    func unsyncedSessionCount() async -> Int {
        guard let owner = currentOwner, let all = try? await store.all() else { return 0 }
        return Set(all.filter { $0.owner == owner }.map(\.sessionId)).count
    }

    /// Đăng xuất: hủy tác vụ nền, đợi lần gửi đang chạy xong rồi xóa CSDL và khóa. Bỏ gắn chủ trong cùng khóa nên
    /// lần gửi sau thấy chưa có chủ và không mở lại CSDL. Hết phiên thì không gọi (giữ lô).
    func wipe() async {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.backgroundTaskId)
        let store = store
        await engine.exclusive { [self] in
            unbindOwner()
            await store.wipe()
        }
    }

    // MARK: Đồng bộ

    private static func sync(_ engine: AttendanceSyncEngine, _ tokenStore: any TokenStore) async -> FlushResult {
        guard tokenStore.load() != nil else { return .done }
        let result = (try? await engine.flush().result) ?? .retry
        if result == .retry { scheduleBackground() }
        return result
    }

    /// Mở app, quay lại màn, có mạng trở lại: gửi ngay; còn dở thì hẹn tác vụ nền. Đã có một lần đang chờ thì không
    /// xếp thêm (tối đa một chạy + một chờ).
    func requestSync() {
        let admitted = syncRequests.withLock { n -> Bool in
            if n >= 2 { return false }
            n += 1
            return true
        }
        guard admitted else { return }
        let (engine, tokenStore, requests) = (engine, tokenStore, syncRequests)
        Task {
            _ = await Self.sync(engine, tokenStore)
            requests.withLock { $0 -= 1 }
        }
    }

    /// Vừa lưu một lô vào outbox: hẹn chạy nền khi có mạng và thử gửi ngay (cờ ngoại tuyến có thể đã cũ).
    func onEnqueued() {
        Self.scheduleBackground()
        requestSync()
    }

    static func scheduleBackground() {
        let request = BGProcessingTaskRequest(identifier: backgroundTaskId)
        request.requiresNetworkConnectivity = true
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// Gọi một lần lúc khởi động app, trước khi app chạy xong `didFinishLaunching`; gọi lần hai bị bỏ qua
    /// (đăng ký trùng làm app thoát).
    func registerBackgroundTask() {
        guard Self.registered.withLock({ done -> Bool in
            if done { return false }
            done = true
            return true
        }) else { return }
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
