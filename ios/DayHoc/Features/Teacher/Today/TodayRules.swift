import Foundation

/// Trạng thái hiển thị của thẻ buổi, theo thứ tự xếp (T03, Figma A01).
enum SessionKind: Int, Comparable, Sendable {
    case teaching, missed, upcoming, taught, cancelled

    static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
}

func classify(status: String, start: Date, end: Date, now: Date) -> SessionKind {
    switch status {
    case "taught": .taught
    case "cancelled": .cancelled
    default: now < start ? .upcoming : (now < end ? .teaching : .missed)
    }
}

struct TodaySession: Identifiable, Equatable, Sendable {
    let id: String
    let className: String
    let start: Date
    let end: Date
    var kind: SessionKind
    let enrolled: Int?
    let location: String?
    /// Chỉ có khi buổi đã điểm danh và lấy được số liệu; `nil` thì chip không kèm số.
    let attended: Int?
    let total: Int?
    /// Trạng thái server (`planned`, `taught`, `cancelled`) để tính lại `kind` khi giờ trôi qua.
    var status = "planned"
    var classId = ""
    /// Có lô điểm danh chờ đồng bộ trong outbox: thẻ hiện như đã điểm danh nhưng phụ huynh chưa được báo.
    var pending = false
    /// Lô của buổi bị server từ chối vĩnh viễn (giữ trong outbox): thẻ báo "Đồng bộ lỗi".
    var syncFailed = false
}

/// Tính lại `kind` theo `now` rồi xếp lại: thẻ tự chuyển Sắp tới → Đang dạy → Chưa điểm danh theo giờ.
func arrange(_ sessions: [TodaySession], now: Date, pendingIds: Set<String> = [], failedIds: Set<String> = []) -> [TodaySession] {
    let classified = sessions.map { s -> TodaySession in
        var s = s
        s.kind = classify(status: s.status, start: s.start, end: s.end, now: now)
        return s
    }
    return sortSessions(mergePending(classified, pendingIds: pendingIds, failedIds: failedIds))
}

/// Buổi có lô chờ đồng bộ (trừ buổi đã hủy trên server) thành thẻ Taught + `pending`; buổi có lô `failed`
/// (`failedIds`, tập con của `pendingIds`) thêm `syncFailed`. Cả hai tập đã lọc theo chủ hiện tại.
func mergePending(_ sessions: [TodaySession], pendingIds: Set<String>, failedIds: Set<String> = []) -> [TodaySession] {
    sessions.map { s in
        var s = s
        let hit = pendingIds.contains(s.id) && s.kind != .cancelled
        if hit { s.kind = .taught }
        s.pending = hit
        s.syncFailed = hit && failedIds.contains(s.id)
        return s
    }
}

/// Đang dạy → Chưa điểm danh → Sắp tới → Đã điểm danh → Đã hủy; cùng nhóm thì theo giờ bắt đầu.
func sortSessions(_ sessions: [TodaySession]) -> [TodaySession] {
    sessions.sorted { ($0.kind, $0.start) < ($1.kind, $1.start) }
}

// ISO8601DateFormatter an toàn đa luồng; Foundation chưa khai Sendable.
nonisolated(unsafe) private let plainISO = ISO8601DateFormatter()
nonisolated(unsafe) private let fractionalISO: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
}()

func parseInstant(_ iso: String) -> Date? { plainISO.date(from: iso) ?? fractionalISO.date(from: iso) }

private func hhmm(_ date: Date, _ tz: TimeZone) -> String {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = tz
    let c = cal.dateComponents([.hour, .minute], from: date)
    return String(format: "%02ld:%02ld", c.hour ?? 0, c.minute ?? 0)
}

func formatTime(_ date: Date, _ tz: TimeZone) -> String { hhmm(date, tz) }

func formatTimeRange(_ start: Date, _ end: Date, _ tz: TimeZone) -> String {
    "\(hhmm(start, tz))–\(hhmm(end, tz))"
}

