import SwiftUI

/// Khung chung của mỗi không gian vai trò: App Bar `Root` có C20 Chip vai trò mở [T00] Chuyển vai trò.
struct RoleSpace<Content: View>: View {
    let app: AppModel
    @ViewBuilder let content: () -> Content

    @State private var switcher = false

    var body: some View {
        NavigationStack {
            content()
                .dhScreen()
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        RoleChip(store: app.memberships) { switcher = true }
                    }
                }
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
    @Environment(\.dhColors) private var colors

    private let items = [
        DhNavItem(id: 0, label: "nav.today", icon: "home-2"),
        DhNavItem(id: 1, label: "nav.schedule", icon: "calendar"),
        DhNavItem(id: 2, label: "nav.classes", icon: "users-group-rounded"),
        DhNavItem(id: 3, label: "nav.fees", icon: "wallet"),
        DhNavItem(id: 4, label: "nav.more", icon: "widget"),
    ]

    var body: some View {
        RoleSpace(app: app) {
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
            .safeAreaInset(edge: .bottom) { DhBottomNav(items: items, selected: $tab) }
        }
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
