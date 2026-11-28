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
    private let changes = Broadcaster<Bool>(replayLast: false, buffer: 1)
    /// `nil` cho tới khi hệ thống báo đường mạng lần đầu; không mặc định online.
    private let known = OSAllocatedUnfairLock<Bool?>(initialState: nil)

    init(onReconnect: @escaping @Sendable () -> Void) {
        monitor.pathUpdateHandler = { [known, changes] path in
            let on = path.status == .satisfied
            let previous = known.withLock { k -> Bool? in
                defer { k = on }
                return k
            }
            guard previous != on else { return }
            changes.send(on)
            if on, previous == false { onReconnect() }
        }
        monitor.start(queue: DispatchQueue(label: "vn.dayhoc.app.connectivity"))
    }

    /// Đường mạng hiện tại của hệ thống (`.satisfied`); chưa có báo cáo đầu thì đọc thẳng `currentPath`.
    var isOnline: Bool { known.withLock { $0 } ?? (monitor.currentPath.status == .satisfied) }

    func updates() -> AsyncStream<Bool> {
        let stream = changes.stream()
        // Người nghe mới nhận ngay trạng thái hiện tại, rồi các thay đổi sau.
        let (out, continuation) = AsyncStream.makeStream(of: Bool.self, bufferingPolicy: .bufferingNewest(1))
        continuation.yield(isOnline)
        let forwarding = Task {
            for await on in stream { continuation.yield(on) }
            continuation.finish()
        }
        continuation.onTermination = { _ in forwarding.cancel() }
        return out
    }
}
