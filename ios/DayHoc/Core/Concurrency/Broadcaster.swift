import Foundation
import os

/// Phát một chuỗi giá trị cho nhiều người nghe. Mỗi lần gọi `stream()` ra một `AsyncStream` riêng, đăng ký đồng bộ
/// trước khi trả về (không mất giá trị gửi ngay sau đó) và tự gỡ khi người nghe dừng. `replayLast`: người nghe mới
/// nhận ngay giá trị gần nhất.
final class Broadcaster<T: Sendable>: Sendable {
    private struct State {
        var subscribers: [UUID: AsyncStream<T>.Continuation] = [:]
        var last: T?
    }

    private let state = OSAllocatedUnfairLock(initialState: State())
    private let replayLast: Bool
    private let buffer: Int

    init(replayLast: Bool = false, buffer: Int = 16) {
        self.replayLast = replayLast
        self.buffer = buffer
    }

    func stream() -> AsyncStream<T> {
        let (stream, continuation) = AsyncStream.makeStream(of: T.self, bufferingPolicy: .bufferingNewest(buffer))
        let id = UUID()
        let replay = state.withLock { s -> T? in
            s.subscribers[id] = continuation
            return replayLast ? s.last : nil
        }
        if let replay { continuation.yield(replay) }
        continuation.onTermination = { [state] _ in state.withLock { _ = $0.subscribers.removeValue(forKey: id) } }
        return stream
    }

    func send(_ value: T) {
        let subs = state.withLock { s -> [AsyncStream<T>.Continuation] in
            s.last = value
            return Array(s.subscribers.values)
        }
        subs.forEach { $0.yield(value) }
    }
}
