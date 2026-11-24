import Foundation
import Observation
import SwiftUI

/// [T01c] Vai trò đã có thì mở luôn; chưa có thì tạo (Giáo viên tạo không gian, US-0.10 AC2).
@Observable @MainActor
final class RolePickerModel: Identifiable {
    private(set) var selected: String?
    private(set) var busy = false
    var failed = false

    let store: MembershipStore
    private let onOpened: @MainActor (MembershipDTO) async -> Void

    init(store: MembershipStore, onOpened: @escaping @MainActor (MembershipDTO) async -> Void) {
        self.store = store
        self.onOpened = onOpened
    }

    var owned: Set<String> { Set(store.items.map(\.role)) }

    func select(_ role: String) {
        guard !busy else { return }
        if selected != role { store.resetPendingAdd() }
        selected = role
    }

    func proceed() async {
        guard let role = selected, !busy else { return }
        busy = true
        failed = false
        defer { busy = false }
        do {
            if let owned = store.items.first(where: { $0.role == role }) {
                store.select(owned)
                await onOpened(owned)
            } else {
                await onOpened(try await store.addRole(role))
            }
        } catch {
            failed = true
        }
    }
}

/// [T01c] "Bạn là…?" (Figma B04, B05, B15). `onBack` khác `nil` khi mở từ "Thêm vai trò".
struct RolePickerView: View {
    let model: RolePickerModel
    let api: APIClient
    var onBack: (() -> Void)?

    @State private var waitlist = false
    @Environment(\.dhColors) private var colors

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
                    Text("role.whoAreYou").font(.largeTitle.bold())
                    Text("role.intro").foregroundStyle(colors.onSurfaceVariant)
                    ForEach(Roles.all, id: \.self) { role in
                        let selected = model.selected == role
                        RoleCard(
                            title: Roles.name(role),
                            caption: Roles.description(role),
                            selected: selected,
                            tag: selected ? "role.selected" : model.owned.contains(role) ? "role.owned" : nil,
                            identifier: "role.\(role)"
                        ) { model.select(role) }
                    }
                    RoleCard(title: "role.centerOwner", caption: "role.centerOwnerDesc", comingSoon: true, identifier: "role.centerOwner") {
                        waitlist = true
                    }
                }
                .padding(TeachHubTokens.Dimens.spacingPage)
            }
            .safeAreaInset(edge: .bottom) {
                DhPrimaryButton(title: "role.continue", loadingTitle: "loading", isLoading: model.busy, identifier: "role.continue") {
                    Task { await model.proceed() }
                }
                .disabled(model.selected == nil)
                .padding(TeachHubTokens.Dimens.spacingPage)
                .background(colors.scaffoldBackground)
            }
            .dhScreen()
            .toolbar {
                if let onBack {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("back", systemImage: "chevron.backward", action: onBack)
                    }
                }
            }
            .navigationDestination(isPresented: $waitlist) {
                CenterWaitlistView(model: CenterWaitlistModel(api: api)) { waitlist = false }
            }
            .alert("role.addFailed", isPresented: Binding(get: { model.failed }, set: { model.failed = $0 })) {
                Button("ok", role: .cancel) {}
            }
        }
    }
}

/// `Surfaces/Card` của T01c: viền 1.5, chọn thì viền ink 2.5 + tag "Đã chọn"; sắp ra mắt viền đứt amber.
private struct RoleCard: View {
    let title: LocalizedStringResource
    let caption: LocalizedStringResource
    var selected = false
    var tag: LocalizedStringKey?
    var comingSoon = false
    /// Định danh cho UI test; `nil` thì không gắn.
    var identifier: String?
    let action: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.cardRadius)
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.bold())
                    Text(caption).font(.footnote).foregroundStyle(colors.onSurfaceVariant)
                }
                Spacer()
                if let tag {
                    Text(tag)
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, TeachHubTokens.Dimens.spacingControl)
                        .frame(height: 24)
                        .foregroundStyle(selected ? colors.onInverseSurface : colors.onSurface)
                        .background(selected ? colors.onSurface : colors.surface, in: Capsule())
                        .overlay(Capsule().stroke(selected ? .clear : colors.outlineVariant, lineWidth: 1.5))
                } else if comingSoon {
                    Text("role.comingSoon")
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, TeachHubTokens.Dimens.spacingControl)
                        .frame(height: 24)
                        .overlay(Capsule().stroke(colors.outlineVariant, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3])))
                }
            }
            .padding(TeachHubTokens.Dimens.spacingContent)
            .foregroundStyle(colors.onSurface)
            .background(colors.surface, in: shape)
            .overlay(
                shape.stroke(
                    comingSoon ? colors.warning : selected ? colors.onSurface : colors.outlineVariant,
                    style: StrokeStyle(lineWidth: selected ? 2.5 : 1.5, dash: comingSoon ? [6, 4] : [])
                )
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityIdentifier(identifier ?? "")
    }
}

