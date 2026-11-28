package vn.dayhoc.app.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class DhAvatarTest {
    @Test
    fun `chu tat lay chu dau tu dau va tu cuoi`() {
        assertEquals("NA", initialsOf("Nguyễn An"))
        assertEquals("ĐH", initialsOf("Đỗ Hà"))
        assertEquals("NA", initialsOf("Nguyễn Hoàng Bảo Ngọc Anh"))
        assertEquals("L", initialsOf("lan"))
        assertEquals("?", initialsOf("  "))
    }
}
