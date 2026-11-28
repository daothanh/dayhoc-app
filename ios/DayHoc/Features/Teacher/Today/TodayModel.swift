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
    /// Đếm lần làm mới lỗi không do mạng khi đã có dữ liệu (5xx, sai định dạng): màn báo toast mỗi lần tăng.
    private(set) var refreshFailures = 0
    /// Lần tải đầu lỗi: hiện trạng thái lỗi + Thử lại.
    private(set) var failed = false
    /// Tải lại lỗi do mạng khi đang có dữ liệu cũ: giữ dữ liệu, hiện banner ngoại tuyến.
    private(set) var offline = false

    var syncing: Bool { loading || refreshing }

    private let source: any TodayDataSource
    private let now: () -> Date
    private let tz: TimeZone
    @ObservationIgnored private var task: Task<Void, Never>?

    // shortcut: dùng múi giờ máy thay cho múi giờ tenant, đổi khi membership có timezone
    init(source: any TodayDataSource, now: @escaping () -> Date = { .now }, timeZone: TimeZone = .autoupdatingCurrent) {
        self.source = source
        self.now = now
        tz = timeZone
    }

    var timeZone: TimeZone { tz }

    /// Lần đầu và mọi lần quay lại màn: tải mới. Lần gọi mới hủy lần đang chạy (đổi tab, về nền rồi quay lại),
    /// nên không có lần nào thoát sớm làm skeleton kẹt; lần bị hủy không ghi gì.
    func refresh() async {
        task?.cancel()
        let t = Task { await load() }
        task = t
        await t.value
    }

    private func load() async {
        let first = data == nil
        loading = first
        refreshing = !first
        failed = false
        do {
            let fresh = try await fetch()
            try Task.checkCancellation()
            data = fresh
            offline = false
            loading = false
            refreshing = false
        } catch {
            // Bị hủy (lần tải mới đã thay chỗ): không đụng trạng thái, lần mới tự đặt.
            if Task.isCancelled || error is CancellationError { return }
            loading = false
            refreshing = false
            if first {
                failed = true
            } else if let kind = (error as? APIError)?.kind, kind == .offline || kind == .timeout {
                offline = true
            } else {
                refreshFailures += 1
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
        try await withThrowingTaskGroup(of: (String, AttendanceDTO?).self) { group in
            for s in dtos where s.status == "taught" {
                group.addTask { (s.id, try await Self.soft { try await source.attendance(sessionId: s.id) }) }
            }
            for try await (id, a) in group { attendance[id] = a }
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
                total: att?.items.count,
                status: dto.status
            )
        }
        let next = list.isEmpty ? try await nextSession(after: current) : nil
        let onboarding = try await onboardingTask
        let receivables = try await receivablesTask
        let unread = try await unreadTask
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

    private func nextSession(after current: Date) async throws -> NextSession? {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        guard let from = cal.date(byAdding: .day, value: 1, to: current),
              let to = cal.date(byAdding: .day, value: 14, to: current)
        else { return nil }
        guard let items = try await Self.soft({ try await source.sessions(from: dayString(from, tz), to: dayString(to, tz)) }),
              let s = earliestPlanned(items), let start = parseInstant(s.startsAt)
        else { return nil }
        return NextSession(className: s.className, day: start, time: formatTime(start, tz))
    }

    /// Lỗi API của phần không bắt buộc: bỏ qua, trả `nil`. Hủy tác vụ thì ném lại để không ghi dữ liệu thiếu.
    private nonisolated static func soft<T: Sendable>(_ block: @Sendable () async throws -> T) async throws -> T? {
        do {
            return try await block()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return nil
        }
    }
}
