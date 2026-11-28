package vn.dayhoc.app.teacher.today

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.dayhoc.app.teacher.onboarding.OnboardingDto
import vn.dayhoc.app.teacher.onboarding.OnboardingStep

class TodayRulesTest {
    private val start = Instant.parse("2026-11-09T10:30:00Z")
    private val end = Instant.parse("2026-11-09T12:00:00Z")

    @Test
    fun `phan loai buoi theo trang thai va gio hien tai`() {
        assertEquals(SessionKind.Upcoming, classify("planned", start, end, start.minusSeconds(1)))
        assertEquals(SessionKind.Teaching, classify("planned", start, end, start))
        assertEquals(SessionKind.Teaching, classify("planned", start, end, end.minusSeconds(1)))
        assertEquals(SessionKind.Missed, classify("planned", start, end, end))
        assertEquals(SessionKind.Taught, classify("taught", start, end, end.plusSeconds(60)))
        assertEquals(SessionKind.Cancelled, classify("cancelled", start, end, start))
    }

    private fun session(id: String, kind: SessionKind, at: String) = TodaySession(
        id, id, Instant.parse(at), Instant.parse(at).plusSeconds(5400), kind, null, null, null, null,
    )

    @Test
    fun `sap xep dang day, chua diem danh, sap toi, da diem danh, da huy, cung nhom theo gio`() {
        val sorted = sortSessions(
            listOf(
                session("huy", SessionKind.Cancelled, "2026-11-09T01:00:00Z"),
                session("xong", SessionKind.Taught, "2026-11-09T02:00:00Z"),
                session("toi2", SessionKind.Upcoming, "2026-11-09T14:00:00Z"),
                session("toi1", SessionKind.Upcoming, "2026-11-09T12:30:00Z"),
                session("lo", SessionKind.Missed, "2026-11-09T03:00:00Z"),
                session("day", SessionKind.Teaching, "2026-11-09T10:30:00Z"),
            ),
        )
        assertEquals(listOf("day", "lo", "toi1", "toi2", "xong", "huy"), sorted.map { it.id })
    }

    private fun onboarding(vararg steps: Pair<String, String>) =
        OnboardingDto(steps.map { OnboardingDto.StepDto(it.first, it.second) })

    @Test
    fun `banner chi tinh them hoc sinh va dat lich da bo qua, theo thu tu buoc`() {
        val o = onboarding(
            "create_class" to "done",
            "add_students" to "skipped",
            "set_schedule" to "skipped",
            "payout_account" to "skipped",
            "compliance_checklist" to "pending",
        )
        assertEquals(listOf(OnboardingStep.AddStudents, OnboardingStep.SetSchedule), skippedSetupSteps(o))
        assertEquals(
            listOf(OnboardingStep.SetSchedule),
            skippedSetupSteps(onboarding("add_students" to "done", "set_schedule" to "skipped")),
        )
        assertEquals(emptyList<OnboardingStep>(), skippedSetupSteps(onboarding("add_students" to "pending", "payout_account" to "skipped")))
    }

    @Test
    fun `gop cong no qua han`() {
        val rows = listOf(
            ReceivableRow(3_000_000, 5),
            ReceivableRow(1_000_000, 0),
            ReceivableRow(3_800_000, 40),
        )
        assertEquals(Overdue(2, 6_800_000), overdueSummary(rows))
        assertEquals(Overdue(0, 0), overdueSummary(listOf(ReceivableRow(500_000, 0))))
    }

    @Test
    fun `dinh dang ngay, gio, tien`() {
        assertEquals("Thứ Hai, 09/11/2026", formatDateLine(LocalDate.of(2026, 11, 9)))
        assertEquals("Chủ Nhật, 15/11/2026", formatDateLine(LocalDate.of(2026, 11, 15)))
        assertEquals("T2 16/11", formatShortDay(LocalDate.of(2026, 11, 16)))
        assertEquals("CN 15/11", formatShortDay(LocalDate.of(2026, 11, 15)))
        assertEquals("17:30–19:00", formatTimeRange(start, end, ZoneId.of("Asia/Ho_Chi_Minh")))
        assertEquals("6.800.000 đ", formatVnd(6_800_000))
        assertEquals("950 đ", formatVnd(950))
        assertEquals("1.000 đ", formatVnd(1000))
    }

    @Test
    fun `buoi ke tiep la buoi planned som nhat`() {
        fun dto(id: String, at: String, status: String) = SessionDto(id, "c", "Toán 9A", at, at, status)
        val list = listOf(
            dto("a", "2026-11-17T10:30:00+07:00", "planned"),
            dto("b", "2026-11-16T10:30:00+07:00", "cancelled"),
            dto("c", "2026-11-16T17:30:00+07:00", "planned"),
        )
        assertEquals("c", earliestPlanned(list)?.id)
        assertNull(earliestPlanned(listOf(dto("x", "2026-11-16T10:30:00+07:00", "cancelled"))))
    }
}
