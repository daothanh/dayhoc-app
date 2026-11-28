import Foundation

// DTO viết tay theo teachhub-backend/contracts/openapi.json (attendance_get, attendance_sync, Class).
// Trường tùy chọn của server có giá trị mặc định nil; trường lạ bị bỏ qua.

struct SessionAttendanceDTO: Codable, Equatable, Sendable {
    let sessionId: String
    let classId: String
    let className: String
    let sessionDate: String
    let startsAt: String
    /// Không có `endsAt` trong hợp đồng: giờ kết thúc lấy từ thẻ buổi ở T03.
    let sessionStatus: String
    let canEdit: Bool
    var editableUntil: String?
    let items: [AttendanceItemDTO]
}

/// `status == nil`: chưa điểm danh, app hiểu là "Có mặt".
struct AttendanceItemDTO: Codable, Equatable, Sendable {
    let studentId: String
    let fullName: String
    var status: String?
    var note: String?
    var clientUpdatedAt: String?
    var syncedAt: String?
}

struct SyncRequestDTO: Codable, Equatable, Sendable {
    let deviceId: String
    let deviceTime: String
    let sessionId: String
    let markSessionTaught: Bool
    let mutations: [OutboxMutation]
}

struct SyncResultDTO: Decodable, Equatable, Sendable {
    let sessionId: String
    let sessionStatus: String
    let results: [MutationResultDTO]
    var parentsToNotify = 0

    enum CodingKeys: String, CodingKey { case sessionId, sessionStatus, results, parentsToNotify }

    init(sessionId: String, sessionStatus: String, results: [MutationResultDTO], parentsToNotify: Int = 0) {
        self.sessionId = sessionId
        self.sessionStatus = sessionStatus
        self.results = results
        self.parentsToNotify = parentsToNotify
    }

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sessionId = try c.decode(String.self, forKey: .sessionId)
        sessionStatus = try c.decode(String.self, forKey: .sessionStatus)
        results = try c.decode([MutationResultDTO].self, forKey: .results)
        parentsToNotify = try c.decodeIfPresent(Int.self, forKey: .parentsToNotify) ?? 0
    }
}

/// `result`: applied | duplicate | stale | rejected.
struct MutationResultDTO: Decodable, Equatable, Sendable {
    let clientMutationId: String
    let studentId: String
    let result: String
    var errorCode: String?
    var current: CurrentDTO?
}

struct CurrentDTO: Decodable, Equatable, Sendable {
    let status: String
    var note: String?
    var clientUpdatedAt: String?
}

private struct ClassDetailDTO: Decodable, Sendable {
    struct Rule: Decodable, Sendable { let weekday: Int }
    var scheduleRules: [Rule] = []
}

/// Nguồn dữ liệu điểm danh; test thay bằng bản giả.
protocol AttendanceService: Sendable {
    func get(sessionId: String) async throws -> SessionAttendanceDTO
    /// `key` = `Idempotency-Key`: UUID mỗi lô, giữ nguyên khi thử lại hoặc phát lại từ outbox.
    func sync(key: String, body: SyncRequestDTO) async throws -> SyncResultDTO
    /// Thứ ISO (1 = Thứ Hai … 7 = Chủ Nhật) của quy tắc lặp của lớp.
    func repeatWeekdays(classId: String) async throws -> [Int]
}

struct AttendanceAPI: AttendanceService {
    let api: APIClient

    func get(sessionId: String) async throws -> SessionAttendanceDTO {
        try await api.request("GET", "sessions/\(sessionId)/attendance")
    }

    func sync(key: String, body: SyncRequestDTO) async throws -> SyncResultDTO {
        try await api.request("POST", "attendance/sync", body: body, headers: ["Idempotency-Key": key])
    }

    func repeatWeekdays(classId: String) async throws -> [Int] {
        let detail: ClassDetailDTO = try await api.request("GET", "classes/\(classId)")
        return detail.scheduleRules.map(\.weekday)
    }
}
