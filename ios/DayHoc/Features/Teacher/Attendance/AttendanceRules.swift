import Foundation

/// Trạng thái điểm danh. `rawValue` là giá trị của API (`AttendanceStatus`).
enum AttStatus: String, CaseIterable, Sendable {
    case present, late, excused, unexcused

    var absent: Bool { self == .excused || self == .unexcused }
}

/// Một hàng học sinh ở T04. `original` là giá trị đang lưu trên server (`nil` = chưa có dòng điểm danh);
/// `status` là giá trị đang hiển thị. `parentReported`: phụ huynh đã báo vắng trước (điền sẵn, không tính vào số báo).
struct AttRow: Equatable, Sendable {
    let studentId: String
    let name: String
    var status: AttStatus
    var original: AttStatus?
    var note: String?
    var parentReported = false
    /// Lý do phụ huynh báo: `sick` | `family` | `other`; chỉ có khi `parentReported`.
    var parentReason: String?

    /// Khác giá trị đang lưu (chưa có dòng thì mặc định "Có mặt").
    var changed: Bool { status != (original ?? .present) }
}

func rowsOf(_ items: [AttendanceItemDTO]) -> [AttRow] {
    items.map {
        let server = $0.status.flatMap(AttStatus.init(rawValue:))
        // Phụ huynh đã báo mà chưa có dòng điểm danh: điền sẵn "Vắng có phép" (US-3.1 AC5).
        let prefilled: AttStatus? = $0.parentReport != nil ? .excused : nil
        return AttRow(studentId: $0.studentId, name: $0.fullName, status: server ?? prefilled ?? .present, original: server ?? prefilled, note: $0.note,
                      parentReported: $0.parentReport != nil, parentReason: $0.parentReport?.reason)
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

/// Áp kết quả từng mutation, ghép theo `clientMutationId` (không theo thứ tự): `applied`/`duplicate` thì giá trị gửi đi
/// thành giá trị đang lưu; `stale` thì lấy `current` của server (bản mới hơn thắng); `rejected` thì giữ nguyên hàng.
func applyResults(_ rows: [AttRow], mutations: [OutboxMutation], results: [MutationResultDTO]) -> Applied {
    var byStudent = Dictionary(rows.map { ($0.studentId, $0) }, uniquingKeysWith: { a, _ in a })
    let byId = Dictionary(results.map { ($0.clientMutationId, $0) }, uniquingKeysWith: { a, _ in a })
    var stale = 0
    for m in mutations {
        guard let r = byId[m.clientMutationId], var row = byStudent[m.studentId] else { continue }
        switch r.result {
        case "applied", "duplicate":
            if let s = AttStatus(rawValue: m.status) { row.original = s; byStudent[m.studentId] = row }
        case "stale":
            stale += 1
            if let cur = r.current, let s = AttStatus(rawValue: cur.status) {
                row.status = s
                row.original = s
                row.note = cur.note
                byStudent[m.studentId] = row
            }
        default: break
        }
    }
    return Applied(rows: rows.compactMap { byStudent[$0.studentId] }, staleCount: stale)
}

let editWindowClosed = "ATTENDANCE_EDIT_WINDOW_CLOSED"

/// Phản hồi 200 của một lô. Kết quả theo từng phần tử: chỉ "cửa sổ sửa đóng" làm cả buổi không sửa được nữa
/// (`rejected`); phần tử `rejected` mã khác (học sinh không còn trong lớp, gói chỉ đọc…) không làm hỏng lô: lô vẫn xong
/// (`done`) và các học sinh đó được báo riêng (`rejectedStudentIds`).
enum BatchVerdict: Equatable, Sendable {
    case done
    case rejected(String)
}

func verdictOf(_ results: [MutationResultDTO]) -> BatchVerdict {
    results.contains { $0.result == "rejected" && $0.errorCode == editWindowClosed } ? .rejected(editWindowClosed) : .done
}

/// Học sinh có phần tử `rejected` (không ghi được) trong phản hồi.
func rejectedStudentIds(_ results: [MutationResultDTO]) -> [String] {
    results.filter { $0.result == "rejected" }.map(\.studentId)
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

/// Lỗi vĩnh viễn mà lô không còn ý nghĩa nữa (buổi đã hủy, quá hạn sửa, `NOT_FOUND`): xóa lô. 404 không kèm mã lỗi
/// (`HTTP_404`, vd. proxy trả) thì không xóa. Các lỗi khác (403, 422…, đủ `maxAttempts` lần lỗi server) giữ lô ở trạng
/// thái `failed` để giáo viên gửi lại hoặc sửa.
func isDeletableError(_ code: String) -> Bool {
    ["SESSION_CANCELLED", editWindowClosed, "NOT_FOUND"].contains(code)
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

/// Lần sửa cuối của buổi: giờ và tên người ghi, lấy từ CÙNG một dòng.
struct LastEdit: Equatable, Sendable {
    let at: Date
    let by: String?
}

/// Dòng mới nhất theo `syncedAt ?? clientUpdatedAt`; hòa giờ thì lấy `studentId` nhỏ nhất (cả hai nền tảng cùng kết quả).
/// `by` là `nil` khi server không trả tên; không lấy tên của dòng khác.
func lastEdit(_ items: [AttendanceItemDTO]) -> LastEdit? {
    let c = items.compactMap { i in (i.syncedAt ?? i.clientUpdatedAt).flatMap(parseInstant).map { (at: $0, id: i.studentId, by: i.recordedByName) } }
    return c.max { $0.at != $1.at ? $0.at < $1.at : $0.id > $1.id }.map { LastEdit(at: $0.at, by: $0.by) }
}

/// Lý do phụ huynh báo (`AbsenceReason`); giá trị lạ → `nil` (UI chỉ hiện "PH đã báo vắng trước").
enum ParentReason: String, Sendable {
    case sick, family, other
}
