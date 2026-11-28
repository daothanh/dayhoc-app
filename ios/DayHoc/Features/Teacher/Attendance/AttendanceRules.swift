import Foundation

/// Trạng thái điểm danh. `rawValue` là giá trị của API (`AttendanceStatus`).
enum AttStatus: String, CaseIterable, Sendable {
    case present, late, excused, unexcused

    var absent: Bool { self == .excused || self == .unexcused }
}

/// Một hàng học sinh ở T04. `original` là giá trị đang lưu trên server (`nil` = chưa có dòng điểm danh);
/// `status` là giá trị đang hiển thị. `parentReported`: phụ huynh đã báo vắng trước (API chưa trả, để sẵn).
struct AttRow: Equatable, Sendable {
    let studentId: String
    let name: String
    var status: AttStatus
    var original: AttStatus?
    var note: String?
    var parentReported = false

    /// Khác giá trị đang lưu (chưa có dòng thì mặc định "Có mặt").
    var changed: Bool { status != (original ?? .present) }
}

func rowsOf(_ items: [AttendanceItemDTO]) -> [AttRow] {
    items.map {
        let server = $0.status.flatMap(AttStatus.init(rawValue:))
        return AttRow(studentId: $0.studentId, name: $0.fullName, status: server ?? .present, original: server, note: $0.note)
    }
}

/// Bộ đếm ở đầu T04. "Có mặt" không gồm "Muộn"; "Vắng" gồm cả có phép và không phép.
struct Counts: Equatable, Sendable {
    let present: Int
    let absent: Int
    let late: Int
}

func countsOf(_ rows: [AttRow]) -> Counts {
    Counts(present: rows.filter { $0.status == .present }.count,
           absent: rows.filter { $0.status.absent }.count,
           late: rows.filter { $0.status == .late }.count)
}

/// Số phụ huynh sẽ nhận báo vắng: học sinh giáo viên đánh vắng, trừ học sinh phụ huynh đã báo trước (US-3.2 AC2).
func notifyCount(_ rows: [AttRow]) -> Int { rows.filter { $0.status.absent && !$0.parentReported }.count }

enum SaveLabel: Equatable, Sendable {
    case save
    case notify(Int)
    case saveChanges
    case saveLocal
}

func saveLabel(_ rows: [AttRow], editMode: Bool, offline: Bool) -> SaveLabel {
    let n = notifyCount(rows)
    if offline { return .saveLocal }
    if editMode { return .saveChanges }
    return n > 0 ? .notify(n) : .save
}

/// Lần đầu: mutation cho mọi học sinh. Chế độ sửa: chỉ học sinh đổi. `newId` sinh `clientMutationId`, `now` là `clientUpdatedAt`.
func buildMutations(_ rows: [AttRow], editMode: Bool, newId: () -> String, now: String) -> [OutboxMutation] {
    rows.filter { !editMode || $0.changed }
        .map { OutboxMutation(clientMutationId: newId(), studentId: $0.studentId, status: $0.status.rawValue, note: $0.note, clientUpdatedAt: now) }
}

/// Chồng các lô của buổi lên danh sách server để giáo viên thấy điều mình đã lưu. Lô đang chờ: giá trị trong lô
/// coi như đã lưu (`original`), nên chỉ phần đổi thêm mới được gửi. Lô lỗi: chỉ hiện giá trị, `original` giữ của server
/// để gửi lại đủ.
func overlayPending(_ rows: [AttRow], batches: [OutboxBatch]) -> [AttRow] {
    var pending: [String: OutboxMutation] = [:]
    var failed: [String: OutboxMutation] = [:]
    for b in batches {
        for m in b.mutations {
            if b.state == .pending { pending[m.studentId] = m } else { failed[m.studentId] = m }
        }
    }
    return rows.map { r in
        var r = r
        if let m = failed[r.studentId], let s = AttStatus(rawValue: m.status) { r.status = s; r.note = m.note }
        if let m = pending[r.studentId], let s = AttStatus(rawValue: m.status) { r.status = s; r.original = s; r.note = m.note }
        return r
    }
}

struct Applied: Equatable, Sendable {
    let rows: [AttRow]
    let staleCount: Int
}