/// Ngày theo giờ máy, dạng `yyyy-MM-dd` cho tham số `from`, `to`.
func dayString(_ date: Date, _ tz: TimeZone) -> String {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = tz
    let c = cal.dateComponents([.year, .month, .day], from: date)
    return String(format: "%04ld-%02ld-%02ld", c.year ?? 0, c.month ?? 0, c.day ?? 0)
}

private func weekdayIndex(_ date: Date, _ tz: TimeZone) -> Int {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = tz
    return (cal.component(.weekday, from: date) + 5) % 7 + 1 // 1 = thứ Hai … 7 = Chủ nhật
}

private let weekdayLong = ["", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"]

/// "Thứ Hai, 09/11/2026".
func formatDateLine(_ date: Date, _ tz: TimeZone) -> String {
    let d = dayString(date, tz).split(separator: "-")
    return "\(weekdayLong[weekdayIndex(date, tz)]), \(d[2])/\(d[1])/\(d[0])"
}

/// "Thứ Hai 09/11" cho dòng ngày của sheet Chi tiết buổi.
func formatSheetDay(_ date: Date, _ tz: TimeZone) -> String {
    let d = dayString(date, tz).split(separator: "-")
    return "\(weekdayLong[weekdayIndex(date, tz)]) \(d[2])/\(d[1])"
}

/// "T2 16/11" cho dòng "Buổi tiếp theo".
func formatShortDay(_ date: Date, _ tz: TimeZone) -> String {
    let i = weekdayIndex(date, tz)
    let d = dayString(date, tz).split(separator: "-")
    return "\(i == 7 ? "CN" : "T\(i + 1)") \(d[2])/\(d[1])"
}

/// "6.800.000 đ".
func formatVnd(_ amount: Int) -> String {
    let digits = Array(String(amount))
    var out = ""
    for (i, ch) in digits.enumerated() {
        if i > 0, (digits.count - i) % 3 == 0 { out.append(".") }
        out.append(ch)
    }
    return out + " đ"
}

/// Bước onboarding đã bỏ qua mà banner nhắc lại (US-0.2 AC2): chỉ thêm học sinh, đặt lịch học.
func skippedSetupSteps(_ onboarding: OnboardingDTO) -> [OnboardingStep] {
    [OnboardingStep.addStudents, .setSchedule].filter { step in
        onboarding.steps.contains { $0.step == step.rawValue && $0.status == "skipped" }
    }
}

struct Overdue: Equatable, Sendable {
    let count: Int
    let total: Int
}

/// Học viên có hóa đơn quá hạn: số dòng và tổng nợ của các dòng đó.
func overdueSummary(_ rows: [ReceivableRowDTO]) -> Overdue {
    let late = rows.filter { $0.overdueDays > 0 }
    return Overdue(count: late.count, total: late.reduce(0) { $0 + $1.outstandingAmount })
}

/// Buổi `planned` sớm nhất (cho dòng "Buổi tiếp theo" của Empty).
func earliestPlanned(_ sessions: [SessionDTO]) -> SessionDTO? {
    sessions.filter { $0.status == "planned" }
        .compactMap { s in parseInstant(s.startsAt).map { (s, $0) } }
        .min { $0.1 < $1.1 }?.0
}

/// Hai biến thể trạng thái "Hôm nay không có buổi": A24 còn buổi kế tiếp, A35 chưa có lịch nào.
enum TodayEmptyVariant: Equatable, Sendable { case nextSession, noSchedule }

func todayEmptyVariant(next: NextSession?) -> TodayEmptyVariant { next == nil ? .noSchedule : .nextSession }

/// Màn A35 đã có nút "Tạo lịch" cùng đích với bước `set_schedule`, nên banner bỏ bước đó (hết bước thì ẩn).
func bannerSteps(_ steps: [OnboardingStep], sessionsEmpty: Bool, next: NextSession?) -> [OnboardingStep] {
    guard sessionsEmpty, todayEmptyVariant(next: next) == .noSchedule else { return steps }
    return steps.filter { $0 != .setSchedule }
}