// MARK: - [T01d] Đăng ký sớm Chủ trung tâm

@Observable @MainActor
final class CenterWaitlistModel {
    var centerName = ""
    var teacherCount = ""
    private(set) var showErrors = false
    private(set) var busy = false
    private(set) var sent = false
    var error: LocalizedStringKey?

    private let api: APIClient
    /// Khóa theo nội dung: gửi lại sau lỗi mạng không ghi hai lần; sửa ô thì là lệnh mới.
    @ObservationIgnored private var key: (body: CenterWaitlistRequest, key: String)?

    init(api: APIClient) { self.api = api }

    var nameValid: Bool { (1...120).contains(centerName.trimmingCharacters(in: .whitespaces).count) }
    var count: Int? {
        Int(teacherCount.trimmingCharacters(in: .whitespaces)).flatMap { (1...1000).contains($0) ? $0 : nil }
    }

    func submit() async {
        guard !busy else { return }
        guard nameValid, let count else {
            showErrors = true
            return
        }
        let body = CenterWaitlistRequest(centerName: centerName.trimmingCharacters(in: .whitespaces), teacherCount: count)
        let k = key.flatMap { $0.body == body ? $0.key : nil } ?? UUID().uuidString.lowercased()
        key = (body, k)
        busy = true
        error = nil
        defer { busy = false }
        do {
            try await api.send("POST", "center-waitlist", body: body, headers: ["Idempotency-Key": k])
            sent = true
        } catch let e as APIError {
            if !e.retryable { key = nil }
            if e.kind == .offline || e.kind == .timeout {
                error = "error.offline"
            } else if e.status == 409 {
                error = "waitlist.errorBusy"
            } else if e.status == 422 {
                error = "waitlist.errorValidation"
            } else {
                error = "waitlist.errorGeneric"
            }
        } catch {
            self.error = "waitlist.errorGeneric"
        }
    }
}

/// [T01d] Gửi xong quay về T01c để chọn Giáo viên.
struct CenterWaitlistView: View {
    @Bindable var model: CenterWaitlistModel
    let onBack: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
                if model.sent {
                    Text("waitlist.sent").font(.title2.bold())
                    Text("waitlist.sentBody")
                    DhSecondaryButton(title: "waitlist.trialAction", action: onBack)
                } else {
                    Text("waitlist.intro")
                    DhTextField(
                        label: "waitlist.centerName",
                        text: $model.centerName,
                        error: model.showErrors && !model.nameValid ? "waitlist.centerNameRequired" : nil
                    )
                    DhTextField(
                        label: "waitlist.teacherCount",
                        text: $model.teacherCount,
                        error: model.showErrors && model.count == nil ? "waitlist.teacherCountInvalid" : nil
                    )
                    .keyboardType(.numberPad)
                    Text("waitlist.trialHint")
                        .padding(TeachHubTokens.Dimens.spacingContent)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            colors.surfaceContainerLow,
                            in: RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.cardRadius)
                        )
                    if let error = model.error {
                        Text(error).font(.footnote).foregroundStyle(colors.error)
                    }
                    DhPrimaryButton(title: "waitlist.submit", isLoading: model.busy) {
                        Task { await model.submit() }
                    }
                    DhSecondaryButton(title: "waitlist.trialAction", action: onBack)
                }
            }
            .padding(TeachHubTokens.Dimens.spacingPage)
        }
        .dhScreen()
        .navigationTitle("waitlist.title")
        .navigationBarTitleDisplayMode(.inline)
    }
}
