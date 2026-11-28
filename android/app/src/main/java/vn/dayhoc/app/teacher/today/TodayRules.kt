package vn.dayhoc.app.teacher.today

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
)

/** Đang dạy → Chưa điểm danh → Sắp tới → Đã điểm danh → Đã hủy; cùng nhóm thì theo giờ bắt đầu. */
fun sortSessions(sessions: List<TodaySession>): List<TodaySession> =
    sessions.sortedWith(compareBy({ it.kind.ordinal }, { it.start }))

fun toInstant(iso: String): Instant = OffsetDateTime.parse(iso).toInstant()

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
    sessions.filter { it.status == "planned" }.minByOrNull { toInstant(it.startsAt) }
