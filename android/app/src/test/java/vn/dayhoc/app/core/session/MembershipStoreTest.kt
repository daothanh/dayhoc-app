package vn.dayhoc.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.dayhoc.app.core.network.ApiError

/**
 * US-0.11: đổi vai trò chỉ đổi vai trò đang mở trên máy và báo server, không đổi phiên đăng nhập.
 * `Idempotency-Key` của lệnh thêm vai trò phải giữ qua các lần thử lại sau lỗi mạng để không tạo
 * hai không gian giáo viên.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MembershipStoreTest {
    private val api = FakeSessionApi()
    private val prefs = MemoryDataStore()

    private val savedId get() = prefs.data.value[ACTIVE_ID]

    @Test
    fun `chon vai tro luu id tren may de lan mo sau dung lai`() = runTest {
        val teacher = MembershipDto("m1", Roles.TEACHER, "Giáo viên · Cô Lan")
        val parent = MembershipDto("m2", Roles.PARENT, "Phụ huynh của Nam")
        api.memberships = MembershipsResponse(listOf(teacher, parent), lastMembershipId = "m1")
        val store = MembershipStore(api, prefs, backgroundScope)
        store.load()

        store.select(parent)
        // Lời báo server chạy trong `backgroundScope`: `advanceUntilIdle()` dừng khi chỉ còn việc nền,
        // `runCurrent()` mới chạy nốt việc đã xếp ở mốc thời gian hiện tại.
        advanceUntilIdle()
        runCurrent()

        assertEquals("m2", savedId)
        assertEquals(listOf("m2"), api.activeCalls)
        assertEquals("m2", store.state.value.active?.id)

        // Mở lại app: máy nhớ vai trò đã chọn (ưu tiên hơn lần dùng gần nhất trên server).
        val reopened = MembershipStore(api, prefs, backgroundScope)
        assertEquals("m2", reopened.load().active?.id)
    }

    @Test
    fun `load dung lastMembershipId khi may chua chon`() = runTest {
        api.memberships = MembershipsResponse(
            listOf(MembershipDto("m1", Roles.TEACHER, "Giáo viên"), MembershipDto("m2", Roles.PARENT, "Phụ huynh")),
            lastMembershipId = "m2",
        )
        assertEquals("m2", MembershipStore(api, prefs, backgroundScope).load().active?.id)
    }

    @Test
    fun `load chon vai tro duy nhat khi chi co mot`() = runTest {
        api.memberships = MembershipsResponse(listOf(MembershipDto("m1", Roles.TEACHER, "Giáo viên")))
        assertEquals("m1", MembershipStore(api, prefs, backgroundScope).load().active?.id)
    }

    @Test
    fun `them giao vien tao khong gian, loi mang thi giu nguyen khoa khi thu lai`() = runTest {
        val store = MembershipStore(api, prefs, backgroundScope)
        api.workspaceError = ApiError.Network
        assertTrue(runCatching { store.addRole(Roles.TEACHER) }.exceptionOrNull() is ApiError.Network)

        api.workspaceError = null
        api.memberships = MembershipsResponse(listOf(MembershipDto("m1", Roles.TEACHER, "Giáo viên")))
        val created = store.addRole(Roles.TEACHER)

        assertEquals("m1", created.id)
        assertEquals(2, api.workspaceKeys.size)
        assertEquals(api.workspaceKeys[0], api.workspaceKeys[1])
        assertEquals("m1", store.state.value.active?.id)
    }

    @Test
    fun `them giao vien chua thay vai tro moi thi coi nhu chua xong va giu khoa`() = runTest {
        val store = MembershipStore(api, prefs, backgroundScope)
        api.memberships = MembershipsResponse(emptyList())
        assertTrue(runCatching { store.addRole(Roles.TEACHER) }.exceptionOrNull() is ApiError.InvalidResponse)

        // Bấm lại: server phát lại kết quả của cùng khóa, không tạo không gian thứ hai.
        api.memberships = MembershipsResponse(listOf(MembershipDto("m1", Roles.TEACHER, "Giáo viên")))
        store.addRole(Roles.TEACHER)
        assertEquals(api.workspaceKeys[0], api.workspaceKeys[1])
    }

    @Test
    fun `them phu huynh goi me roles theo vai tro`() = runTest {
        val store = MembershipStore(api, prefs, backgroundScope)
        api.memberships = MembershipsResponse(listOf(MembershipDto("m2", Roles.PARENT, "Phụ huynh của Nam")))

        val created = store.addRole(Roles.PARENT)

        assertEquals("m2", created.id)
        assertEquals(listOf(AddRoleRequest(Roles.PARENT)), api.roleCalls.map { it.second })
        assertTrue(api.workspaceKeys.isEmpty())
        assertEquals("m2", store.state.value.active?.id)
    }

    @Test
    fun `409 khi vai tro da tao lan truoc thi tai lai va mo luon`() = runTest {
        val store = MembershipStore(api, prefs, backgroundScope)
        api.workspaceError = ApiError.Http(ApiError.CONFLICT_STALE, 409)
        api.memberships = MembershipsResponse(listOf(MembershipDto("m1", Roles.TEACHER, "Giáo viên")))

        val created = store.addRole(Roles.TEACHER)

        assertEquals("m1", created.id)
        assertEquals(1, api.workspaceKeys.size)
        assertEquals("m1", store.state.value.active?.id)
    }

    @Test
    fun `server tu choi han thi lan sau la lenh moi`() = runTest {
        val store = MembershipStore(api, prefs, backgroundScope)
        api.workspaceError = ApiError.Http(ApiError.VALIDATION_FAILED, 422)
        assertTrue(runCatching { store.addRole(Roles.TEACHER) }.exceptionOrNull() is ApiError.Http)

        api.workspaceError = null
        api.memberships = MembershipsResponse(listOf(MembershipDto("m1", Roles.TEACHER, "Giáo viên")))
        store.addRole(Roles.TEACHER)

        assertNotEquals(api.workspaceKeys[0], api.workspaceKeys[1])
    }

    @Test
    fun `resetPendingAdd bo khoa dang do`() = runTest {
        val store = MembershipStore(api, prefs, backgroundScope)
        api.workspaceError = ApiError.Network
        runCatching { store.addRole(Roles.TEACHER) }

        store.resetPendingAdd()
        api.workspaceError = null
        api.memberships = MembershipsResponse(listOf(MembershipDto("m1", Roles.TEACHER, "Giáo viên")))
        store.addRole(Roles.TEACHER)

        assertNotEquals(api.workspaceKeys[0], api.workspaceKeys[1])
    }

    @Test
    fun `clear quen vai tro cua phien`() = runTest {
        val store = MembershipStore(api, prefs, backgroundScope)
        api.memberships = MembershipsResponse(listOf(MembershipDto("m1", Roles.TEACHER, "Giáo viên")))
        store.load()
        store.select(MembershipDto("m1", Roles.TEACHER, "Giáo viên"))
        advanceUntilIdle()

        store.clear()

        assertNull(store.state.value.active)
        assertTrue(store.state.value.items.isEmpty())
        assertNull(savedId)
    }

    private companion object {
        val ACTIVE_ID = stringPreferencesKey("active_membership_id")
    }
}

/** API phiên giả: trả danh sách vai trò và ghi lại lời gọi để kiểm tra hợp đồng. */
internal class FakeSessionApi : SessionApi {
    var memberships = MembershipsResponse(emptyList())
    var workspaceError: ApiError? = null
    var roleError: ApiError? = null
    var waitlistError: ApiError? = null

    val activeCalls = mutableListOf<String>()
    val workspaceKeys = mutableListOf<String>()
    val roleCalls = mutableListOf<Pair<String, AddRoleRequest>>()
    val waitlistCalls = mutableListOf<Pair<String, CenterWaitlistRequest>>()

    override suspend fun memberships() = memberships

    override suspend fun setActive(body: ActiveMembershipRequest) {
        activeCalls += body.membershipId
    }

    override suspend fun createWorkspace(key: String, body: JsonObject) {
        workspaceKeys += key
        workspaceError?.let { throw it }
    }

    override suspend fun addRole(key: String, body: AddRoleRequest) {
        roleCalls += key to body
        roleError?.let { throw it }
    }

    override suspend fun appConfig() = AppConfig(AppConfig.MinVersion("1.0.0", "1.0.0"))

    override suspend fun joinCenterWaitlist(key: String, body: CenterWaitlistRequest) {
        waitlistCalls += key to body
        waitlistError?.let { throw it }
    }
}

/** DataStore trong bộ nhớ thay tệp Preferences. */
internal class MemoryDataStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}
