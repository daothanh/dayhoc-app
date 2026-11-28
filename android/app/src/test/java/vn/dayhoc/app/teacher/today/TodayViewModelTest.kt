package vn.dayhoc.app.teacher.today

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.teacher.onboarding.OnboardingDto
import vn.dayhoc.app.teacher.onboarding.OnboardingStep

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    // 09/11/2026 17:45 giờ VN: buổi 17:30–19:00 đang dạy.
    private val clock = Clock.fixed(Instant.parse("2026-11-09T10:45:00Z"), zone)
    private val api = FakeTodayApi()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = TodayViewModel(api, clock)

    @Test
    fun `tai thanh cong, xep buoi, dem co mat, cong no, chuong, banner`() = runTest {
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        val data = s.data!!
        assertEquals(listOf("Toán 9A", "Lý 8", "Toán 7", "Sử 6"), data.sessions.map { it.className })
        assertEquals(SessionKind.Teaching, data.sessions[0].kind)
        assertEquals(18, data.sessions[0].enrolled)
        assertEquals("Tại nhà", data.sessions[0].location)
        assertEquals(15 to 16, data.sessions[2].attended to data.sessions[2].total)
        assertEquals(SessionKind.Cancelled, data.sessions[3].kind)
        assertEquals(Overdue(1, 500_000), data.overdue)
        assertEquals(3, data.unread)
        assertEquals(listOf(OnboardingStep.SetSchedule), data.setupSteps)
        assertNull(data.next)
        assertEquals("2026-11-09" to "2026-11-09", api.sessionCalls.first())
    }

    @Test
    fun `loi attendance, receivables, notifications, onboarding khong lam hong man`() = runTest {
        api.attendanceError = ApiError.Http("X", 500)
        api.receivablesError = ApiError.Http("X", 500)
        api.notificationsError = ApiError.Network
        api.onboardingError = ApiError.Timeout
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()

        val data = vm.state.value.data!!
        assertFalse(vm.state.value.failed)
        assertNull(data.sessions.first { it.className == "Toán 7" }.attended)
        assertNull(data.overdue)
        assertEquals(0, data.unread)
        assertTrue(data.setupSteps.isEmpty())
    }

    @Test
    fun `khong co buoi hom nay thi tim buoi ke tiep trong 14 ngay`() = runTest {
        api.today = emptyList()
        api.upcoming = listOf(SessionDto("n", "c1", "Toán 9A", "2026-11-16T17:30:00+07:00", "2026-11-16T19:00:00+07:00", "planned"))
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()

        val data = vm.state.value.data!!
        assertTrue(data.sessions.isEmpty())
        assertEquals(NextSession("Toán 9A", java.time.LocalDate.of(2026, 11, 16), "17:30"), data.next)
        assertEquals("2026-11-10" to "2026-11-23", api.sessionCalls.last())
    }

    @Test
    fun `khong co buoi hom nay va khong co buoi ke tiep`() = runTest {
        api.today = emptyList()
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.data!!.next)
    }

    @Test
    fun `loi lan dau thi hien trang thai loi roi Thu lai thanh cong`() = runTest {
        api.sessionsError = ApiError.Network
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.failed)
        assertNull(vm.state.value.data)
        assertFalse(vm.state.value.offline)

        api.sessionsError = null
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.failed)
        assertEquals(4, vm.state.value.data!!.sessions.size)
    }

    @Test
    fun `mat mang khi da co du lieu thi giu du lieu cu va danh dau ngoai tuyen`() = runTest {
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        api.sessionsError = ApiError.Network
        vm.refresh()
        assertTrue(vm.state.value.refreshing)
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.offline)
        assertFalse(s.refreshing)
        assertFalse(s.failed)
        assertEquals(4, s.data!!.sessions.size)

        api.sessionsError = null
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun `loi khong phai mang khi da co du lieu thi giu du lieu va bao mot lan`() = runTest {
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        api.sessionsError = ApiError.Http("X", 500)
        vm.refresh()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.refreshError)
        assertFalse(s.offline)
        assertEquals(4, s.data!!.sessions.size)
        vm.refreshErrorShown()
        assertFalse(vm.state.value.refreshError)
    }

    @Test
    fun `gio sai dinh dang thi bo buoi do, khong lam sap man`() = runTest {
        api.today = api.today + SessionDto("bad", "c1", "Hỏng", "không phải giờ", "không phải giờ", "planned")
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(4, vm.state.value.data!!.sessions.size)
    }

    @Test
    fun `exception la bat ky lan dau thi roi ve failed`() = runTest {
        api.sessionsCrash = IllegalStateException("boom")
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.failed)
    }

    @Test
    fun `the buoi tu chuyen trang thai theo gio`() {
        val start = Instant.parse("2026-11-09T10:30:00Z")
        val s = TodaySession("a", "Toán 9A", start, start.plusSeconds(5400), SessionKind.Upcoming, null, null, null, null)
        assertEquals(SessionKind.Upcoming, arrange(listOf(s), start.minusSeconds(60)).single().kind)
        assertEquals(SessionKind.Teaching, arrange(listOf(s), start.plusSeconds(60)).single().kind)
        assertEquals(SessionKind.Missed, arrange(listOf(s), start.plusSeconds(5400)).single().kind)
    }
}

