import Foundation
import Observation

// DTO viết tay theo teachhub-backend/contracts/openapi.json (tag me, tenancy, app-config).

enum Roles {
    static let teacher = "tenant_owner"
    static let parent = "parent"
    static let student = "student"
    static let all = [teacher, parent, student]

    static func name(_ role: String) -> LocalizedStringResource {
        switch role {
        case teacher: "role.teacher"
        case parent: "role.parent"
        default: "role.student"
        }
    }

    static func description(_ role: String) -> LocalizedStringResource {
        switch role {
        case teacher: "role.teacherDesc"
        case parent: "role.parentDesc"
        default: "role.studentDesc"
        }
    }
}

private struct ActiveMembershipRequest: Encodable, Sendable { let membershipId: String }
private struct AddRoleRequest: Encodable, Sendable { let role: String }
private struct EmptyBody: Encodable, Sendable {}

struct AppConfigDTO: Decodable, Sendable {
    struct MinVersion: Decodable, Sendable {
        let android: String
        let ios: String
    }

    let minSupportedVersion: MinVersion
}

struct CenterWaitlistRequest: Encodable, Equatable, Sendable {
    let centerName: String
    let teacherCount: Int
}

/// `true` khi `current` thấp hơn `minimum` theo `major.minor.patch`; phần `-beta`, `+12` bị bỏ, số lạ coi là 0.
func isVersionBelow(_ current: String, _ minimum: String) -> Bool {
    func parts(_ v: String) -> [Int] {
        let core = v.split(whereSeparator: { $0 == "-" || $0 == "+" }).first.map(String.init) ?? ""
        let p = core.split(separator: ".").map { Int($0) ?? 0 }
        return (0..<3).map { $0 < p.count ? p[$0] : 0 }
    }
    let a = parts(current), b = parts(minimum)
    for i in 0..<3 where a[i] != b[i] { return a[i] < b[i] }
    return false
}

/// Vai trò của người dùng và vai trò đang mở (Technical Spec 3.7, US-0.10, US-0.11).
/// Đổi vai trò không đổi JWT: chỉ đổi `active`, lưu id trên máy và báo `PUT /me/active-membership`.
@Observable @MainActor
final class MembershipStore {
    private(set) var items: [MembershipDTO] = []
    private(set) var active: MembershipDTO?

    private let api: APIClient
    private let defaults: UserDefaults
    /// `Idempotency-Key` của lần thêm Giáo viên đang dở: giữ qua các lần thử lại sau lỗi mạng
    /// để `POST /workspaces` không tạo hai tenant.
    @ObservationIgnored private var workspaceKey: String?
    /// Như `workspaceKey` cho `POST /me/roles`, theo vai trò.
    @ObservationIgnored private var roleKeys: [String: String] = [:]

    private static let activeKey = "active_membership_id"

    init(api: APIClient, defaults: UserDefaults = .standard) {
        self.api = api
        self.defaults = defaults
    }

    /// Vai trò mở lại: lần chọn trên máy này, rồi lần dùng gần nhất trên server, rồi vai trò duy nhất.
    func load() async throws {
        let res: MembershipsDTO = try await api.request("GET", "me/memberships")
        func byId(_ id: String?) -> MembershipDTO? { res.items.first { $0.id == id } }
        items = res.items
        active = byId(defaults.string(forKey: Self.activeKey)) ?? byId(res.lastMembershipId)
            ?? (res.items.count == 1 ? res.items.first : nil)
    }

    func select(_ membership: MembershipDTO) {
        active = membership
        defaults.set(membership.id, forKey: Self.activeKey)
        // Chỉ để máy khác mở đúng vai trò; lỗi bỏ qua.
        let api = api
        Task { try? await api.send("PUT", "me/active-membership", body: ActiveMembershipRequest(membershipId: membership.id)) }
    }

    /// Thêm vai trò rồi mở nó. Giáo viên = `POST /workspaces`; Phụ huynh, Học sinh = `POST /me/roles`.
    @discardableResult
    func addRole(_ role: String) async throws -> MembershipDTO {
        var posted = false
        do {
            if role == Roles.teacher {
                let key = workspaceKey ?? UUID().uuidString.lowercased()
                workspaceKey = key
                try await api.send("POST", "workspaces", body: EmptyBody(), headers: ["Idempotency-Key": key])
            } else {
                let key = roleKeys[role] ?? UUID().uuidString.lowercased()
                roleKeys[role] = key
                try await api.send("POST", "me/roles", body: AddRoleRequest(role: role), headers: ["Idempotency-Key": key])
            }
            posted = true
            try await load()
            // Chưa thấy vai trò mới thì coi như chưa xong và giữ khóa: bấm lại được server phát lại kết quả cũ.
            guard let created = items.first(where: { $0.role == role }) else { throw APIError(kind: .invalidResponse) }
            select(created)
            workspaceKey = nil
            roleKeys[role] = nil
            return created
        } catch {
            // 409: vai trò có thể đã được tạo ở lần trước (mất phản hồi). Tải lại, có thì mở luôn.
            if !posted, (error as? APIError)?.status == 409, (try? await load()) != nil,
               let existing = items.first(where: { $0.role == role }) {
                workspaceKey = nil
                roleKeys[role] = nil
                select(existing)
                return existing
            }
            // Server từ chối hẳn (409, 422…): lần sau là lệnh mới.
            if !posted, (error as? APIError)?.retryable != true {
                workspaceKey = nil
                roleKeys[role] = nil
            }
            throw error
        }
    }

    /// Người dùng đổi ý (chọn vai trò khác): lần thêm Giáo viên sau là lệnh mới.
    func resetPendingAdd() {
        workspaceKey = nil
        roleKeys = [:]
    }

    /// Đăng xuất hoặc hết phiên: quên vai trò của phiên này.
    func clear() {
        workspaceKey = nil
        roleKeys = [:]
        items = []
        active = nil
        defaults.removeObject(forKey: Self.activeKey)
    }
}
