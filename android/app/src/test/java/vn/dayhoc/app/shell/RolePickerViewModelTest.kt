package vn.dayhoc.app.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import vn.dayhoc.app.core.session.FakeSessionApi
import vn.dayhoc.app.core.session.MembershipDto
import vn.dayhoc.app.core.session.MembershipStore
import vn.dayhoc.app.core.session.MembershipsResponse
import vn.dayhoc.app.core.session.MemoryDataStore
import vn.dayhoc.app.core.session.Roles

/**
 * US-0.10 AC2, [T01c]: vai trò đã có thì mở luôn, chưa có thì tạo (Giáo viên tạo không gian;
 * Phụ huynh, Học sinh thêm vai trò) rồi mở.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RolePickerViewModelTest {
    private val api = FakeSessionApi()
    private val prefs = MemoryDataStore()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `vai tro da co thi mo luon, khong tao moi`() = runTest {
        val teacher = MembershipDto("m1", Roles.TEACHER, "Giáo viên · Cô Lan")
        api.memberships = MembershipsResponse(listOf(teacher))
        val store = MembershipStore(api, prefs, backgroundScope)
        store.load()
        val vm = RolePickerViewModel(store)

        assertEquals(setOf(Roles.TEACHER), vm.state.value.owned)
        vm.select(Roles.TEACHER)
        vm.proceed()
        // Lời báo server chạy trong `backgroundScope` của store nên phải `runCurrent()` mới chạy nốt.
        advanceUntilIdle()
        runCurrent()

        assertEquals("m1", vm.state.value.opened?.id)
        assertFalse(vm.state.value.busy)
        assertTrue(api.workspaceKeys.isEmpty())
        assertEquals(listOf("m1"), api.activeCalls)
    }

    @Test
    fun `vai tro chua co thi tao roi mo`() = runTest {
        api.memberships = MembershipsResponse(emptyList())
        val store = MembershipStore(api, prefs, backgroundScope)
        store.load()
        val vm = RolePickerViewModel(store)

        vm.select(Roles.PARENT)
        // Sau khi POST /me/roles, server trả vai trò mới trong danh sách.
        api.memberships = MembershipsResponse(listOf(MembershipDto("m2", Roles.PARENT, "Phụ huynh của Nam")))
        vm.proceed()
        advanceUntilIdle()

        assertEquals("m2", vm.state.value.opened?.id)
        assertEquals(listOf(Roles.PARENT), api.roleCalls.map { it.second.role })
    }

    @Test
    fun `them vai tro loi thi bao that bai va khong mo`() = runTest {
        api.memberships = MembershipsResponse(emptyList())
        api.roleError = ApiError.Http(ApiError.VALIDATION_FAILED, 422)
        val store = MembershipStore(api, prefs, backgroundScope)
        store.load()
        val vm = RolePickerViewModel(store)

        vm.select(Roles.STUDENT)
        vm.proceed()
        advanceUntilIdle()

        assertTrue(vm.state.value.failed)
        assertNull(vm.state.value.opened)
        assertFalse(vm.state.value.busy)

        vm.failureShown()
        assertFalse(vm.state.value.failed)
    }

    @Test
    fun `dang xu ly thi bam tiep khong gui them`() = runTest {
        api.memberships = MembershipsResponse(emptyList())
        val store = MembershipStore(api, prefs, backgroundScope)
        store.load()
        val vm = RolePickerViewModel(store)

        vm.select(Roles.PARENT)
        api.memberships = MembershipsResponse(listOf(MembershipDto("m2", Roles.PARENT, "Phụ huynh")))
        vm.proceed()
        assertTrue(vm.state.value.busy)

        vm.proceed()
        advanceUntilIdle()

        assertEquals(1, api.roleCalls.size)
    }
}
