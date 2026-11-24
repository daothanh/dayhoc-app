package vn.dayhoc.app.teacher.onboarding

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.dayhoc.app.core.session.isVersionBelow

class OnboardingRulesTest {
    @Test
    fun `chuan hoa so dien thoai`() {
        assertEquals("0912345678", normalizePhone("0912 345 678"))
        assertEquals("0912345678", normalizePhone("+84 912 345 678"))
        assertEquals("0912345678", normalizePhone("0084912345678"))
        assertEquals("0912345678", normalizePhone("84912345678"))
        assertEquals("+6591234567", normalizePhone("+65 9123 4567"))
        assertNull(normalizePhone("không có số"))
        assertTrue(isValidPhone("0912345678"))
        assertFalse(isValidPhone("0912"))
    }

    @Test
    fun `doc so tien`() {
        assertEquals(150_000L, parseVnd("150.000 đ"))
        assertNull(parseVnd(""))
        assertNull(parseVnd("1234567890123"))
    }

    @Test
    fun `hang sau chong gio voi hang truoc cung thu thi bao loi o hang sau`() {
        val rows = listOf(
            ScheduleRow(0, 1, "17:30", 90),
            ScheduleRow(1, 3, "17:30", 90),
            ScheduleRow(2, 1, "18:30", 60),
        )
        assertEquals(2 to rows[0], findOverlap(rows))
        assertFalse(rowsValid(rows))
        // Nối tiếp nhau (17:30–19:00 rồi 19:00) không tính là chồng.
        assertTrue(rowsValid(listOf(ScheduleRow(0, 1, "17:30", 90), ScheduleRow(1, 1, "19:00", 60))))
    }

    @Test
    fun `hang chua chon thu hoac qua nua dem thi chua hop le`() {
        assertFalse(rowsValid(listOf(ScheduleRow(0, null))))
        assertFalse(rowsValid(listOf(ScheduleRow(0, 2, "23:30", 60))))
        assertFalse(rowsValid(emptyList()))
    }

    @Test
    fun `buoi dau tien la ngay gan nhat co lich, gio som nhat`() {
        // 26/11/2026 là thứ Năm.
        val today = LocalDate.of(2026, 11, 26)
        val rules = listOf(RuleDto(1, "17:30", 90), RuleDto(6, "19:00", 60), RuleDto(6, "08:00", 90))
        assertEquals(LocalDate.of(2026, 11, 28) to rules[2], firstSession(rules, today, today))
        // Lớp bắt đầu sau hôm nay: tính từ ngày bắt đầu.
        assertEquals(LocalDate.of(2026, 12, 7) to rules[0], firstSession(rules, LocalDate.of(2026, 12, 7), today))
    }

    @Test
    fun `so phien ban toi thieu`() {
        assertTrue(isVersionBelow("0.1.0", "0.2.0"))
        assertTrue(isVersionBelow("1.9.9", "1.10.0"))
        assertFalse(isVersionBelow("1.2.0", "1.2.0"))
        assertFalse(isVersionBelow("1.2.0-beta+12", "1.1.9"))
    }

    @Test
    fun `doi chieu gio phut hai chieu`() {
        assertEquals(1050, minutesOf("17:30"))
        assertEquals("17:30", hhmm(1050))
        assertEquals("00:00", hhmm(0))
        // 23:30 = 1410 phút, không qua nửa đêm.
        assertEquals(1410, minutesOf("23:30"))
    }

    @Test
    fun `buoi dau tien la null khi khong co quy tac lich`() {
        val today = LocalDate.of(2026, 11, 23)
        assertNull(firstSession(emptyList(), today, today))
    }

    @Test
    fun `doc so tien o bien 12 chu so va loai 13 chu so`() {
        assertEquals(999_999_999_999L, parseVnd("999.999.999.999"))
        assertNull(parseVnd("1000000000000"))
    }

    @Test
    fun `chuan hoa so dien thoai nhan null va giu so khong phai dau so viet nam`() {
        assertNull(normalizePhone(null))
        // 9 chữ số, không dấu + và không mở đầu 00: giữ nguyên, không ghép thành số Việt Nam.
        assertEquals("849123456", normalizePhone("849123456"))
    }

    @Test
    fun `so dien thoai hop le o bien do dai 8 va 15 chu so`() {
        assertTrue(isValidPhone("12345678"))
        assertTrue(isValidPhone("123456789012345"))
        assertFalse(isValidPhone("1234567"))
        assertFalse(isValidPhone("1234567890123456"))
    }
}
