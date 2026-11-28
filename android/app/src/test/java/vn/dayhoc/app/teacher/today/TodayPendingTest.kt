package vn.dayhoc.app.teacher.today

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayPendingTest {
    private val now = Instant.parse("2026-11-09T10:45:00Z")

    private fun s(id: String, status: String, startH: Long) = TodaySession(
        id, "Lớp $id", now.plusSeconds(startH * 3600), now.plusSeconds(startH * 3600 + 5400), SessionKind.Upcoming, null, null, null, null, status,
    )

    @Test
    fun `arrange dua buoi cho dong bo xuong nhom da diem danh va danh dau pending`() {
        val out = arrange(listOf(s("a", "planned", -3), s("b", "planned", 2), s("c", "taught", -5)), now, setOf("a"))
        assertEquals(listOf("b", "c", "a"), out.map { it.id })
        assertEquals(listOf(SessionKind.Upcoming, SessionKind.Taught, SessionKind.Taught), out.map { it.kind })
        assertEquals(listOf(false, false, true), out.map { it.pending })
    }

    @Test
    fun `khong co lo cho thi arrange nhu cu`() {
        val out = arrange(listOf(s("a", "planned", -3)), now)
        assertEquals(SessionKind.Missed, out.single().kind)
        assertEquals(false, out.single().pending)
    }
}
