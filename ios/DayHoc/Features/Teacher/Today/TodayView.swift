import SwiftUI

/// [T03] Hôm nay: nội dung dưới App Bar. Các màn đích (T04, T05a, T03b, T11) chưa có nên [onComingSoon]
/// báo "đang hoàn thiện".
struct TodayView: View {
    let model: TodayModel
    let onComingSoon: () -> Void
    let onOpenSchedule: () -> Void
    let onContinueSetup: (OnboardingStep) -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(spacing: 0) {
            if model.offline {
                DhOfflineBanner(title: "today.offline.title", description: "today.offline.body")
            }
            if let data = model.data {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
                        Text(formatDateLine(data.date, model.timeZone))
                            .dhText(TeachHubTokens.Typo.caption)
                            .foregroundStyle(colors.onSurfaceVariant)
                        if data.sessions.isEmpty {
                            empty(data.next)
                        } else {
                            section(String(localized: "today.section.sessions \(data.sessions.count)"))
                            ForEach(data.sessions) { sessionCard($0) }
                        }
                        if !data.setupSteps.isEmpty { setupBanner(data.setupSteps) }
                        if let overdue = data.overdue, overdue.count > 0 {
                            section(String(localized: "today.section.todo"))
                            DhListRow(
                                title: "today.overdue.title",
                                subtitle: String(localized: "today.overdue.total \(formatVnd(overdue.total))"),
                                onTap: onComingSoon
                            ) { DhChip(text: "\(overdue.count)", style: .counter) }
                        }
                    }
                    .padding(TeachHubTokens.Dimens.spacingPage)
                }
                .refreshable { await model.refresh() }
            } else if model.failed {
                ScrollView {
                    DhEmptyState(title: "today.loadFailed", description: nil, actionTitle: "today.retry") {
                        Task { await model.refresh() }
                    }
                }
            } else {
                skeleton
            }
        }
    }

    private func section(_ text: String) -> some View {
        Text(text.uppercased()).dhText(TeachHubTokens.Typo.section).foregroundStyle(colors.onSurfaceVariant)
    }

    private var skeleton: some View {
        VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
            DhSkeletonText()
            ForEach(0..<3, id: \.self) { _ in DhSkeletonCard() }
            ForEach(0..<3, id: \.self) { _ in DhSkeletonRow() }
            Spacer()
        }
        .padding(TeachHubTokens.Dimens.spacingPage)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func sessionCard(_ s: TodaySession) -> some View {
        let emphasis = s.kind == .teaching || s.kind == .missed
        let style: DhCardStyle = emphasis ? .emphasis : (s.kind == .upcoming ? .default : .muted)
        if emphasis {
            // Nút "Điểm danh" nằm trong thẻ nên cả thẻ không bọc Button (tránh Button lồng Button).
            DhCard(style: style) { cardContent(s, emphasis: true) }.onTapGesture(perform: onComingSoon)
        } else {
            DhCard(style: style, onTap: onComingSoon) { cardContent(s, emphasis: false) }
        }
    }

    @ViewBuilder
    private func cardContent(_ s: TodaySession, emphasis: Bool) -> some View {
        let parts = [formatTimeRange(s.start, s.end, model.timeZone)]
            + (s.enrolled.map { [String(localized: "today.students \($0)")] } ?? [])
            + (s.location.map { [$0] } ?? [])
        HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            VStack(alignment: .leading, spacing: 0) {
                Text(s.className).dhText(TeachHubTokens.Typo.bodyBold).foregroundStyle(colors.onSurface).lineLimit(1)
                Text(parts.joined(separator: " · "))
                    .dhText(TeachHubTokens.Typo.caption)
                    .foregroundStyle(colors.onSurfaceVariant)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            chip(s)
        }
        if emphasis { DhPrimaryButton(title: "today.attendance", action: onComingSoon) }
    }

    private func chip(_ s: TodaySession) -> DhChip {
        switch s.kind {
        case .teaching: DhChip(text: String(localized: "today.chip.teaching"), style: .filled)
        case .missed: DhChip(text: String(localized: "today.chip.missed"), style: .filled)
        case .upcoming: DhChip(text: String(localized: "today.chip.upcoming"))
        case .cancelled: DhChip(text: String(localized: "today.chip.cancelled"))
        case .taught:
            if let a = s.attended, let t = s.total {
                DhChip(text: String(localized: "today.chip.taughtCount \(a) \(t)"), style: .success)
            } else {
                DhChip(text: String(localized: "today.chip.taught"), style: .success)
            }
        }
    }

    private func setupBanner(_ steps: [OnboardingStep]) -> some View {
        let names = steps.map { $0 == .addStudents ? String(localized: "today.setup.addStudents") : String(localized: "today.setup.setSchedule") }
        return DhInfoBanner(
            title: "today.setup.title \(steps.count)",
            description: names.joined(separator: " · "),
            actionTitle: "today.setup.continue",
            onAction: { onContinueSetup(steps[0]) }
        )
    }

    private func empty(_ next: NextSession?) -> some View {
        let description = next.map {
            String(localized: "today.empty.next \($0.className) \(formatShortDay($0.day, model.timeZone)) \($0.time)")
        } ?? String(localized: "today.empty.none")
        return DhEmptyState(title: "today.empty.title", description: description, actionTitle: "today.empty.action", action: onOpenSchedule)
    }
}

/// Chip đồng bộ nét đứt + chuông có chấm khi còn thông báo chưa đọc (App Bar `Root`).
struct TodayAppBarActions: View {
    let model: TodayModel
    let onBell: () -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingInline) {
            DhChip(
                text: String(localized: model.offline ? "today.sync.offline" : (model.syncing ? "today.sync.busy" : "today.sync.done")),
                style: .dashed
            )
            Button(action: onBell) {
                Image(systemName: "bell")
                    .font(.system(size: 20))
                    .foregroundStyle(colors.onSurface)
                    .frame(width: 44, height: 44)
                    .overlay(alignment: .topTrailing) {
                        if (model.data?.unread ?? 0) > 0 {
                            Circle().fill(colors.onErrorContainer).frame(width: 8, height: 8)
                                .overlay(Circle().stroke(colors.scaffoldBackground, lineWidth: 1.5))
                                .padding(.top, 10).padding(.trailing, 10)
                        }
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("today.notifications"))
        }
    }
}
