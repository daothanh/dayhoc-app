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
    let kind: SessionKind
    let enrolled: Int?
    let location: String?
    /// Chỉ có khi buổi đã điểm danh và lấy được số liệu; `nil` thì chip không kèm số.
    let attended: Int?
    let total: Int?
}

/// Đang dạy → Chưa điểm danh → Sắp tới → Đã điểm danh → Đã hủy; cùng nhóm thì theo giờ bắt đầu.
func sortSessions(_ sessions: [TodaySession]) -> [TodaySession] {
    sessions.sorted { ($0.kind, $0.start) < ($1.kind, $1.start) }
}

func parseInstant(_ iso: String) -> Date? {
    let plain = ISO8601DateFormatter()
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return plain.date(from: iso) ?? fractional.date(from: iso)
}

private func hhmm(_ date: Date, _ tz: TimeZone) -> String {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = tz
    let c = cal.dateComponents([.hour, .minute], from: date)
    return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
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
    return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
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
