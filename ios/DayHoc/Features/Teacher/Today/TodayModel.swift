import Foundation
import Observation

/// Buổi gần nhất sau hôm nay, dùng cho mô tả Empty.
struct NextSession: Equatable, Sendable {
    let className: String
    let day: Date
    let time: String
}

struct TodayData: Equatable, Sendable {
    let date: Date
    let sessions: [TodaySession]
    let next: NextSession?
    let setupSteps: [OnboardingStep]
    /// `nil` khi không lấy được công nợ: ẩn cả section "Cần xử lý".
    let overdue: Overdue?
    let unread: Int
}

/// [T03] Hôm nay của giáo viên. Sessions và classes là bắt buộc; onboarding, receivables, notifications
/// và attendance lỗi thì phần đó ẩn hoặc rơi về chip không số. Chưa có cache bền (NA-07).
/// Cùng ca kiểm thử với `TodayViewModelTest` bên Android.
@Observable @MainActor
final class TodayModel {
    private(set) var data: TodayData?
    /// Lần tải đầu, chưa có dữ liệu: hiện skeleton.
    private(set) var loading = true
    /// Tải lại khi đã có dữ liệu (kéo xuống, quay lại màn).
    private(set) var refreshing = false
    /// Lần tải đầu lỗi: hiện trạng thái lỗi + Thử lại.
    private(set) var failed = false
    /// Tải lại lỗi do mạng khi đang có dữ liệu cũ: giữ dữ liệu, hiện banner ngoại tuyến.
    private(set) var offline = false

    var syncing: Bool { loading || refreshing }

    private let source: any TodayDataSource
    private let now: () -> Date
    private let tz: TimeZone
    @ObservationIgnored private var inFlight = false

    init(source: any TodayDataSource, now: @escaping () -> Date = { .now }, timeZone: TimeZone = .current) {
        self.source = source
        self.now = now
        tz = timeZone
    }

    var timeZone: TimeZone { tz }

    /// Lần đầu và mọi lần quay lại màn: tải mới; đang tải thì bỏ qua.
    func refresh() async {
        guard !inFlight else { return }
        inFlight = true
        defer { inFlight = false }
        let first = data == nil
        loading = first
        refreshing = !first
        failed = false
        do {
            data = try await fetch()
            offline = false
            loading = false
            refreshing = false
        } catch is CancellationError {
            // View dựng lại hủy `.task`: lần chạy mới sẽ tải.
            loading = first
            refreshing = false
        } catch {
            loading = false
            refreshing = false
            if first {
                failed = true
            } else {
                let kind = (error as? APIError)?.kind
                offline = kind == .offline || kind == .timeout
            }
        }
    }

    private func fetch() async throws -> TodayData {
        let source = source
        let current = now()
        let day = dayString(current, tz)
        async let sessionsTask = source.sessions(from: day, to: day)
        async let classesTask = source.classes()
        async let onboardingTask = Self.soft { try await source.onboarding() }
        async let receivablesTask = Self.soft { try await source.receivables() }
        async let unreadTask = Self.soft { try await source.unreadCount() }

        let dtos = try await sessionsTask
        let byClass = Dictionary(try await classesTask.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        // Buổi đã điểm danh: lấy số có mặt / sĩ số; lỗi thì chip không số.
        var attendance: [String: AttendanceDTO] = [:]
        await withTaskGroup(of: (String, AttendanceDTO?).self) { group in
            for s in dtos where s.status == "taught" {
                group.addTask { (s.id, await Self.soft { try await source.attendance(sessionId: s.id) }) }
            }
            for await (id, a) in group { attendance[id] = a }
        }
        let list: [TodaySession] = dtos.compactMap { dto in
            guard let start = parseInstant(dto.startsAt), let end = parseInstant(dto.endsAt) else { return nil }
            let cls = byClass[dto.classId]
            let att = attendance[dto.id]
            return TodaySession(
                id: dto.id,
                className: dto.className,
                start: start,
                end: end,
                kind: classify(status: dto.status, start: start, end: end, now: current),
                enrolled: cls?.enrolledCount,
                location: cls?.location.flatMap { $0.isEmpty ? nil : $0 },
                attended: att.map { $0.summary.present + $0.summary.late },
                total: att?.items.count
            )
        }
        let next = list.isEmpty ? await nextSession(after: current) : nil
        let onboarding = await onboardingTask
        let receivables = await receivablesTask
        let unread = await unreadTask
        return TodayData(
            date: current,
            sessions: sortSessions(list),
            next: next,
            setupSteps: onboarding.map(skippedSetupSteps) ?? [],
            // shortcut: chỉ trang đầu 100 học viên nợ, nâng cấp khi backend có endpoint tổng hợp
            overdue: receivables.map(overdueSummary),
            unread: unread ?? 0
        )
    }

    private func nextSession(after current: Date) async -> NextSession? {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        guard let from = cal.date(byAdding: .day, value: 1, to: current),
              let to = cal.date(byAdding: .day, value: 14, to: current)
        else { return nil }
        guard let items = await Self.soft({ try await source.sessions(from: dayString(from, tz), to: dayString(to, tz)) }),
              let s = earliestPlanned(items), let start = parseInstant(s.startsAt)
        else { return nil }
        return NextSession(className: s.className, day: start, time: formatTime(start, tz))
    }

    /// Lỗi API của phần không bắt buộc: bỏ qua, trả `nil`. Hủy tác vụ thì vẫn nhả ra ngoài.
    private nonisolated static func soft<T: Sendable>(_ block: @Sendable () async throws -> T) async -> T? {
        try? await block()
    }
}
