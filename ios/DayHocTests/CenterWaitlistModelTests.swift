import Foundation
import os
import Testing
@testable import DayHoc

/// Cùng ca kiểm thử với `CenterWaitlistViewModelTest` bên Android.
/// [T01d] Chủ trung tâm đăng ký sớm: kiểm tra ô nhập, gửi `POST /center-waitlist` với
/// `Idempotency-Key` bám theo nội dung (gửi lại không ghi hai lần, sửa ô thì là lệnh mới).
@MainActor
struct CenterWaitlistModelTests {
    private let store = InMemoryTokenStore(Tokens(accessToken: "a", refreshToken: "r"))

    private func makeModel(_ server: MockServer) -> CenterWaitlistModel {
        CenterWaitlistModel(api: server.makeClient(tokenStore: store))
    }

    @Test func validNameAndCountSubmits() async throws {
        let server = MockServer { _ in .noContent }
        let model = makeModel(server)
        model.centerName = " Trung tâm ABC "
        model.teacherCount = "12"

        await model.submit()

        #expect(model.sent)
        #expect(!model.busy)
        let request = try #require(server.requests(to: "center-waitlist").first)
        let body = request.jsonObject
        #expect(body["centerName"] as? String == "Trung tâm ABC")
        #expect(body["teacherCount"] as? Int == 12)
        #expect(request.value(forHTTPHeaderField: "Idempotency-Key")?.isEmpty == false)
    }

    @Test func invalidInputShowsErrorsAndSkipsApi() async throws {
        let server = MockServer { _ in .noContent }
        let model = makeModel(server)

        await model.submit()

        #expect(model.showErrors)
        #expect(!model.nameValid)
        #expect(server.requests(to: "center-waitlist").isEmpty)

        model.centerName = "Trung tâm ABC"
        model.teacherCount = "0"
        await model.submit()

        #expect(model.count == nil)
        #expect(server.requests(to: "center-waitlist").isEmpty)
    }

    /// Android chặn ngay trong ô nhập (chỉ chữ số, tối đa 4). iOS để bàn phím số `.numberPad` lo phần đó
    /// nên ở đây kiểm hợp đồng của model: số giáo viên phải nằm trong 1...1000.
    @Test func countRejectsNonNumericAndOutOfRange() async {
        let model = makeModel(MockServer { _ in .noContent })
        model.teacherCount = "1a2b3456"
        #expect(model.count == nil)
        model.teacherCount = "1001"
        #expect(model.count == nil)
        model.teacherCount = "1000"
        #expect(model.count == 1000)
    }

    @Test func resentAfterRetryableErrorKeepsKeyAndNewContentResetsIt() async throws {
        let server = MockServer { _ in .error(503, "SERVICE_UNAVAILABLE") }
        let model = makeModel(server)
        model.centerName = "Trung tâm ABC"
        model.teacherCount = "12"

        await model.submit()
        await model.submit()

        let keys = server.requests(to: "center-waitlist").map { $0.value(forHTTPHeaderField: "Idempotency-Key") }
        #expect(keys.count == 2)
        #expect(keys[0] == keys[1])

        model.centerName = "Trung tâm XYZ"
        await model.submit()

        let after = server.requests(to: "center-waitlist").map { $0.value(forHTTPHeaderField: "Idempotency-Key") }
        #expect(after[1] != after[2])
    }

    @Test func hardRejectionResetsKey() async throws {
        let attempts = OSAllocatedUnfairLock(initialState: 0)
        let server = MockServer { _ in
            attempts.withLock { n in
                n += 1
                return n == 1 ? .error(422, "VALIDATION_FAILED") : .noContent
            }
        }
        let model = makeModel(server)
        model.centerName = "Trung tâm ABC"
        model.teacherCount = "12"

        await model.submit()
        await model.submit()

        let keys = server.requests(to: "center-waitlist").map { $0.value(forHTTPHeaderField: "Idempotency-Key") }
        #expect(keys.count == 2)
        #expect(keys[0] != keys[1])
    }
}
