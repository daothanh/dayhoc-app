import SwiftUI

private let segments: [AttStatus] = [.present, .late, .excused, .unexcused]

/// [T04] Điểm danh (Figma A03–A09, A26–A28). Màn đẩy, không có bottom nav. `endsAt` lấy từ thẻ buổi ở T03.
struct AttendanceView: View {
    let model: AttendanceModel
    let endsAt: Date
    let timeZone: TimeZone
    let onClose: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(spacing: 0) {
            DhAppBar(
                verbatimTitle: title,
                subtitle: subtitle,
                onBack: { if model.canLeaveFreely { onClose() } else { model.askDiscard() } },
                leading: { EmptyView() },
                trailing: { EmptyView() }
            )
            if model.loading {
                VStack(spacing: 0) {
                    ForEach(0..<6, id: \.self) { _ in DhSkeletonRow().padding(.vertical, TeachHubTokens.Dimens.spacingControl) }
                    Spacer()
                }
                .padding(TeachHubTokens.Dimens.spacingPage)
                .accessibilityHidden(true)
            } else if model.loadFailed {
                ScrollView {
                    DhEmptyState(title: "attendance.loadFailed", description: nil, actionTitle: "today.retry") {
                        Task { await model.load() }
                    }
                }
            } else {
                if model.offline && model.readOnly == nil {
                    DhOfflineBanner(title: "attendance.offline.title", description: "attendance.offline.body")
                }
                list
                if let error = model.error { errorBar(error) }
                if model.readOnly == nil { actionBar }
            }
        }
        .dhScreen()
        .toolbar(.hidden, for: .navigationBar)
        .task { await model.load() }
        .task { await model.observeConnectivity() }
        .onChange(of: model.done) { _, done in if done { onClose() } }
        .alert("attendance.discard.title", isPresented: Binding(get: { model.confirmDiscard }, set: { if !$0 { model.stay() } })) {
            Button("attendance.discard.stay", role: .cancel) { model.stay() }
            Button("attendance.discard.confirm", role: .destructive) { Task { await model.discard() } }
        } message: {
            Text("attendance.discard.body")
        }
    }

    private var title: String {
        let name = model.className ?? String(localized: "attendance.title")
        return model.editMode ? String(localized: "attendance.titleEdit \(name)") : name
    }

    private var subtitle: String? {
        guard let start = model.startsAt else { return nil }
        return "\(formatShortDay(start, timeZone)) · \(formatTimeRange(start, endsAt, timeZone))"
    }

    private var list: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                Group {
                    if let ro = model.readOnly {
                        note(ro == .cancelled ? "attendance.readonly.cancelled" : "attendance.readonly.expired")
                    }
                    if let code = model.failedCode {
                        DhInfoBanner(
                            title: "attendance.syncFailed.title", description: errorText(code),
                            actionTitle: "attendance.resend", onAction: { Task { await model.resend() } }
                        )
                        .padding(.bottom, TeachHubTokens.Dimens.spacingControl)
                    }
                    if model.editMode && model.readOnly == nil {
                        DhInfoBanner(
                            title: model.lastEditedAt.map { "attendance.editBanner.title \(stamp($0))" } ?? "attendance.editBanner.none",
                            description: String(localized: "attendance.editBanner.body")
                        )
                        .padding(.bottom, TeachHubTokens.Dimens.spacingControl)
                    }
                    if model.readOnly == nil && !model.editMode { note("attendance.hint") }
                }
                Section {
                    Text("attendance.legend")
                        .dhText(TeachHubTokens.Typo.caption)
                        .foregroundStyle(colors.onSurfaceVariant)
                        .padding(.bottom, TeachHubTokens.Dimens.spacingControl)
                    ForEach(model.rows, id: \.studentId) { row in
                        StudentRow(
                            row: row, expanded: model.expandedId == row.studentId, enabled: model.readOnly == nil,
                            onToggle: { withAnimation(.easeInOut(duration: 0.15)) { model.toggle(row.studentId) } },
                            onSelect: { status in withAnimation(.easeInOut(duration: 0.15)) { model.select(row.studentId, status) } }
                        )
                    }
                } header: {
                    counters
                }
            }
            .padding(TeachHubTokens.Dimens.spacingPage)
        }
    }

    private func note(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .dhText(TeachHubTokens.Typo.body)
            .foregroundStyle(colors.onSurfaceVariant)
            .padding(.bottom, TeachHubTokens.Dimens.spacingControl)
    }

    /// Hàng bộ đếm dính trên khi cuộn (spec T04 mục 8).
    private var counters: some View {
        let c = model.counts
        return HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            DhChip(text: String(localized: "attendance.count.present \(c.present)"), style: .success)
            DhChip(text: String(localized: "attendance.count.absent \(c.absent)"), style: .danger)
            DhChip(text: String(localized: "attendance.count.late \(c.late)"), style: .warning)
            Spacer(minLength: 0)
        }
        .padding(.bottom, TeachHubTokens.Dimens.spacingControl)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.scaffoldBackground)
    }

    private func stamp(_ date: Date) -> String {
        let d = dayString(date, timeZone).split(separator: "-")
        return "\(formatTime(date, timeZone)) \(d[2])/\(d[1])"
    }

    private func errorText(_ code: String?) -> String {
        switch code {
        case "ATTENDANCE_EDIT_WINDOW_CLOSED": String(localized: "attendance.error.windowClosed")
        case "SESSION_CANCELLED": String(localized: "attendance.error.cancelled")
        case "NOT_FOUND": String(localized: "attendance.error.notFound")
        default: String(localized: "attendance.error.generic")
        }
    }

    private func errorBar(_ error: SaveError) -> some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            Text(errorText(error.code))
                .dhText(TeachHubTokens.Typo.caption)
                .foregroundStyle(colors.onErrorContainer)
                .frame(maxWidth: .infinity, alignment: .leading)
            if error.retry {
                DhSecondaryButton(title: "today.retry", fullWidth: false, small: true) { Task { await model.save() } }
            }
        }
        .padding(TeachHubTokens.Dimens.spacingContent)
        .background(colors.errorContainer)
    }

    /// Bottom Action Bar (Figma C13): nút Primary; chế độ sửa disabled tới khi có thay đổi.
    private var actionBar: some View {
        let text: LocalizedStringKey = switch model.label {
        case .save: "attendance.save"
        case .notify(let n): "attendance.saveNotify \(n)"
        case .saveChanges: "attendance.saveChanges"
        case .saveLocal: "attendance.saveLocal"
        }
        return DhPrimaryButton(title: text, loadingTitle: "attendance.saving", isLoading: model.saving) {
            Task { await model.save() }
        }
        .disabled(!model.canSave)
        .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
        .padding(.top, TeachHubTokens.Dimens.spacingContent)
        .padding(.bottom, TeachHubTokens.Dimens.spacingPage)
        .background(colors.surface)
        .overlay(alignment: .top) { Rectangle().fill(colors.outlineVariant).frame(height: 1.5) }
    }
}

