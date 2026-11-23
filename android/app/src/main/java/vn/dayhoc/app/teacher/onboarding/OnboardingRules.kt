package vn.dayhoc.app.teacher.onboarding

import java.time.LocalDate

/** SĐT phụ huynh hợp lệ theo hợp đồng `Guardian.phone`. */
private val PHONE = Regex("^\\+?[0-9]{8,15}$")

fun isValidPhone(phone: String) = PHONE.matches(phone)

/**
 * Chuẩn hóa SĐT gõ tay hoặc lấy từ danh bạ: bỏ khoảng trắng, dấu chấm, gạch;
 * `+84 912…`, `0084 912…`, `84912…` (11 số) thành `0912…`. Không có chữ số thì `null`.
 */
fun normalizePhone(raw: String?): String? {
    if (raw == null) return null
    var digits = raw.filter(Char::isDigit)
    if (digits.isEmpty()) return null
    var intl = raw.trimStart().startsWith('+')
    if (!intl && digits.startsWith("00")) {
        intl = true
        digits = digits.drop(2)
    }
    if (digits.startsWith("84") && (intl || digits.length == 11)) {
        return "0" + digits.drop(2).removePrefix("0")
    }
    return if (intl) "+$digits" else digits
}

/** Số tiền VND từ ô nhập (chỉ lấy chữ số); rỗng hoặc quá 12 chữ số thì `null`. */
fun parseVnd(text: String): Long? = text.filter(Char::isDigit).takeIf { it.length in 1..12 }?.toLong()

fun minutesOf(hhmm: String): Int = hhmm.split(':').let { it[0].toInt() * 60 + it[1].toInt() }

fun hhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** Một hàng lịch đang soạn; [weekday] `null` = chưa chọn thứ (hàng mới thêm). [key] giữ hàng khi xóa hàng giữa. */
data class ScheduleRow(val key: Int, val weekday: Int?, val start: String = "17:30", val duration: Int = 90)

/** Hàng chồng giờ với một hàng trước nó cùng thứ: (chỉ số hàng sau, hàng trước). */
fun findOverlap(rows: List<ScheduleRow>): Pair<Int, ScheduleRow>? {
    for (j in 1 until rows.size) {
        for (i in 0 until j) {
            val a = rows[i]
            val b = rows[j]
            if (a.weekday == null || a.weekday != b.weekday) continue
            val aS = minutesOf(a.start)
            val bS = minutesOf(b.start)
            if (aS < bS + b.duration && bS < aS + a.duration) return j to a
        }
    }
    return null
}

/** Mọi hàng có thứ, không chồng giờ, không qua nửa đêm. */
fun rowsValid(rows: List<ScheduleRow>) =
    rows.isNotEmpty() &&
        rows.all { it.weekday != null && minutesOf(it.start) + it.duration <= 24 * 60 } &&
        findOverlap(rows) == null

/** Buổi đầu tiên theo lịch, tính từ ngày bắt đầu lớp (hoặc hôm nay nếu lớp đã bắt đầu): ngày và quy tắc sớm nhất. */
fun firstSession(rules: List<RuleDto>, startDate: LocalDate, today: LocalDate): Pair<LocalDate, RuleDto>? {
    if (rules.isEmpty()) return null
    val from = maxOf(startDate, today)
    for (k in 0L until 7L) {
        val d = from.plusDays(k)
        val hit = rules.filter { it.weekday == d.dayOfWeek.value }.minByOrNull { minutesOf(it.startTime) }
        if (hit != null) return d to hit
    }
    return null
}
