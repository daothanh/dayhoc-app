import SwiftUI

/// Khung chung của mỗi không gian vai trò: App Bar `Root` có C20 Chip vai trò mở [T00] Chuyển vai trò.
struct RoleSpace<Content: View, Trailing: View>: View {
    let app: AppModel
    /// Tiêu đề cạnh chip vai trò (Figma `Root`); `nil` thì chỉ có chip.
    var title: LocalizedStringKey?
    @ViewBuilder let trailing: () -> Trailing
    @ViewBuilder let content: () -> Content

    @State private var switcher = false
    @Environment(\.dhColors) private var colors

    private func header(_ title: LocalizedStringKey) -> some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            RoleChip(store: app.memberships) { switcher = true }
                .fixedSize()
            Text(title).dhText(TeachHubTokens.Typo.h2).lineLimit(1)
            Spacer(minLength: 0)
            trailing()
        }
        .padding(.leading, TeachHubTokens.Dimens.spacingPage)
        .padding(.trailing, TeachHubTokens.Dimens.spacingInline)
        .frame(height: 56)
        .background(colors.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(colors.outlineVariant).frame(height: 1.5) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let title {
                    // App Bar `Root` có tiêu đề: tự vẽ để chip vai trò không bị thanh điều hướng nén còn avatar.
                    VStack(spacing: 0) {
                        header(title)
                        content()
                    }
                    .toolbar(.hidden, for: .navigationBar)
                } else {
                    content()
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                RoleChip(store: app.memberships) { switcher = true }
                            }
                        }
                }
            }
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
    @State private var today: TodayModel
    @Environment(\.dhColors) private var colors
    @Environment(\.scenePhase) private var scenePhase

    init(app: AppModel) {
        self.app = app
        _today = State(initialValue: TodayModel(source: TodayAPI(api: app.api)))
    }

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
            title: tab == 0 ? "today.title" : nil,
            trailing: { if tab == 0 { TodayAppBarActions(model: today, onBell: comingSoon) } },
            content: {
                Group {
                    if tab == 0 {
                        TodayView(
                            model: today,
                            onComingSoon: comingSoon,
                            onOpenSchedule: { tab = 1 },
                            onContinueSetup: { app.openOnboarding(startingAt: $0) }
                        )
                    } else {
                        ScrollView {
                            VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
                                Text(items[tab].label).font(.title.bold())
                                Text("space.coming").foregroundStyle(colors.onSurfaceVariant)
                                if tab == items.count - 1 {
                                    DhPrimaryButton(title: "logout", isLoading: loggingOut) {
                                        loggingOut = true
                                        Task { await app.logout() }
                                    }
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(TeachHubTokens.Dimens.spacingPage)
                        }
                    }
                }
                .task(id: tab) { if tab == 0 { await today.refresh() } }
                .onChange(of: today.refreshFailures) { app.toast = String(localized: "today.refreshFailed") }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active, tab == 0 { Task { await today.refresh() } }
                }
                .safeAreaInset(edge: .bottom) { DhBottomNav(items: items, selected: $tab) }
            }
        )
    }

    private func comingSoon() { app.toast = String(localized: "coming.soon") }
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
