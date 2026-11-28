import Foundation

/// Chủ của dữ liệu ngoại tuyến: chỉ lô của đúng người dùng và tenant này mới được gửi (US-3.5).
struct OutboxOwner: Codable, Equatable, Sendable {
    let userId: String
    let tenantId: String
}

/// Một mutation đã dựng xong, giữ nguyên `clientMutationId` và `clientUpdatedAt` khi gửi lại.
struct OutboxMutation: Codable, Equatable, Sendable {
    let clientMutationId: String
    let studentId: String
    let status: String
    let note: String?
    let clientUpdatedAt: String

    enum CodingKeys: String, CodingKey { case clientMutationId, studentId, status, note, clientUpdatedAt }

    /// `note` luôn có trong body, null khi không có (đúng hợp đồng `nullable`).
    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(clientMutationId, forKey: .clientMutationId)
        try c.encode(studentId, forKey: .studentId)
        try c.encode(status, forKey: .status)
        try c.encode(note, forKey: .note)
        try c.encode(clientUpdatedAt, forKey: .clientUpdatedAt)
    }
}

enum OutboxState: String, Codable, Sendable { case pending, failed }

/// Một lô điểm danh của một buổi chưa gửi được. Lưu nguyên body đã dựng lần đầu (gồm `deviceId`, `deviceTime`):
/// backend băm cả body vào khóa idempotency nên phát lại phải y nguyên. `failed` = server từ chối vĩnh viễn,
/// giữ lại để giáo viên thấy và gửi lại (không tự xóa).
struct OutboxBatch: Equatable, Sendable {
    let idempotencyKey: String
    let sessionId: String
    let owner: OutboxOwner
    let deviceId: String
    let deviceTime: String
    let mutations: [OutboxMutation]
    let markSessionTaught: Bool
    let createdAt: Int64
    var attempts = 0
    var state = OutboxState.pending
    var errorCode: String?

    var request: SyncRequestDTO {
        SyncRequestDTO(deviceId: deviceId, deviceTime: deviceTime, sessionId: sessionId,
                       markSessionTaught: markSessionTaught, mutations: mutations)
    }
}

/// Outbox lô điểm danh chưa gửi (Technical Spec 6.4). Bản thật là SQLCipher; test dùng bản trong bộ nhớ.
protocol OutboxStore: Sendable {
    /// Mọi lô theo thứ tự tạo.
    func all() async throws -> [OutboxBatch]
    func enqueue(_ batch: OutboxBatch) async throws
    func remove(_ key: String) async throws
    func addAttempt(_ key: String) async throws
    func markFailed(_ key: String, code: String) async throws
    /// "Gửi lại": lô lỗi của buổi về chờ gửi, đếm lại từ 0.
    func retryFailed(sessionId: String) async throws
    /// Phát danh sách hiện tại ngay, rồi mỗi khi outbox đổi.
    func batches() -> AsyncStream<[OutboxBatch]>
}

/// Bản chụp danh sách học sinh của buổi (JSON của `SessionAttendanceDTO`) để mở T04 khi mất mạng.
protocol RosterCache: Sendable {
    func get(sessionId: String, owner: OutboxOwner) async -> String?
    func put(sessionId: String, owner: OutboxOwner, json: String) async
    func remove(sessionId: String) async
}

/// UUID cố định mỗi lần cài đặt, gửi kèm mọi lô đồng bộ.
protocol DeviceIdProvider: Sendable {
    func deviceId() -> String
}