/// Áp kết quả từng mutation: `applied`/`duplicate` thì giá trị gửi đi thành giá trị đang lưu;
/// `stale` thì lấy `current` của server (bản mới hơn thắng). `results` cùng thứ tự với `mutations`.
func applyResults(_ rows: [AttRow], mutations: [OutboxMutation], results: [MutationResultDTO]) -> Applied {
    var byStudent = Dictionary(rows.map { ($0.studentId, $0) }, uniquingKeysWith: { a, _ in a })
    var stale = 0
    for (m, r) in zip(mutations, results) {
        guard let row = byStudent[m.studentId] else { continue }
        switch r.result {
        case "applied", "duplicate":
            if let s = AttStatus(rawValue: m.status) { byStudent[m.studentId] = { var x = row; x.original = s; return x }() }
        case "stale":
            stale += 1
            if let cur = r.current, let s = AttStatus(rawValue: cur.status) {
                byStudent[m.studentId] = { var x = row; x.status = s; x.original = s; x.note = cur.note; return x }()
            }
        default: break
        }
    }
    return Applied(rows: rows.compactMap { byStudent[$0.studentId] }, staleCount: stale)
}

/// Phản hồi 200 của một lô: xong, hoặc có phần tử bị từ chối vĩnh viễn (cửa sổ sửa đóng, không còn học sinh…).
enum BatchVerdict: Equatable, Sendable {
    case done
    case rejected(String)
}

func verdictOf(_ results: [MutationResultDTO]) -> BatchVerdict {
    results.first { $0.result == "rejected" }.map { .rejected($0.errorCode ?? "REJECTED") } ?? .done
}

/// Quyết định với lô khi gọi API lỗi: giữ lại thử sau, hoặc dừng (lỗi không thử lại được).
enum ErrorDecision: Equatable, Sendable {
    /// `countAttempt`: lỗi từ server (5xx…) mới tính vào số lần thử; mất mạng thì không.
    case keep(countAttempt: Bool)
    case drop(String)
}

let maxAttempts = 10

func decideOnError(_ e: APIError, attempts: Int) -> ErrorDecision {
    switch e.kind {
    case .offline, .timeout: return .keep(countAttempt: false)
    case .http where e.status == 401 || e.status == 429: return .keep(countAttempt: false)
    case .http where !e.retryable:
        return .drop(e.code.flatMap { $0.isEmpty ? nil : $0 } ?? "HTTP_\(e.status ?? 0)")
    default:
        return attempts + 1 >= maxAttempts ? .drop("MAX_ATTEMPTS") : .keep(countAttempt: true)
    }
}

/// Lỗi vĩnh viễn mà lô không còn ý nghĩa nữa (buổi đã hủy, quá hạn sửa, không thấy buổi): xóa lô.
/// Các lỗi khác (403, 422…) giữ lô ở trạng thái `failed` để giáo viên gửi lại hoặc sửa.
func isDeletableError(_ code: String) -> Bool {
    ["SESSION_CANCELLED", "ATTENDANCE_EDIT_WINDOW_CLOSED", "NOT_FOUND", "HTTP_404"].contains(code)
}

/// `2026-11-09T17:45:00.123+07:00`: có mili giây, đúng mẫu `date-time` của hợp đồng.
func formatIso(_ date: Date, _ tz: TimeZone) -> String {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    f.timeZone = tz
    return f.string(from: date)
}

/// Hành động chính của sheet Chi tiết buổi (T05a).
enum SheetAction: String, Sendable { case attend = "Attend", edit = "Edit", none = "None" }

func sheetAction(status: String, pending: Bool) -> SheetAction {
    if status == "cancelled" { return .none }
    return status == "taught" || pending ? .edit : .attend
}

/// "T2-T4-T6" từ thứ ISO của quy tắc lặp; không có quy tắc thì `nil`.
func repeatLabel(_ weekdays: [Int]) -> String? {
    let days = Set(weekdays.filter { (1...7).contains($0) }).sorted()
    return days.isEmpty ? nil : days.map { $0 == 7 ? "CN" : "T\($0 + 1)" }.joined(separator: "-")
}

/// Hạn xem lại: "Sửa lần cuối" lấy mốc server nhận gần nhất của các dòng.
func lastEditedTime(_ items: [AttendanceItemDTO]) -> Date? {
    items.compactMap { ($0.syncedAt ?? $0.clientUpdatedAt).flatMap(parseInstant) }.max()
}
