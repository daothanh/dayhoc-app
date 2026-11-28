import Foundation
import Network
import os

/// Trạng thái mạng của máy. Test thay bằng bản giả.
protocol ConnectivityMonitor: Sendable {
    var isOnline: Bool { get }
    /// Phát giá trị hiện tại ngay, rồi mỗi khi đổi.
    func updates() -> AsyncStream<Bool>
}

/// `NWPathMonitor`: chỉ coi là online khi đường mạng `.satisfied`. Khi mạng trở lại gọi `onReconnect`.
final class PathConnectivityMonitor: ConnectivityMonitor, @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let state = OSAllocatedUnfairLock(initialState: (online: true, subscribers: [UUID: AsyncStream<Bool>.Continuation]()))

    init(onReconnect: @escaping @Sendable () -> Void) {
        monitor.pathUpdateHandler = { [state] path in
            let on = path.status == .satisfied
            let (changed, subs) = state.withLock { s -> (Bool, [AsyncStream<Bool>.Continuation]) in
                let changed = s.online != on
                s.online = on
                return (changed, Array(s.subscribers.values))
            }
            guard changed else { return }
            subs.forEach { $0.yield(on) }
            if on { onReconnect() }
        }
        monitor.start(queue: DispatchQueue(label: "vn.dayhoc.app.connectivity"))
    }

    var isOnline: Bool { state.withLock { $0.online } }

    func updates() -> AsyncStream<Bool> {
        let (stream, continuation) = AsyncStream.makeStream(of: Bool.self, bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        state.withLock { $0.subscribers[id] = continuation }
        continuation.yield(isOnline)
        continuation.onTermination = { [state] _ in state.withLock { _ = $0.subscribers.removeValue(forKey: id) } }
        return stream
    }
}
