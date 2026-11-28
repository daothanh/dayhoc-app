import SwiftUI

/// [T03] Hôm nay: nội dung dưới App Bar. Chạm thẻ → `onSession` (sheet T05a); nút "Điểm danh" và thẻ đã điểm danh →
/// `onAttendance` (T04). Các màn đích còn lại (T03b, T11) chưa có nên `onComingSoon` báo "đang hoàn thiện".
struct TodayView: View {
    let model: TodayModel
    let onComingSoon: () -> Void
    let onSession: (TodaySession) -> Void
    let onAttendance: (TodaySession) -> Void
    let onOpenSchedule: () -> Void
    let onContinueSetup: (OnboardingStep) -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(spacing: 0) {
            if model.offline {
                DhOfflineBanner(title: "today.offline.title", description: "today.offline.body")
            }
            if let data = model.data {
                // Giờ trôi qua thì thẻ buổi tự chuyển trạng thái; sang ngày mới thì tải lại.
                TimelineView(.everyMinute) { context in
                    content(data, now: context.date)
                        .onChange(of: dayString(context.date, model.timeZone)) { _, day in
                            if day != dayString(data.date, model.timeZone) { Task { await model.refresh() } }
                        }
                }
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

    private func content(_ data: TodayData, now: Date) -> some View {
        let sessions = arrange(data.sessions, now: now, pendingIds: model.pendingSessions, failedIds: model.failedSessions)
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
                Text(formatDateLine(data.date, model.timeZone))
                    .dhText(TeachHubTokens.Typo.caption)
                    .foregroundStyle(colors.onSurfaceVariant)
                if sessions.isEmpty {
                    empty(data.next)
                } else {
                    section(String(localized: "today.section.sessions \(sessions.count)"))
                    ForEach(sessions) { sessionCard($0) }
                }
                let setup = bannerSteps(data.setupSteps, sessionsEmpty: sessions.isEmpty, next: data.next)
                if !setup.isEmpty { setupBanner(setup) }
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
        switch s.kind {
        case .teaching, .missed:
            // Nút "Điểm danh" nằm trong thẻ: vùng bấm của thẻ là phần tiêu đề (Button riêng), không lồng Button trong Button.
            DhCard(style: .emphasis) {
                Button { onSession(s) } label: { header(s) }.buttonStyle(.plain)
                DhPrimaryButton(title: "today.attendance") { onAttendance(s) }
            }
        case .upcoming:
            DhCard(style: .default, onTap: { onSession(s) }) { header(s) }
        case .taught:
            DhCard(style: .muted, onTap: { onAttendance(s) }) {
                header(s)
                // Lô chờ đồng bộ: phụ huynh chưa được báo (US-3.5), đọc từ outbox nên không cần mạng.
                if s.syncFailed {
                    DhChip(text: String(localized: "today.chip.syncFailed"), style: .danger)
                } else if s.pending {
                    DhChip(text: String(localized: "today.chip.pending"), style: .dashed)
                }
            }
        case .cancelled:
            DhCard(style: .muted, onTap: { onSession(s) }) { header(s) }
        }
    }

    private func header(_ s: TodaySession) -> some View {
        let parts = [formatTimeRange(s.start, s.end, model.timeZone)]
            + (s.enrolled.map { [String(localized: "today.students \($0)")] } ?? [])
            + (s.location.map { [$0] } ?? [])
        return HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            VStack(alignment: .leading, spacing: 0) {
                Text(s.className).dhText(TeachHubTokens.Typo.bodyBold).foregroundStyle(colors.onSurface).lineLimit(1)
                Text(parts.joined(separator: " · "))
                    .dhText(TeachHubTokens.Typo.caption)
                    .foregroundStyle(colors.onSurfaceVariant)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let chip = chip(s) { chip }
        }
        .contentShape(Rectangle())
    }

    private func chip(_ s: TodaySession) -> DhChip? {
        switch s.kind {
        case .teaching: DhChip(text: String(localized: "today.chip.teaching"), style: .filled)
        case .missed: DhChip(text: String(localized: "today.chip.missed"), style: .filled)
        case .upcoming: DhChip(text: String(localized: "today.chip.upcoming"))
        case .cancelled: DhChip(text: String(localized: "today.chip.cancelled"))
        case .taught where s.pending: nil
        case .taught:
            if let a = s.attended, let t = s.total {
                DhChip(text: String(localized: "today.chip.taughtCount \(a) \(t)"), style: .success)
            } else {
                DhChip(text: String(localized: "today.chip.taught"), style: .success)
            }
        }
    }

    private func setupBanner(_ steps: [OnboardingStep]) -> some View {
        let names = steps.map { step -> String in
            switch step {
            case .addStudents: String(localized: "today.setup.addStudents")
            default: String(localized: "today.setup.setSchedule")
            }
        }
        return DhInfoBanner(
            title: "today.setup.title \(steps.count)",
            description: names.joined(separator: " · "),
            actionTitle: "today.setup.continue",
            onAction: { onContinueSetup(steps[0]) }
        )
    }

    @ViewBuilder
    private func empty(_ next: NextSession?) -> some View {
        switch todayEmptyVariant(next: next) {
        case .nextSession:
            let description = next.map {
                String(localized: "today.empty.next \($0.className) \(formatShortDay($0.day, model.timeZone)) \($0.time)")
            }
            DhEmptyState(title: "today.empty.title", description: description, actionTitle: "today.empty.action", action: onOpenSchedule)
        case .noSchedule:
            DhEmptyState(
                title: "today.empty.title",
                description: String(localized: "today.empty.none"),
                actionTitle: "today.empty.create",
                illustration: .calendarPlus,
                action: { onContinueSetup(.setSchedule) }
            )
        }
    }
}
