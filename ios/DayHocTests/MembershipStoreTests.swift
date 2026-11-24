import Foundation
import os
import Testing
@testable import DayHoc

/// Cùng ca kiểm thử với `MembershipStoreTest` bên Android.
/// US-0.11: đổi vai trò chỉ đổi vai trò đang mở trên máy và báo server, không đổi phiên đăng nhập.
/// `Idempotency-Key` của lệnh thêm vai trò phải giữ qua các lần thử lại sau lỗi mạng để không tạo
/// hai không gian giáo viên.
/// Ở phạm vi tệp để dùng được trong handler `@Sendable` của `MockServer` (static của kiểu `@MainActor` bị cô lập).
private let teacherJSON = #"{"id":"m1","role":"tenant_owner","label":"Giáo viên · Cô Lan"}"#
private let parentJSON = #"{"id":"m2","role":"parent","label":"Phụ huynh của Nam"}"#

@MainActor
struct MembershipStoreTests {
    private let store = InMemoryTokenStore(Tokens(accessToken: "a", refreshToken: "r"))
    private let defaults = UserDefaults(suiteName: UUID().uuidString)!

    private func makeStore(_ server: MockServer) -> MembershipStore {
        MembershipStore(api: server.makeClient(tokenStore: store), defaults: defaults)
    }

