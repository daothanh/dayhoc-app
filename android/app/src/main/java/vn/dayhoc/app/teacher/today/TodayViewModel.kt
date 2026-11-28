package vn.dayhoc.app.teacher.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.apiCall
import vn.dayhoc.app.teacher.onboarding.OnboardingStep

/** Buổi gần nhất sau hôm nay, dùng cho mô tả Empty. */
data class NextSession(val className: String, val day: LocalDate, val time: String)

data class TodayData(
    val date: LocalDate,
    val sessions: List<TodaySession>,
    val next: NextSession?,
    val setupSteps: List<OnboardingStep>,
    /** `null` khi không lấy được công nợ: ẩn cả section "Cần xử lý". */
    val overdue: Overdue?,
    val unread: Int,
)

data class TodayUiState(
    val data: TodayData? = null,
    /** Lần tải đầu, chưa có dữ liệu: hiện skeleton. */
    val loading: Boolean = true,
    /** Tải lại khi đã có dữ liệu (kéo xuống, quay lại màn). */
    val refreshing: Boolean = false,
    /** Làm mới lỗi không do mạng khi đã có dữ liệu (5xx, sai định dạng): màn báo một lần rồi gọi [TodayViewModel.refreshErrorShown]. */
    val refreshError: Boolean = false,
    /** Lần tải đầu lỗi: hiện trạng thái lỗi + Thử lại. */
    val failed: Boolean = false,
    /** Tải lại lỗi do mạng khi đang có dữ liệu cũ: giữ dữ liệu, hiện banner ngoại tuyến. */
    val offline: Boolean = false,
) {
    val syncing get() = loading || refreshing
}

/**
 * [T03] Hôm nay của giáo viên. Sessions và classes là bắt buộc; onboarding, receivables, notifications
 * và attendance lỗi thì phần đó ẩn hoặc rơi về chip không số. Chưa có cache bền (NA-07).
 */
class TodayViewModel(
    private val api: TodayApi,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Lần đầu và mọi lần quay lại màn: tải mới; đang tải thì bỏ qua. */
    fun refresh() {
        if (job?.isActive == true) return
        val first = _state.value.data == null
        _state.update { it.copy(loading = first, refreshing = !first, failed = false) }
        job = viewModelScope.launch {
            try {
                val data = fetch()
                _state.value = TodayUiState(data = data, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Mọi lỗi khác (kể cả sai định dạng) cũng rơi về failed/offline, không làm sập app.
                val network = e == ApiError.Network || e == ApiError.Timeout
                _state.update { s ->
                    when {
                        s.data == null -> TodayUiState(loading = false, failed = true)
                        network -> s.copy(refreshing = false, offline = true)
                        else -> s.copy(refreshing = false, refreshError = true)
                    }
                }
            }
        }
    }

    fun refreshErrorShown() = _state.update { it.copy(refreshError = false) }

    private suspend fun fetch(): TodayData = coroutineScope {
        // shortcut: dùng múi giờ máy thay cho múi giờ tenant, đổi khi membership có timezone
        val today = LocalDate.now(clock)
        val day = today.toString()
        val sessions = async { apiCall { api.sessions(day, day) }.items }
        val classes = async { apiCall { api.classes() }.items.associateBy { it.id } }
        val onboarding = async { soft { api.onboarding() } }
        val receivables = async { soft { api.receivables() } }
        val notifications = async { soft { api.notifications() } }

        val now = clock.instant()
        val zone = clock.zone
        val dtos = sessions.await()
        val byClass = classes.await()
        // Buổi đã điểm danh: lấy số có mặt / sĩ số; lỗi thì chip không số.
        val attendance = dtos.filter { it.status == "taught" }
            .map { s -> s.id to async { soft { api.attendance(s.id) } } }
            .associate { (id, d) -> id to d }
        val list = dtos.mapNotNull { dto ->
            val start = toInstantOrNull(dto.startsAt) ?: return@mapNotNull null
            val end = toInstantOrNull(dto.endsAt) ?: return@mapNotNull null
            val cls = byClass[dto.classId]
            val att = attendance[dto.id]?.await()
            TodaySession(
                id = dto.id,
                className = dto.className,
                start = start,
                end = end,
                kind = classify(dto.status, start, end, now),
                enrolled = cls?.enrolledCount,
                location = cls?.location?.takeIf { it.isNotBlank() },
                attended = att?.let { it.summary.present + it.summary.late },
                total = att?.items?.size,
                status = dto.status,
            )
        }
        val next = if (list.isEmpty()) nextSession(today) else null
        TodayData(
            date = today,
            sessions = sortSessions(list),
            next = next,
            setupSteps = onboarding.await()?.let(::skippedSetupSteps).orEmpty(),
            // shortcut: chỉ trang đầu 100 học viên nợ, nâng cấp khi backend có endpoint tổng hợp
            overdue = receivables.await()?.let { overdueSummary(it.items) },
            unread = notifications.await()?.unreadCount ?: 0,
        )
    }

    private suspend fun nextSession(today: LocalDate): NextSession? {
        val page = soft { api.sessions(today.plusDays(1).toString(), today.plusDays(14).toString()) } ?: return null
        val s = earliestPlanned(page.items) ?: return null
        val start = toInstant(s.startsAt)
        return NextSession(s.className, start.atZone(clock.zone).toLocalDate(), formatTime(start, clock.zone))
    }

    /** Lỗi API của phần không bắt buộc: bỏ qua, trả `null`. */
    private suspend fun <T> soft(block: suspend () -> T): T? = try {
        apiCall(block)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
