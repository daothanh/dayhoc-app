package vn.dayhoc.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.apiCall

/**
 * Vai trò của người dùng và vai trò đang mở (Technical Spec 3.7, US-0.10, US-0.11).
 * Đổi vai trò không đổi JWT: chỉ đổi [state], lưu id trên máy và báo `PUT /me/active-membership`.
 */
class MembershipStore(
    private val api: SessionApi,
    private val prefs: DataStore<Preferences>,
    /** Chạy lời báo vai trò lên server, không chặn người dùng. */
    private val appScope: CoroutineScope,
) {
    data class State(val items: List<MembershipDto> = emptyList(), val active: MembershipDto? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * `Idempotency-Key` của lần thêm Giáo viên đang dở: giữ qua các lần thử lại sau lỗi mạng
     * để `POST /workspaces` không tạo hai tenant.
     */
    private var workspaceKey: String? = null

    /** Vai trò mở lại: lần chọn trên máy này, rồi lần dùng gần nhất trên server, rồi vai trò duy nhất. */
    suspend fun load(): State {
        val res = apiCall { api.memberships() }
        val saved = orNull { prefs.data.first()[ACTIVE_ID] }
        fun byId(id: String?) = res.items.firstOrNull { it.id == id }
        val active = byId(saved) ?: byId(res.lastMembershipId) ?: res.items.singleOrNull()
        return State(res.items, active).also { _state.value = it }
    }

    suspend fun select(membership: MembershipDto) {
        _state.update { it.copy(active = membership) }
        orNull { prefs.edit { it[ACTIVE_ID] = membership.id } }
        // Chỉ để máy khác mở đúng vai trò; lỗi bỏ qua.
        appScope.launch { orNull { apiCall { api.setActive(ActiveMembershipRequest(membership.id)) } } }
    }

    /** Thêm vai trò rồi mở nó. Giáo viên = `POST /workspaces`; Phụ huynh, Học sinh = `POST /me/roles`. */
    suspend fun addRole(role: String): MembershipDto {
        var posted = false
        try {
            if (role == Roles.TEACHER) {
                val key = workspaceKey ?: UUID.randomUUID().toString().also { workspaceKey = it }
                apiCall { api.createWorkspace(key) }
            } else {
                apiCall { api.addRole(UUID.randomUUID().toString(), AddRoleRequest(role)) }
            }
            posted = true
            // Chưa thấy vai trò mới thì coi như chưa xong và giữ khóa: bấm lại được server phát lại kết quả cũ.
            val created = load().items.firstOrNull { it.role == role } ?: throw ApiError.InvalidResponse
            select(created)
            workspaceKey = null
            return created
        } catch (e: ApiError) {
            // Server từ chối hẳn (409, 422…): lần sau là lệnh mới.
            if (!posted && !e.retryable) workspaceKey = null
            throw e
        }
    }

    /** Người dùng đổi ý (chọn vai trò khác): lần thêm Giáo viên sau là lệnh mới. */
    fun resetPendingAdd() {
        workspaceKey = null
    }

    /** Đăng xuất hoặc hết phiên: quên vai trò của phiên này. */
    suspend fun clear() {
        workspaceKey = null
        _state.value = State()
        orNull { prefs.edit { it.remove(ACTIVE_ID) } }
    }

    private inline fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        val ACTIVE_ID = stringPreferencesKey("active_membership_id")
    }
}