private class FakeTodayApi : TodayApi {
    var sessionsError: ApiError? = null
    var sessionsCrash: Exception? = null
    var attendanceError: ApiError? = null
    var receivablesError: ApiError? = null
    var notificationsError: ApiError? = null
    var onboardingError: ApiError? = null
    val sessionCalls = mutableListOf<Pair<String, String>>()
    var upcoming = emptyList<SessionDto>()
    var today = listOf(
        SessionDto("s4", "c4", "Sử 6", "2026-11-09T20:00:00+07:00", "2026-11-09T21:00:00+07:00", "cancelled"),
        SessionDto("s3", "c3", "Toán 7", "2026-11-09T15:00:00+07:00", "2026-11-09T16:30:00+07:00", "taught"),
        SessionDto("s2", "c2", "Lý 8", "2026-11-09T19:15:00+07:00", "2026-11-09T20:45:00+07:00", "planned"),
        SessionDto("s1", "c1", "Toán 9A", "2026-11-09T17:30:00+07:00", "2026-11-09T19:00:00+07:00", "planned"),
    )

    override suspend fun onboarding(): OnboardingDto {
        onboardingError?.let { throw it }
        return OnboardingDto(
            listOf(
                OnboardingDto.StepDto("add_students", "done"),
                OnboardingDto.StepDto("set_schedule", "skipped"),
            ),
        )
    }

    override suspend fun sessions(from: String, to: String): SessionList {
        sessionCalls += from to to
        sessionsError?.let { throw it }
        sessionsCrash?.let { throw it }
        return SessionList(if (from == to) today else upcoming)
    }

    override suspend fun classes(status: String, pageSize: Int) = ClassInfoPage(
        listOf(
            ClassInfoDto("c1", "Toán 9A", "offline", "Tại nhà", 18),
            ClassInfoDto("c2", "Lý 8", "offline", null, 12),
            ClassInfoDto("c3", "Toán 7", "online", null, 16),
        ),
    )

    override suspend fun receivables(pageSize: Int): ReceivablePage {
        receivablesError?.let { throw it }
        return ReceivablePage(listOf(ReceivableRow(500_000, 3), ReceivableRow(900_000, 0)))
    }

    override suspend fun notifications(limit: Int): NotificationInfo {
        notificationsError?.let { throw it }
        return NotificationInfo(3)
    }

    override suspend fun attendance(sessionId: String): AttendanceInfo {
        attendanceError?.let { throw it }
        return AttendanceInfo(List(16) { AttendanceRow("st$it") }, AttendanceSummary(present = 14, late = 1))
    }
}
