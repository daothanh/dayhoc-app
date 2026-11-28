import SwiftUI

/// Điểm đến của T04 trong NavigationStack: `sessionId` và giờ kết thúc buổi (API T04 không trả giờ kết thúc).
struct AttendanceRoute: Hashable, Identifiable {
    let sessionId: String
    let endsAt: Date
    var id: String { sessionId }
}

/// Giữ `AttendanceModel` suốt đời của màn (NavigationStack dựng lại nội dung điểm đến khi cha cập nhật).
struct AttendanceHost: View {
    let route: AttendanceRoute
    let timeZone: TimeZone
    let onClose: () -> Void

    @State private var model: AttendanceModel

    init(app: AppModel, route: AttendanceRoute, timeZone: TimeZone, onClose: @escaping () -> Void) {
        self.route = route
        self.timeZone = timeZone
        self.onClose = onClose
        let services = app.attendance
        _model = State(initialValue: AttendanceModel(
            sessionId: route.sessionId,
            service: services.service,
            outbox: services.store,
            roster: services.store,
            device: services.store,
            engine: services.engine,
            connectivity: services.connectivity,
            ownerProvider: { await app.currentOwner() },
            notify: { notice in
                app.toast = switch notice {
                case .saved(_, let names) where !names.isEmpty:
                    String(localized: "attendance.saved.rejected \(names.joined(separator: ", "))")
                case .saved(let n, _) where n > 0: String(localized: "attendance.saved.notified \(n)")
                case .saved: String(localized: "attendance.saved")
                case .savedLocal: String(localized: "attendance.saved.local")
                case .stale: String(localized: "attendance.stale")
                }
            },
            onEnqueued: { services.onEnqueued() },
            timeZone: timeZone
        ))
    }

    var body: some View {
        AttendanceView(model: model, endsAt: route.endsAt, timeZone: timeZone, onClose: onClose)
    }
}