private struct StudentRow: View {
    let row: AttRow
    let expanded: Bool
    let enabled: Bool
    let onToggle: () -> Void
    let onSelect: (AttStatus) -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: expanded || row.changed ? 12 : 0)
        VStack(spacing: 0) {
            VStack(spacing: TeachHubTokens.Dimens.spacingControl) {
                Button(action: onToggle) {
                    HStack(spacing: TeachHubTokens.Dimens.spacingContent) {
                        DhAvatar(name: row.name)
                        VStack(alignment: .leading, spacing: 0) {
                            Text(verbatim: row.name)
                                .dhText(expanded ? TeachHubTokens.Typo.bodyBold : TeachHubTokens.Typo.body)
                                .foregroundStyle(colors.onSurface)
                                .lineLimit(1)
                            if let sub = subtitle {
                                Text(sub).dhText(TeachHubTokens.Typo.caption).foregroundStyle(colors.onSurfaceVariant).lineLimit(1)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        chip
                    }
                    .frame(minHeight: 40)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .accessibilityElement(children: .combine)
                if expanded {
                    DhSegmented(
                        options: segments.map { statusName($0) },
                        selectedIndex: segments.firstIndex(of: row.status) ?? 0,
                        onSelect: { onSelect(segments[$0]) }
                    )
                }
            }
            .padding(TeachHubTokens.Dimens.spacingControl)
            if !expanded && !row.changed { Rectangle().fill(colors.surfaceContainerLow).frame(height: 1) }
        }
        .background(expanded ? colors.surfaceContainerLow : colors.surface, in: shape)
        .overlay { if row.changed && !expanded { shape.stroke(colors.primary, lineWidth: 1.5) } }
        .clipShape(shape)
    }

    private var subtitle: String? {
        if row.parentReported { return String(localized: "attendance.row.parentReported") }
        return row.changed ? String(localized: "attendance.row.changed") : nil
    }

    private var chip: DhChip {
        let style: DhChipStyle = switch row.status {
        case .present: .success
        case .late: .warning
        case .excused: .warningFilled
        case .unexcused: .dangerFilled
        }
        return DhChip(text: statusName(row.status), style: style)
    }
}

func statusName(_ s: AttStatus) -> String {
    switch s {
    case .present: String(localized: "attendance.status.present")
    case .late: String(localized: "attendance.status.late")
    case .excused: String(localized: "attendance.status.excused")
    case .unexcused: String(localized: "attendance.status.unexcused")
    }
}
