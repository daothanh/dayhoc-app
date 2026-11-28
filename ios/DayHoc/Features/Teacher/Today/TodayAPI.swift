import Foundation

// DTO viết tay theo teachhub-backend/contracts/openapi.json (sessions, classes, receivables, notifications).
// Chỉ khai trường màn Hôm nay dùng; trường lạ bị bỏ qua.

struct SessionDTO: Decodable, Equatable, Sendable {
    let id: String
    let classId: String
    let className: String
    /// ISO 8601 có offset.
    let startsAt: String
    let endsAt: String
    /// `planned` | `taught` | `cancelled`.
    let status: String
}

struct ClassInfoDTO: Decodable, Equatable, Sendable {
    let id: String
    let name: String
    var location: String?
    var enrolledCount: Int = 0

    enum CodingKeys: String, CodingKey { case id, name, location, enrolledCount }

    init(id: String, name: String, location: String? = nil, enrolledCount: Int = 0) {
        self.id = id
        self.name = name
        self.location = location
        self.enrolledCount = enrolledCount
    }

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        location = try c.decodeIfPresent(String.self, forKey: .location)
        enrolledCount = try c.decodeIfPresent(Int.self, forKey: .enrolledCount) ?? 0
    }
}

struct ReceivableRowDTO: Decodable, Equatable, Sendable {
    let outstandingAmount: Int
    let overdueDays: Int
}

/// Sĩ số = số học viên của buổi; có mặt = present + late (đi muộn vẫn có mặt).
struct AttendanceDTO: Decodable, Equatable, Sendable {
    struct Row: Decodable, Equatable, Sendable { let studentId: String }
    struct Summary: Decodable, Equatable, Sendable {
        var present = 0
        var late = 0
    }

    let items: [Row]
    let summary: Summary
}

private struct Page<Item: Decodable & Sendable>: Decodable, Sendable { let items: [Item] }
private struct NotificationInfo: Decodable, Sendable { var unreadCount = 0 }

/// Nguồn dữ liệu của màn Hôm nay; test thay bằng bản giả.
protocol TodayDataSource: Sendable {
    func onboarding() async throws -> OnboardingDTO
    func sessions(from: String, to: String) async throws -> [SessionDTO]
    func classes() async throws -> [ClassInfoDTO]
    func receivables() async throws -> [ReceivableRowDTO]
    func unreadCount() async throws -> Int
    func attendance(sessionId: String) async throws -> AttendanceDTO
}

struct TodayAPI: TodayDataSource {
    let api: APIClient

    func onboarding() async throws -> OnboardingDTO { try await api.request("GET", "onboarding") }

    func sessions(from: String, to: String) async throws -> [SessionDTO] {
        let page: Page<SessionDTO> = try await api.request(
            "GET", "sessions", query: [URLQueryItem(name: "from", value: from), URLQueryItem(name: "to", value: to)]
        )
        return page.items
    }

    func classes() async throws -> [ClassInfoDTO] {
        // shortcut: chỉ trang đầu 100 lớp đang mở, nâng cấp khi một giáo viên có hơn 100 lớp
        let page: Page<ClassInfoDTO> = try await api.request(
            "GET", "classes",
            query: [URLQueryItem(name: "status", value: "active"), URLQueryItem(name: "pageSize", value: "100")]
        )
        return page.items
    }

    func receivables() async throws -> [ReceivableRowDTO] {
        let page: Page<ReceivableRowDTO> = try await api.request(
            "GET", "receivables", query: [URLQueryItem(name: "pageSize", value: "100")]
        )
        return page.items
    }

    func unreadCount() async throws -> Int {
        let info: NotificationInfo = try await api.request(
            "GET", "notifications", query: [URLQueryItem(name: "limit", value: "1")]
        )
        return info.unreadCount
    }

    func attendance(sessionId: String) async throws -> AttendanceDTO {
        try await api.request("GET", "sessions/\(sessionId)/attendance")
    }
}
