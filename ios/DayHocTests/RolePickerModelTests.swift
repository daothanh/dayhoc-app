import Foundation
import os
import Testing
@testable import DayHoc

/// Cùng ca kiểm thử với `RolePickerViewModelTest` bên Android.
/// US-0.10 AC2, [T01c]: vai trò đã có thì mở luôn, chưa có thì tạo (Giáo viên tạo không gian;
/// Phụ huynh, Học sinh thêm vai trò) rồi mở.
/// Ở phạm vi tệp để dùng được trong handler `@Sendable` của `MockServer` (static của kiểu `@MainActor` bị cô lập).
private let teacherJSON = #"{"id":"m1","role":"tenant_owner","label":"Giáo viên · Cô Lan"}"#
private let parentJSON = #"{"id":"m2","role":"parent","label":"Phụ huynh của Nam"}"#

@MainActor
struct RolePickerModelTests {
    private let store = InMemoryTokenStore(Tokens(accessToken: "a", refreshToken: "r"))

    private func makeStore(_ server: MockServer) -> MembershipStore {
        MembershipStore(api: server.makeClient(tokenStore: store), defaults: UserDefaults(suiteName: UUID().uuidString)!)
    }

    @Test func ownedRoleOpensWithoutCreating() async throws {
        let server = MockServer { request in
            request.url?.path == "/v1/me/memberships"
                ? Stub(status: 200, json: #"{"items":[\#(teacherJSON)]}"#)
                : .noContent
        }
        let store = makeStore(server)
        try await store.load()
        var opened: MembershipDTO?
        let model = RolePickerModel(store: store) { opened = $0 }

        #expect(model.owned == [Roles.teacher])
        model.select(Roles.teacher)
        await model.proceed()
        try await Task.sleep(for: .milliseconds(200))

        #expect(opened?.id == "m1")
        #expect(!model.busy)
        #expect(server.requests(to: "workspaces").isEmpty)
        #expect(server.requests(to: "me/active-membership").count == 1)
    }

    @Test func unownedRoleIsCreatedThenOpened() async throws {
        let loads = OSAllocatedUnfairLock(initialState: 0)
        let server = MockServer { request in
            switch request.url?.path {
            case "/v1/me/roles":
                return .noContent
            case "/v1/me/memberships":
                // Lần tải đầu chưa có vai trò; sau khi POST /me/roles, server trả vai trò mới.
                let n = loads.withLock { n -> Int in
                    n += 1
                    return n
                }
                return Stub(status: 200, json: n == 1 ? #"{"items":[]}"# : #"{"items":[\#(parentJSON)]}"#)
            default:
                return .noContent
            }
        }
        let store = makeStore(server)
        try await store.load()
        var opened: MembershipDTO?
        let model = RolePickerModel(store: store) { opened = $0 }
        model.select(Roles.parent)

        await model.proceed()

        #expect(opened?.id == "m2")
        #expect(server.requests(to: "me/roles").first?.jsonBody == ["role": "parent"])
    }

    @Test func addRoleFailureReportsAndDoesNotOpen() async throws {
        let server = MockServer { request in
            switch request.url?.path {
            case "/v1/me/roles":
                return .error(422, "VALIDATION_FAILED")
            case "/v1/me/memberships":
                return Stub(status: 200, json: #"{"items":[]}"#)
            default:
                return .noContent
            }
        }
        let store = makeStore(server)
        try await store.load()
        var opened: MembershipDTO?
        let model = RolePickerModel(store: store) { opened = $0 }

        model.select(Roles.student)
        await model.proceed()

        #expect(model.failed)
        #expect(opened == nil)
        #expect(!model.busy)

        // Màn hình báo xong thì hạ cờ để lần sau hiện lại.
        model.failed = false
        #expect(!model.failed)
    }

    @Test func busyProceedDoesNotSubmitAgain() async throws {
        let loads = OSAllocatedUnfairLock(initialState: 0)
        let server = MockServer { request in
            switch request.url?.path {
            case "/v1/me/roles":
                return .noContent
            case "/v1/me/memberships":
                let n = loads.withLock { n -> Int in
                    n += 1
                    return n
                }
                return Stub(status: 200, json: n == 1 ? #"{"items":[]}"# : #"{"items":[\#(parentJSON)]}"#)
            default:
                return .noContent
            }
        }
        let store = makeStore(server)
        try await store.load()
        let model = RolePickerModel(store: store) { _ in }
        model.select(Roles.parent)

        let first = Task { await model.proceed() }
        try await Task.sleep(for: .milliseconds(20))
        await model.proceed()
        await first.value

        #expect(server.requests(to: "me/roles").count == 1)
    }
}
