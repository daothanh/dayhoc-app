package vn.dayhoc.app.shell

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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.session.CenterWaitlistRequest
import vn.dayhoc.app.core.session.FakeSessionApi

/**
 * [T01d] Chủ trung tâm đăng ký sớm: kiểm tra ô nhập, gửi `POST /center-waitlist` với
 * `Idempotency-Key` bám theo nội dung (gửi lại không ghi hai lần, sửa ô thì là lệnh mới).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CenterWaitlistViewModelTest {
    private val api = FakeSessionApi()
    private lateinit var vm: CenterWaitlistViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        vm = CenterWaitlistViewModel(api)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `ten va so giao vien hop le thi gui di dang ky`() = runTest {
        vm.onName(" Trung tâm ABC ")
        vm.onCount("12")
        vm.submit()
        advanceUntilIdle()

        assertTrue(vm.state.value.sent)
        assertFalse(vm.state.value.busy)
        assertEquals(
            listOf(CenterWaitlistRequest("Trung tâm ABC", 12)),
            api.waitlistCalls.map { it.second },
        )
        assertTrue(api.waitlistCalls.single().first.isNotBlank())
    }

    @Test
    fun `ten rong hoac so khong hop le thi hien loi va khong goi api`() = runTest {
        vm.submit()
        advanceUntilIdle()

        assertTrue(vm.state.value.showErrors)
        assertFalse(vm.state.value.nameValid)
        assertTrue(api.waitlistCalls.isEmpty())

        vm.onName("Trung tâm ABC")
        vm.onCount("0")
        vm.submit()
        advanceUntilIdle()

        assertEquals(null, vm.state.value.count)
        assertTrue(api.waitlistCalls.isEmpty())
    }

    @Test
    fun `o so giao vien chi nhan chu so toi da bon`() {
        vm.onCount("1a2b3456")
        assertEquals("1234", vm.state.value.teacherCount)
    }

    @Test
    fun `gui lai sau loi mang giu khoa, sua noi dung thi khoa moi`() = runTest {
        api.waitlistError = ApiError.Network
        vm.onName("Trung tâm ABC")
        vm.onCount("12")

        vm.submit()
        advanceUntilIdle()
        vm.submit()
        advanceUntilIdle()

        assertEquals(2, api.waitlistCalls.size)
        assertEquals(api.waitlistCalls[0].first, api.waitlistCalls[1].first)

        vm.onName("Trung tâm XYZ")
        vm.submit()
        advanceUntilIdle()

        assertNotEquals(api.waitlistCalls[1].first, api.waitlistCalls[2].first)
    }

    @Test
    fun `loi khong the thu lai thi lan sau la lenh moi`() = runTest {
        api.waitlistError = ApiError.Http(ApiError.VALIDATION_FAILED, 422)
        vm.onName("Trung tâm ABC")
        vm.onCount("12")

        vm.submit()
        advanceUntilIdle()
        api.waitlistError = null
        vm.submit()
        advanceUntilIdle()

        assertEquals(2, api.waitlistCalls.size)
        assertNotEquals(api.waitlistCalls[0].first, api.waitlistCalls[1].first)
    }
}
