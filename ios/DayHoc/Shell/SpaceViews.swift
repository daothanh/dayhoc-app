import SwiftUI

/// Khung chung của mỗi không gian vai trò: App Bar `Root` có C20 Chip vai trò mở [T00] Chuyển vai trò.
struct RoleSpace<Content: View, Trailing: View>: View {
    let app: AppModel
    /// Tiêu đề cạnh chip vai trò (Figma `Root`); `nil` thì chỉ có chip.
    var title: LocalizedStringKey?
    @ViewBuilder let trailing: () -> Trailing
    @ViewBuilder let content: () -> Content

    @State private var switcher = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                DhAppBar(title: title, leading: { RoleChip(store: app.memberships) { switcher = true } }, trailing: trailing)
                content()
            }
            .toolbar(.hidden, for: .navigationBar)
            .dhScreen()
        }
        .sheet(isPresented: $switcher) {
            RoleSwitcher(store: app.memberships) { m in
                switcher = false
                Task { await app.switchTo(m) }
            } onAddRole: {
                switcher = false
                app.addRole()
            }
            .presentationDetents([.medium, .large])
        }
    }
}

extension RoleSpace where Trailing == EmptyView {
    init(app: AppModel, @ViewBuilder content: @escaping () -> Content) {
        self.init(app: app, title: nil, trailing: { EmptyView() }, content: content)
    }
}

/// C20 Chip vai trò: một vai trò thì chỉ hiện tên; nhiều vai trò thì có ▾ và chạm mở T00.
private struct RoleChip: View {
    let store: MembershipStore
    let onTap: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        if let active = store.active {
            let multi = store.items.count > 1
            let chip = HStack(spacing: 6) {
                RoleAvatar(role: active.role, size: 24)
                Text(Roles.name(active.role)).font(.footnote.bold())
                if multi { Image(systemName: "chevron.down").font(.caption2.bold()) }
            }
            .padding(.leading, 4)
            .padding(.trailing, 10)
            .frame(height: 32)
            .fixedSize()
            .overlay(Capsule().stroke(colors.outlineVariant, lineWidth: 1.5))
            .foregroundStyle(colors.onSurface)
            if multi {
                Button(action: onTap) { chip }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("role.switchTitle"))
            } else {
                chip
            }
        }
    }
}

/// [T00] Chuyển vai trò: chỉ đổi vai trò đang mở ở shell rồi điều hướng, không đăng nhập lại,
/// nên dưới 1 giây (US-0.11 AC1). Dòng của vai trò hiện tại không bấm được.
private struct RoleSwitcher: View {
    let store: MembershipStore
    let onSelect: (MembershipDTO) -> Void
    let onAddRole: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.items, id: \.id) { m in
                    let current = m.id == store.active?.id
                    Button {
                        onSelect(m)
                    } label: {
                        HStack(spacing: TeachHubTokens.Dimens.spacingContent) {
                            RoleAvatar(role: m.role, size: 36)
                            VStack(alignment: .leading) {
                                Text(Roles.name(m.role))
                                // Phạm vi lấy từ nhãn API ("Giáo viên · Cô Lan" → "Cô Lan"), không có thì mô tả vai trò.
                                if let range = m.label.range(of: " · ") {
                                    Text(m.label[range.upperBound...])
                                        .font(.footnote)
                                        .foregroundStyle(colors.onSurfaceVariant)
                                } else {
                                    Text(Roles.description(m.role))
                                        .font(.footnote)
                                        .foregroundStyle(colors.onSurfaceVariant)
                                }
                            }
                            Spacer()
                            if current { Image(systemName: "checkmark") }
                        }
                        .foregroundStyle(colors.onSurface)
                    }
                    .disabled(current)
                }
                Button(action: onAddRole) {
                    Label("role.add", systemImage: "plus").foregroundStyle(colors.onSurface)
                }
            }
            .navigationTitle("role.switchTitle")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct RoleAvatar: View {
    let role: String
    let size: CGFloat

    @Environment(\.dhColors) private var colors

    var body: some View {
        Text(String(String(localized: Roles.name(role)).prefix(1)))
            .font(.system(size: size * 0.45, weight: .bold))
            .foregroundStyle(colors.onPrimary)
            .frame(width: size, height: size)
            .background(colors.primary, in: Circle())
    }
}

/// Không gian giáo viên: 5 tab Hôm nay · Lịch · Lớp · Học phí · Thêm (Figma C02 `Teacher`).
/// Nội dung các tab làm ở NA-06, NA-08; tab Thêm có Đăng xuất.
struct TeacherSpaceView: View {
    let app: AppModel

    @State private var tab = 0
    @State private var loggingOut = false
    /// Buổi đang mở ở sheet T05a, và buổi đang mở ở T04 (màn đẩy, không bottom nav).
    @State private var sheet: TodaySession?
    @State private var attendance: AttendanceRoute?
    /// Số buổi chưa đồng bộ khi bấm Đăng xuất: có thì hỏi lại trước khi xóa.
    @State private var unsyncedOnLogout: Int?
    @Environment(\.dhColors) private var colors
    @Environment(\.scenePhase) private var scenePhase

