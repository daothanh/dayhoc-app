package vn.dayhoc.app.teacher.today

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import vn.dayhoc.app.teacher.onboarding.OnboardingDto
import vn.dayhoc.app.teacher.onboarding.OnboardingStep

/** Trạng thái hiển thị của thẻ buổi, theo thứ tự xếp (T03, Figma A01). */
enum class SessionKind { Teaching, Missed, Upcoming, Taught, Cancelled }

fun classify(status: String, start: Instant, end: Instant, now: Instant): SessionKind = when (status) {
    "taught" -> SessionKind.Taught
    "cancelled" -> SessionKind.Cancelled
    else -> when {
        now < start -> SessionKind.Upcoming
        now < end -> SessionKind.Teaching
        else -> SessionKind.Missed
    }
}

data class TodaySession(
    val id: String,
    val className: String,
    val start: Instant,
    val end: Instant,
    val kind: SessionKind,
    val enrolled: Int?,
    val location: String?,
    /** Chỉ có khi buổi đã điểm danh và lấy được số liệu; `null` thì chip không kèm số. */
    val attended: Int?,
    val total: Int?,
    /** Trạng thái server (`planned`, `taught`, `cancelled`) để tính lại [kind] khi giờ trôi qua. */
    val status: String = "planned",
    val classId: String = "",
    /** Có lô điểm danh chờ đồng bộ trong outbox: thẻ hiện như đã điểm danh nhưng phụ huynh chưa được báo. */
    val pending: Boolean = false,
    /** Lô của buổi bị server từ chối vĩnh viễn (nằm trong outbox, chờ giáo viên gửi lại): chip "Đồng bộ lỗi". */
    val syncFailed: Boolean = false,
)

/** Tính lại [TodaySession.kind] theo `now` rồi xếp lại: thẻ tự chuyển Sắp tới → Đang dạy → Chưa điểm danh theo giờ. */
fun arrange(
    sessions: List<TodaySession>,
    now: Instant,
    pendingIds: Set<String> = emptySet(),
    failedIds: Set<String> = emptySet(),
): List<TodaySession> = sortSessions(
    mergePending(sessions.map { it.copy(kind = classify(it.status, it.start, it.end, now)) }, pendingIds, failedIds),
)

/** Buổi có lô trong outbox (chờ hoặc lỗi; trừ buổi đã hủy trên server) thành thẻ Taught + [TodaySession.pending]. */
fun mergePending(sessions: List<TodaySession>, pendingIds: Set<String>, failedIds: Set<String> = emptySet()): List<TodaySession> =
    sessions.map {
        if (it.id in pendingIds && it.kind != SessionKind.Cancelled) {
            it.copy(kind = SessionKind.Taught, pending = true, syncFailed = it.id in failedIds)
        } else {
            it.copy(pending = false, syncFailed = false)
        }
    }

/** Đang dạy → Chưa điểm danh → Sắp tới → Đã điểm danh → Đã hủy; cùng nhóm thì theo giờ bắt đầu. */
fun sortSessions(sessions: List<TodaySession>): List<TodaySession> =
    sessions.sortedWith(compareBy({ it.kind.ordinal }, { it.start }))

fun toInstant(iso: String): Instant = OffsetDateTime.parse(iso).toInstant()

/** Chuỗi giờ sai định dạng thì `null`: buổi đó bị bỏ qua thay vì làm sập màn. */
fun toInstantOrNull(iso: String): Instant? = try {
    toInstant(iso)
} catch (e: DateTimeParseException) {
    null
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

fun formatTimeRange(start: Instant, end: Instant, zone: ZoneId): String =
    "${HHMM.format(start.atZone(zone))}–${HHMM.format(end.atZone(zone))}"

fun formatTime(at: Instant, zone: ZoneId): String = HHMM.format(at.atZone(zone))

private val DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val DM = DateTimeFormatter.ofPattern("dd/MM")

private fun weekdayLong(d: DayOfWeek) = when (d) {
    DayOfWeek.MONDAY -> "Thứ Hai"
    DayOfWeek.TUESDAY -> "Thứ Ba"
    DayOfWeek.WEDNESDAY -> "Thứ Tư"
    DayOfWeek.THURSDAY -> "Thứ Năm"
    DayOfWeek.FRIDAY -> "Thứ Sáu"
    DayOfWeek.SATURDAY -> "Thứ Bảy"
    DayOfWeek.SUNDAY -> "Chủ Nhật"
}

private fun weekdayShort(d: DayOfWeek) = if (d == DayOfWeek.SUNDAY) "CN" else "T${d.value + 1}"

/** "Thứ Hai, 09/11/2026". */
fun formatDateLine(date: LocalDate) = "${weekdayLong(date.dayOfWeek)}, ${DMY.format(date)}"

/** "Thứ Hai 09/11" cho dòng ngày của sheet Chi tiết buổi. */
fun formatSheetDay(date: LocalDate) = "${weekdayLong(date.dayOfWeek)} ${DM.format(date)}"

/** "T2 16/11" cho dòng "Buổi tiếp theo". */
fun formatShortDay(date: LocalDate) = "${weekdayShort(date.dayOfWeek)} ${DM.format(date)}"

/** "6.800.000 đ". */
fun formatVnd(amount: Long): String = amount.toString().reversed().chunked(3).joinToString(".").reversed() + " đ"

/** Bước onboarding đã bỏ qua mà banner nhắc lại (US-0.2 AC2): chỉ thêm học sinh, đặt lịch học. */
fun skippedSetupSteps(onboarding: OnboardingDto): List<OnboardingStep> =
    listOf(OnboardingStep.AddStudents, OnboardingStep.SetSchedule).filter { step ->
        onboarding.steps.any { it.step == step.wire && it.status == "skipped" }
    }

data class Overdue(val count: Int, val total: Long)

/** Học viên có hóa đơn quá hạn: số dòng và tổng nợ của các dòng đó. */
fun overdueSummary(rows: List<ReceivableRow>): Overdue =
    rows.filter { it.overdueDays > 0 }.let { Overdue(it.size, it.sumOf { r -> r.outstandingAmount }) }

/** Buổi `planned` sớm nhất trong danh sách (cho dòng "Buổi tiếp theo" của Empty). */
fun earliestPlanned(sessions: List<SessionDto>): SessionDto? =
    sessions.filter { it.status == "planned" && toInstantOrNull(it.startsAt) != null }.minByOrNull { toInstant(it.startsAt) }

/** Hai biến thể trạng thái "Hôm nay không có buổi": A24 còn buổi kế tiếp, A35 chưa có lịch nào. */
enum class EmptyVariant { NextSession, NoSchedule }

fun emptyVariant(next: NextSession?): EmptyVariant = if (next != null) EmptyVariant.NextSession else EmptyVariant.NoSchedule

/** Màn A35 đã có nút "Tạo lịch" cùng đích với bước `set_schedule`, nên banner bỏ bước đó (hết bước thì ẩn). */
fun bannerSteps(steps: List<OnboardingStep>, sessionsEmpty: Boolean, next: NextSession?): List<OnboardingStep> =
    if (sessionsEmpty && emptyVariant(next) == EmptyVariant.NoSchedule) steps.filter { it != OnboardingStep.SetSchedule } else steps
