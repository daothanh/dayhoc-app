package vn.dayhoc.app.teacher.attendance

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.teacher.today.toInstantOrNull

/** Trạng thái điểm danh. [wire] là giá trị của API (`AttendanceStatus`). */
enum class AttStatus(val wire: String) {
    Present("present"), Late("late"), Excused("excused"), Unexcused("unexcused");

    val absent get() = this == Excused || this == Unexcused

    companion object {
        fun fromWire(s: String?): AttStatus? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * Một hàng học sinh ở T04. [original] là giá trị đang lưu trên server (`null` = chưa có dòng điểm danh);
 * [status] là giá trị đang hiển thị. [parentReported]: phụ huynh đã báo vắng trước (điền sẵn, không tính vào số báo).
 */
data class AttRow(
    val studentId: String,
    val name: String,
    val status: AttStatus,
    val original: AttStatus? = null,
    val note: String? = null,
    val parentReported: Boolean = false,
    /** Lý do phụ huynh báo: `sick` | `family` | `other`; chỉ có khi [parentReported]. */
    val parentReason: String? = null,
) {
    /** Khác giá trị đang lưu (chưa có dòng thì mặc định "Có mặt"). */
    val changed get() = status != (original ?: AttStatus.Present)
}

fun rowsOf(items: List<AttendanceItemDto>): List<AttRow> = items.map {
    val server = AttStatus.fromWire(it.status)
    val report = it.parentReport
    // Phụ huynh đã báo mà chưa có dòng điểm danh: điền sẵn "Vắng có phép" (US-3.1 AC5), coi như giá trị gốc (không tính là đã đổi);
    // lô đầu vẫn gửi mọi học sinh nên giá trị này lên server.
    val prefilled = if (report != null) AttStatus.Excused else null
    AttRow(it.studentId, it.fullName, server ?: prefilled ?: AttStatus.Present, server ?: prefilled, it.note, report != null, report?.reason)
}

/** Bộ đếm ở đầu T04. "Có mặt" không gồm "Muộn"; "Vắng" gồm cả có phép và không phép. */
data class Counts(val present: Int, val absent: Int, val late: Int)

fun countsOf(rows: List<AttRow>) = Counts(
    present = rows.count { it.status == AttStatus.Present },
    absent = rows.count { it.status.absent },
    late = rows.count { it.status == AttStatus.Late },
)

/** Số phụ huynh sẽ nhận báo vắng: học sinh giáo viên đánh vắng, trừ học sinh phụ huynh đã báo trước (US-3.2 AC2). */
fun notifyCount(rows: List<AttRow>): Int = rows.count { it.status.absent && !it.parentReported }

/** Nhãn nút lưu: [n] chỉ dùng ở [Notify]. */
sealed interface SaveLabel {
    data object Save : SaveLabel
    data class Notify(val n: Int) : SaveLabel
    data object SaveChanges : SaveLabel
    data object SaveLocal : SaveLabel
}

fun saveLabel(rows: List<AttRow>, editMode: Boolean, offline: Boolean): SaveLabel {
    val n = notifyCount(rows)
    return when {
        offline -> SaveLabel.SaveLocal
        editMode -> SaveLabel.SaveChanges
        n > 0 -> SaveLabel.Notify(n)
        else -> SaveLabel.Save
    }
}

/** Lần đầu: mutation cho mọi học sinh. Chế độ sửa: chỉ học sinh đổi. [newId] sinh `clientMutationId`, [now] là `clientUpdatedAt`. */
fun buildMutations(rows: List<AttRow>, editMode: Boolean, newId: () -> String, now: String): List<OutboxMutation> =
    rows.filter { !editMode || it.changed }
        .map { OutboxMutation(newId(), it.studentId, it.status.wire, it.note, now) }

/**
 * Chồng các lô của buổi lên danh sách server để giáo viên thấy điều mình đã lưu. Lô đang chờ: giá trị trong lô coi như
 * đã lưu ([AttRow.original]), nên chỉ phần đổi thêm mới được gửi. Lô lỗi: chỉ hiện giá trị, `original` giữ của server
 * để gửi lại đủ.
 */
fun overlayPending(rows: List<AttRow>, batches: List<OutboxBatch>): List<AttRow> {
    val pending = HashMap<String, OutboxMutation>()
    val failed = HashMap<String, OutboxMutation>()
    for (b in batches) for (m in b.mutations) (if (b.state == OutboxState.Pending) pending else failed)[m.studentId] = m
    return rows.map { r ->
        var row = r
        failed[r.studentId]?.let { m -> AttStatus.fromWire(m.status)?.let { row = row.copy(status = it, note = m.note) } }
        pending[r.studentId]?.let { m -> AttStatus.fromWire(m.status)?.let { row = row.copy(status = it, original = it, note = m.note) } }
        row
    }
}

/** Kết quả áp phản hồi của server vào danh sách. */
data class Applied(val rows: List<AttRow>, val staleCount: Int)

/**
 * Áp kết quả từng mutation, ghép theo `clientMutationId` (không theo thứ tự): `applied`/`duplicate` thì giá trị gửi đi
 * thành giá trị đang lưu; `stale` thì lấy `current` của server (bản mới hơn thắng); `rejected` thì giữ nguyên hàng.
 */
fun applyResults(rows: List<AttRow>, mutations: List<OutboxMutation>, results: List<MutationResultDto>): Applied {
    val byStudent = rows.associateBy { it.studentId }.toMutableMap()
    val byId = results.associateBy { it.clientMutationId }
    var stale = 0
    mutations.forEach { m ->
        val r = byId[m.clientMutationId] ?: return@forEach
        val row = byStudent[m.studentId] ?: return@forEach
        when (r.result) {
            "applied", "duplicate" -> AttStatus.fromWire(m.status)?.let { byStudent[m.studentId] = row.copy(original = it) }
            "stale" -> {
                stale++
                val cur = r.current
                val s = AttStatus.fromWire(cur?.status)
                if (cur != null && s != null) byStudent[m.studentId] = row.copy(status = s, original = s, note = cur.note)
            }
        }
    }
    return Applied(rows.map { byStudent.getValue(it.studentId) }, stale)
}

/**
 * Phản hồi 200 của một lô. Kết quả theo từng phần tử: chỉ "cửa sổ sửa đóng" (`ATTENDANCE_EDIT_WINDOW_CLOSED`) làm cả buổi
 * không sửa được nữa ([Rejected]); phần tử `rejected` mã khác (học sinh không còn trong lớp, gói chỉ đọc…) không làm hỏng
 * lô: lô vẫn xong ([Done]) và các học sinh đó được báo riêng ([rejectedStudentIds]).
 */
sealed interface BatchVerdict {
    data object Done : BatchVerdict
    data class Rejected(val code: String) : BatchVerdict
}

fun verdictOf(results: List<MutationResultDto>): BatchVerdict =
    results.firstOrNull { it.result == "rejected" && it.errorCode == EDIT_WINDOW_CLOSED }?.let { BatchVerdict.Rejected(EDIT_WINDOW_CLOSED) }
        ?: BatchVerdict.Done

/** Học sinh có phần tử bị `rejected` (không ghi được) trong phản hồi. */
fun rejectedStudentIds(results: List<MutationResultDto>): List<String> = results.filter { it.result == "rejected" }.map { it.studentId }

const val EDIT_WINDOW_CLOSED = "ATTENDANCE_EDIT_WINDOW_CLOSED"

/** Quyết định với lô khi gọi API lỗi: giữ lại thử sau, hoặc bỏ (lỗi không thử lại được). */
sealed interface ErrorDecision {
    /** [countAttempt]: lỗi từ server (5xx…) mới tính vào số lần thử; mất mạng thì không. */
    data class Keep(val countAttempt: Boolean) : ErrorDecision
    data class Drop(val code: String) : ErrorDecision
}

const val MAX_ATTEMPTS = 10

fun decideOnError(e: ApiError, attempts: Int): ErrorDecision = when {
    e == ApiError.Network || e == ApiError.Timeout -> ErrorDecision.Keep(countAttempt = false)
    e is ApiError.Http && e.httpStatus == 401 -> ErrorDecision.Keep(countAttempt = false)
    e is ApiError.Http && e.httpStatus == 429 -> ErrorDecision.Keep(countAttempt = false)
    e.retryable || e == ApiError.InvalidResponse ->
        if (attempts + 1 >= MAX_ATTEMPTS) ErrorDecision.Drop("MAX_ATTEMPTS") else ErrorDecision.Keep(countAttempt = true)
    e is ApiError.Http -> ErrorDecision.Drop(e.code.ifEmpty { "HTTP_${e.httpStatus}" })
    else -> ErrorDecision.Keep(countAttempt = true)
}

/**
 * Lỗi vĩnh viễn mà lô không còn ý nghĩa nữa (buổi đã hủy, quá hạn sửa, `NOT_FOUND`): xóa lô. 404 không kèm mã lỗi
 * (`HTTP_404`, vd. proxy trả) thì không xóa: giữ `failed` để giáo viên gửi lại.
 * Các lỗi khác (403, 422…, đủ [MAX_ATTEMPTS] lần lỗi server) giữ lô ở trạng thái `failed` để giáo viên gửi lại.
 */
fun isDeletableError(code: String): Boolean = code in DELETABLE

private val DELETABLE = setOf("SESSION_CANCELLED", EDIT_WINDOW_CLOSED, "NOT_FOUND")

private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

/** `2026-11-09T17:45:00.123+07:00`: có mili giây (mốc LWW của server so sánh `>`), đúng mẫu `date-time` của hợp đồng. */
fun formatIso(at: Instant, zone: ZoneId): String = ISO.format(at.truncatedTo(ChronoUnit.MILLIS).atZone(zone))

/** Hành động chính của sheet Chi tiết buổi (T05a). */
enum class SheetAction { Attend, Edit, None }

fun sheetAction(status: String, pending: Boolean): SheetAction = when {
    status == "cancelled" -> SheetAction.None
    status == "taught" || pending -> SheetAction.Edit
    else -> SheetAction.Attend
}

/** "T2-T4-T6" từ thứ ISO của quy tắc lặp; không có quy tắc thì `null`. */
fun repeatLabel(weekdays: List<Int>): String? =
    weekdays.filter { it in 1..7 }.distinct().sorted().takeIf { it.isNotEmpty() }
        ?.joinToString("-") { if (it == 7) "CN" else "T${it + 1}" }

/** Người sửa lần cuối: tên ở dòng có `syncedAt` mới nhất (US-3.3); `null` khi không có dòng hoặc server không trả tên. */
fun lastEditedBy(items: List<AttendanceItemDto>): String? =
    items.mapNotNull { i -> i.syncedAt?.let(::toInstantOrNull)?.let { it to i.recordedByName } }.maxByOrNull { it.first }?.second

/** Hạn xem lại: "Sửa lần cuối" lấy mốc server nhận gần nhất của các dòng. */
fun lastEditedAt(items: List<AttendanceItemDto>): Instant? =
    items.mapNotNull { (it.syncedAt ?: it.clientUpdatedAt)?.let(::toInstantOrNull) }.maxOrNull()
