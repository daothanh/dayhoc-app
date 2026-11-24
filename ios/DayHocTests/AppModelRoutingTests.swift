import Foundation
import Testing
@testable import DayHoc

/// Điều hướng gốc khi mở app với phiên có sẵn: giáo viên còn bước onboarding dở thì phải vào onboarding
/// chứ không dừng ở màn chính. Đây là điểm UI test onboarding dựa vào.
@MainActor
struct AppModelRoutingTests {
    private let store = InMemoryTokenStore(Tokens(accessToken: "a", refreshToken: "r"))

    private static let memberships = #"{"items":[{"id":"m1","role":"tenant_owner","label":"Giáo viên · Cô Lan","tenantId":"t1"}],"lastMembershipId":"m1"}"#
    private static let onboardingPending = #"{"steps":[{"step":"create_class","status":"pending"},{"step":"add_students","status":"pending"},{"step":"set_schedule","status":"pending"}],"currentStep":"create_class"}"#

    private func makeModel(_ handler: @escaping @Sendable (URLRequest) -> Stub) -> AppModel {
        let server = MockServer(handler: handler)
        let api = server.makeClient(tokenStore: store)
        return AppModel(env: AppEnvironment(api: api, tokenStore: store))
    }

    private func name(_ route: AppModel.Route) -> String {
        switch route {
        case .launching: "launching"
        case .login: "login"
        case .rolePicker: "rolePicker"
        case .teacher: "teacher"
        case .onboarding: "onboarding"
        case .parent: "parent"
        case .student: "student"
        case .failed: "failed"
        }
    }

    @Test func teacherWithPendingOnboardingOpensOnboarding() async {
        let memberships = Self.memberships
        let pending = Self.onboardingPending
        let model = makeModel { request in
            switch request.url?.path {
            case "/v1/me/memberships": Stub(status: 200, json: memberships)
            case "/v1/onboarding": Stub(status: 200, json: pending)
            default: Stub(status: 200, json: "{}")
            }
        }

        await model.start()

        #expect(name(model.route) == "onboarding", "route thực tế: \(name(model.route))")
    }

    @Test func mockOnboardingResponseDecodes() throws {
        UITestBackend.shared.configure(for: .onboardingStudents)
        var request = URLRequest(url: URL(string: "https://uitest.dayhoc.local/v1/onboarding")!)
        request.httpMethod = "GET"
        let stub = UITestBackend.shared.respond(to: request, mode: .onboardingStudents)
        let dto = try JSONDecoder().decode(OnboardingDTO.self, from: Data(stub.json.utf8))
        #expect(dto.current == .addStudents, "json: \(stub.json)")
    }
}