    init(app: AppModel) {
        self.app = app
    }

    private var today: TodayModel { app.today }

    private let items = [
        DhNavItem(id: 0, label: "nav.today", icon: "home-2"),
        DhNavItem(id: 1, label: "nav.schedule", icon: "calendar"),
        DhNavItem(id: 2, label: "nav.classes", icon: "users-group-rounded"),
        DhNavItem(id: 3, label: "nav.fees", icon: "wallet"),
        DhNavItem(id: 4, label: "nav.more", icon: "widget"),
    ]

    var body: some View {
        RoleSpace(
            app: app,
            title: items[tab].label,
            trailing: { TeacherAppBarActions(model: today, onBell: comingSoon) },
            content: {
                Group {
                    if tab == 0 {
                        TodayView(
                            model: today,
                            onComingSoon: comingSoon,
                            onSession: { sheet = $0 },
                            onAttendance: { attendance = AttendanceRoute(sessionId: $0.id, endsAt: $0.end) },
                            onOpenSchedule: { tab = 1 },
                            onContinueSetup: { app.openOnboarding(startingAt: $0) }
                        )
                    } else {
                        ScrollView {
                            VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
                                Text("space.coming").foregroundStyle(colors.onSurfaceVariant)
                                if tab == items.count - 1 {
                                    DhPrimaryButton(title: "logout", isLoading: loggingOut) { Task { await requestLogout() } }
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(TeachHubTokens.Dimens.spacingPage)
                        }
                    }
                }
                .task(id: tab) { if tab == 0 { await today.refresh() } }
                .task { app.attendance.requestSync() }
                // Đồng bộ nền xong một lô: tải lại Hôm nay; lô bị xóa vì lỗi không thử lại được thì báo.
                .task {
                    for await event in app.attendance.engine.events() {
                        switch event {
                        case .synced(_, let rejected):
                            if rejected > 0 { app.toast = String(localized: "attendance.partlyRejected \(rejected)") }
                            await today.refresh()
                        case .dropped(_, let code): app.toast = droppedText(code)
                        }
                    }
                }
                .sheet(item: $sheet) { session in
                    SessionSheet(
                        session: session,
                        timeZone: today.timeZone,
                        loadRepeat: { [service = app.attendance.service] classId in
                            (try? await service.repeatWeekdays(classId: classId)).flatMap(repeatLabel)
                        },
                        onAttend: {
                            sheet = nil
                            attendance = AttendanceRoute(sessionId: session.id, endsAt: session.end)
                        },
                        onDismiss: { sheet = nil },
                        onComingSoon: {
                            sheet = nil
                            comingSoon()
                        }
                    )
                    .presentationDetents([.medium, .large])
                    .presentationBackground(colors.surface)
                }
                .navigationDestination(item: $attendance) { route in
                    AttendanceHost(app: app, route: route, timeZone: today.timeZone) { attendance = nil }
                }
                .alert(
                    Text("logout.pending.title \(unsyncedOnLogout ?? 0)"),
                    isPresented: Binding(get: { unsyncedOnLogout != nil }, set: { if !$0 { unsyncedOnLogout = nil } })
                ) {
                    Button("logout.pending.stay", role: .cancel) {}
                    Button("logout.pending.confirm", role: .destructive) { doLogout() }
                } message: {
                    Text("logout.pending.body")
                }
                .onChange(of: today.refreshFailures) { app.toast = String(localized: "today.refreshFailed") }
                .onChange(of: scenePhase) { _, phase in
                    guard phase == .active else { return }
                    app.attendance.requestSync()
                    if tab == 0 { Task { await today.refresh() } }
                }
                .safeAreaInset(edge: .bottom) { DhBottomNav(items: items, selected: $tab) }
            }
        )
    }

    private func comingSoon() { app.toast = String(localized: "coming.soon") }

    private func droppedText(_ code: String) -> String {
        switch code {
        case "ATTENDANCE_EDIT_WINDOW_CLOSED": String(localized: "attendance.error.windowClosed")
        case "SESSION_CANCELLED": String(localized: "attendance.error.cancelled")
        default: String(localized: "attendance.dropped")
        }
    }

    private func requestLogout() async {
        let n = await app.attendance.unsyncedSessionCount()
        if n > 0 { unsyncedOnLogout = n } else { doLogout() }
    }

    private func doLogout() {
        loggingOut = true
        Task { await app.logout() }
    }
}

/// Không gian phụ huynh, học sinh ở trạng thái chưa có lời mời (US-0.10 AC3).
/// shortcut: chỉ có chip vai trò và đăng xuất; màn thật và bottom nav `Parent`/`Student` làm ở NA-11, NA-12.
struct PlaceholderSpaceView: View {
    let app: AppModel

    @State private var loggingOut = false

    var body: some View {
        RoleSpace(app: app) {
            VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
                Text("space.noInvite")
                Spacer()
                DhPrimaryButton(title: "logout", isLoading: loggingOut) {
                    loggingOut = true
                    Task { await app.logout() }
                }
            }
            .padding(TeachHubTokens.Dimens.spacingPage)
        }
    }
}