    /// Chờ lời báo `PUT /me/active-membership` chạy trong `Task` của `select` (máy chủ giả trả lời trễ 0,05 giây).
    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(200))
    }

    @Test func selectSavesIdOnDeviceForNextLaunch() async throws {
        let server = MockServer { request in
            request.url?.path == "/v1/me/memberships"
                ? Stub(status: 200, json: #"{"items":[\#(teacherJSON),\#(parentJSON)],"lastMembershipId":"m1"}"#)
                : .noContent
        }
        let store = makeStore(server)
        try await store.load()

        store.select(store.items[1])
        try await settle()

        #expect(defaults.string(forKey: "active_membership_id") == "m2")
        #expect(server.requests(to: "me/active-membership").first?.jsonBody == ["membershipId": "m2"])
        #expect(store.active?.id == "m2")

        // Mở lại app: máy nhớ vai trò đã chọn (ưu tiên hơn lần dùng gần nhất trên server).
        let reopened = makeStore(server)
        try await reopened.load()
        #expect(reopened.active?.id == "m2")
    }

    @Test func loadUsesLastMembershipIdWhenDeviceHasNoChoice() async throws {
        let server = MockServer { request in
            request.url?.path == "/v1/me/memberships"
                ? Stub(status: 200, json: #"{"items":[\#(teacherJSON),\#(parentJSON)],"lastMembershipId":"m2"}"#)
                : .noContent
        }
        let store = makeStore(server)
        try await store.load()
        #expect(store.active?.id == "m2")
    }

    @Test func loadPicksSingleMembership() async throws {
        let server = MockServer { request in
            request.url?.path == "/v1/me/memberships"
                ? Stub(status: 200, json: #"{"items":[\#(teacherJSON)]}"#)
                : .noContent
        }
        let store = makeStore(server)
        try await store.load()
        #expect(store.active?.id == "m1")
    }

    @Test func addTeacherCreatesWorkspaceAndKeepsKeyOnRetry() async throws {
        let attempts = OSAllocatedUnfairLock(initialState: 0)
        let server = MockServer { request in
            switch request.url?.path {
            case "/v1/workspaces":
                return attempts.withLock { n in
                    n += 1
                    return n == 1 ? .error(503, "SERVICE_UNAVAILABLE") : .noContent
                }
            case "/v1/me/memberships":
                return Stub(status: 200, json: #"{"items":[\#(teacherJSON)]}"#)
            default:
                return .noContent
            }
        }
        let store = makeStore(server)
        await #expect(throws: APIError.self) { try await store.addRole(Roles.teacher) }

        let created = try await store.addRole(Roles.teacher)

        #expect(created.id == "m1")
        let keys = server.requests(to: "workspaces").map { $0.value(forHTTPHeaderField: "Idempotency-Key") }
        #expect(keys.count == 2)
        #expect(keys[0] == keys[1])
        #expect(store.active?.id == "m1")
    }

    @Test func addTeacherKeepsKeyWhenNewRoleNotVisible() async throws {
        let loads = OSAllocatedUnfairLock(initialState: 0)
        let server = MockServer { request in
            switch request.url?.path {
            case "/v1/workspaces":
                return .noContent
            case "/v1/me/memberships":
                // Lần tải đầu chưa thấy vai trò mới; bấm lại thì server phát lại kết quả của cùng khóa.
                let n = loads.withLock { n -> Int in
                    n += 1
                    return n
                }
                return Stub(status: 200, json: n == 1 ? #"{"items":[]}"# : #"{"items":[\#(teacherJSON)]}"#)
            default:
                return .noContent
            }
        }
        let store = makeStore(server)
        await #expect(throws: APIError.self) { try await store.addRole(Roles.teacher) }

        _ = try await store.addRole(Roles.teacher)

        let keys = server.requests(to: "workspaces").map { $0.value(forHTTPHeaderField: "Idempotency-Key") }
        #expect(keys[0] == keys[1])
    }

    @Test func addParentCallsMeRoles() async throws {
        let server = MockServer { request in
            switch request.url?.path {
            case "/v1/me/roles":
                return .noContent
            case "/v1/me/memberships":
                return Stub(status: 200, json: #"{"items":[\#(parentJSON)]}"#)
            default:
                return .noContent
            }
        }
        let store = makeStore(server)

        let created = try await store.addRole(Roles.parent)

        #expect(created.id == "m2")
        #expect(server.requests(to: "me/roles").first?.jsonBody == ["role": "parent"])
        #expect(server.requests(to: "workspaces").isEmpty)
        #expect(store.active?.id == "m2")
    }

    @Test func conflictReloadsAndOpensExistingRole() async throws {
        let server = MockServer { request in
            switch request.url?.path {
            case "/v1/workspaces":
                return .error(409, "CONFLICT_STALE")
            case "/v1/me/memberships":
                return Stub(status: 200, json: #"{"items":[\#(teacherJSON)]}"#)
            default:
                return .noContent
            }
        }
        let store = makeStore(server)

        let created = try await store.addRole(Roles.teacher)

        #expect(created.id == "m1")
        #expect(server.requests(to: "workspaces").count == 1)
        #expect(store.active?.id == "m1")
    }

    @Test func hardRejectionResetsKeyForNextAttempt() async throws {
        let attempts = OSAllocatedUnfairLock(initialState: 0)
        let server = MockServer { request in
            switch request.url?.path {
            case "/v1/workspaces":
                return attempts.withLock { n in
                    n += 1
                    return n == 1 ? .error(422, "VALIDATION_FAILED") : .noContent
                }
            case "/v1/me/memberships":
                return Stub(status: 200, json: #"{"items":[\#(teacherJSON)]}"#)
            default:
                return .noContent
            }
        }
        let store = makeStore(server)
        await #expect(throws: APIError.self) { try await store.addRole(Roles.teacher) }

        _ = try await store.addRole(Roles.teacher)

        let keys = server.requests(to: "workspaces").map { $0.value(forHTTPHeaderField: "Idempotency-Key") }
        #expect(keys[0] != keys[1])
    }

    @Test func resetPendingAddClearsKey() async throws {
        let attempts = OSAllocatedUnfairLock(initialState: 0)
        let server = MockServer { request in
            switch request.url?.path {
            case "/v1/workspaces":
                return attempts.withLock { n in
                    n += 1
                    return n == 1 ? .error(503, "SERVICE_UNAVAILABLE") : .noContent
                }
            case "/v1/me/memberships":
                return Stub(status: 200, json: #"{"items":[\#(teacherJSON)]}"#)
            default:
                return .noContent
            }
        }
        let store = makeStore(server)
        _ = try? await store.addRole(Roles.teacher)

        store.resetPendingAdd()
        _ = try await store.addRole(Roles.teacher)

        let keys = server.requests(to: "workspaces").map { $0.value(forHTTPHeaderField: "Idempotency-Key") }
        #expect(keys[0] != keys[1])
    }

    @Test func clearForgetsSessionRole() async throws {
        let server = MockServer { request in
            request.url?.path == "/v1/me/memberships"
                ? Stub(status: 200, json: #"{"items":[\#(teacherJSON)]}"#)
                : .noContent
        }
        let store = makeStore(server)
        try await store.load()
        store.select(store.items[0])
        try await settle()

        store.clear()

        #expect(store.active == nil)
        #expect(store.items.isEmpty)
        #expect(defaults.string(forKey: "active_membership_id") == nil)
    }
}
