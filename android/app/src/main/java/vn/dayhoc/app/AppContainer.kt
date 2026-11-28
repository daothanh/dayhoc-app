package vn.dayhoc.app

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import vn.dayhoc.app.core.auth.AuthApi
import vn.dayhoc.app.core.auth.AuthRepository
import vn.dayhoc.app.core.auth.KeystoreTokenStore
import vn.dayhoc.app.core.localdb.LocalStore
import vn.dayhoc.app.core.network.AndroidConnectivityMonitor
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.core.network.ConnectivityMonitor
import vn.dayhoc.app.core.network.Network
import vn.dayhoc.app.core.session.MembershipStore
import vn.dayhoc.app.core.session.SessionApi
import vn.dayhoc.app.teacher.onboarding.OnboardingApi
import vn.dayhoc.app.teacher.attendance.AttendanceApi
import vn.dayhoc.app.teacher.attendance.AttendanceNotice
import vn.dayhoc.app.teacher.attendance.AttendanceSyncEngine
import vn.dayhoc.app.teacher.attendance.AttendanceSyncWorker
import vn.dayhoc.app.teacher.attendance.FlushResult
import vn.dayhoc.app.teacher.attendance.OutboxOwner
import vn.dayhoc.app.teacher.attendance.OutboxState
import vn.dayhoc.app.teacher.attendance.SyncEvent
import vn.dayhoc.app.teacher.today.OutboxSessions
import vn.dayhoc.app.teacher.today.TodayApi
import vn.dayhoc.app.teacher.onboarding.PendingFeeStore

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/** DI thủ công: một instance cho cả app, tạo ở [DayHocApp]. */
class AppContainer(context: Context) {
    val tokenStore = KeystoreTokenStore(context)

    /** Việc chạy nền không gắn với màn nào (báo vai trò đang mở lên server). */
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e("DayHocApp", "Việc nền lỗi", e) },
    )

    /** Khóa chung cho refresh, đăng nhập, đăng xuất: không để kết quả refresh cũ ghi đè phiên mới. */
    private val sessionMutex = Mutex()

    private val network = Network(
        apiUrl = BuildConfig.API_URL,
        tokenStore = tokenStore,
        clientInfo = "teachhub-app/${BuildConfig.VERSION_NAME} (android)",
        httpLogging = BuildConfig.DEBUG && BuildConfig.HTTP_LOGGING,
        sessionMutex = sessionMutex,
    )

    /** `true` khi refresh token bị thu hồi; gốc điều hướng về màn đăng nhập rồi đặt lại `false`. */
    val sessionExpired = network.authenticator.sessionExpired

    /** Mã một lần từ deep link `dayhoc://auth/apple/callback`; màn đăng nhập lấy rồi đặt lại `null`. */
    val appleCallbackCode = MutableStateFlow<String?>(null)

    /** CSDL cục bộ mã hóa: outbox điểm danh, bản chụp danh sách học sinh, mã thiết bị, chủ dữ liệu. */
    val localStore = LocalStore(context)

    val authRepository = AuthRepository(
        network.retrofit.create(AuthApi::class.java), tokenStore, sessionMutex, onUser = localStore::rememberUser,
    )

    val sessionApi: SessionApi = network.retrofit.create(SessionApi::class.java)

    private val sessionPrefs = context.applicationContext.sessionDataStore

    val membershipStore = MembershipStore(sessionApi, sessionPrefs, appScope)

    val onboardingApi: OnboardingApi = network.retrofit.create(OnboardingApi::class.java)

    val todayApi: TodayApi = network.retrofit.create(TodayApi::class.java)

    val pendingFeeStore = PendingFeeStore(sessionPrefs)

    private val appContext = context.applicationContext

    val attendanceApi: AttendanceApi = network.retrofit.create(AttendanceApi::class.java)

    val syncEngine = AttendanceSyncEngine(attendanceApi, localStore, localStore) { localStore.owner.value }

    /** Thông báo T03 hiện sau khi T04 đóng hoặc khi đồng bộ nền bỏ lô; T03 lấy rồi đặt lại `null`. */
    val attendanceNotice = MutableStateFlow<AttendanceNotice?>(null)

    val connectivity: ConnectivityMonitor = AndroidConnectivityMonitor(context) { requestSync() }

    /** Buổi có lô trong outbox của chủ hiện tại (chờ hoặc lỗi), đọc từ outbox nên không cần mạng. */
    val outboxSessions: Flow<OutboxSessions> = combine(localStore.batches, localStore.owner) { list, owner ->
        val mine = list.filter { it.owner == owner }
        OutboxSessions(mine.map { it.sessionId }.toSet(), mine.filter { it.state == OutboxState.Failed }.map { it.sessionId }.toSet())
    }.distinctUntilChanged()

    init {
        appScope.launch {
            syncEngine.events.collect { e ->
                when {
                    e is SyncEvent.Dropped -> attendanceNotice.value = AttendanceNotice.Dropped(e.code)
                    e is SyncEvent.Synced && e.rejected > 0 -> attendanceNotice.value = AttendanceNotice.PartlyRejected(e.rejected)
                }
            }
        }
    }

    /**
     * Gắn chủ của dữ liệu ngoại tuyến = người dùng (lưu bền từ lúc đăng nhập; chưa có thì hỏi `auth/me`) + tenant đang mở.
     * Gọi trước khi vào không gian giáo viên và mỗi khi đổi tenant. Chưa xác định được thì không gắn: T04 không cho lưu
     * và SyncEngine không gửi gì (đóng kín khi chưa biết chủ).
     */
    suspend fun bindAttendanceOwner(tenantId: String?) {
        tenantId ?: return
        val userId = localStore.userId() ?: try {
            authRepository.me().id.also(localStore::rememberUser)
        } catch (e: ApiError) {
            null
        } ?: return
        localStore.adopt(OutboxOwner(userId, tenantId))
    }

    /** Chủ hiện tại; chưa gắn thì thử gắn lại theo vai trò đang mở (nút "Thử lại" của T04). */
    suspend fun currentOwner(): OutboxOwner? {
        localStore.owner.value?.let { return it }
        bindAttendanceOwner(membershipStore.state.value.active?.tenantId)
        return localStore.owner.value
    }

    /** Số buổi còn điểm danh chưa đồng bộ (chờ hoặc lỗi): hộp thoại đăng xuất. */
    suspend fun unsyncedSessionCount(): Int = localStore.unsyncedSessionCount()

    /** Gửi outbox nếu đã đăng nhập (worker chạy lúc app chưa mở nên tự nạp token). */
    suspend fun syncNow(): FlushResult =
        if (tokenStore.tokens == null && tokenStore.load() == null) FlushResult.Done else syncEngine.flush().result

    /** Số lần gửi đang chạy hoặc đang chờ: nhiều yêu cầu dồn lại, tối đa một lần chạy và một lần chờ. */
    private val syncRequests = AtomicInteger(0)

    /** Khi mở app, quay lại màn, có mạng trở lại: gửi ngay; còn dở thì hẹn WorkManager. Đã có một lần đang chờ thì không xếp thêm. */
    fun requestSync() {
        if (syncRequests.incrementAndGet() > 2) {
            syncRequests.decrementAndGet()
            return
        }
        appScope.launch {
            try {
                if (syncNow() == FlushResult.Retry) AttendanceSyncWorker.schedule(appContext)
            } finally {
                syncRequests.decrementAndGet()
            }
        }
    }

    /** Vừa lưu một lô vào outbox: gửi ngay, và hẹn chạy nền khi có mạng. */
    fun onOutboxEnqueued() {
        AttendanceSyncWorker.schedule(appContext)
        requestSync()
    }

    /**
     * Đăng xuất: thu hồi phiên (nếu được), xóa token và mọi trạng thái của phiên trên máy.
     */
    suspend fun logout() {
        authRepository.logout()
        forgetSession()
        // Xóa dữ liệu ngoại tuyến (CSDL mã hóa và khóa) sau khi đã hủy việc đồng bộ nền.
        AttendanceSyncWorker.cancel(appContext)
        // Đợi lần gửi đang chạy xong rồi mới xóa; lần gửi sau thấy chưa có chủ nên không mở lại CSDL.
        syncEngine.exclusive { localStore.wipe() }
    }

    /**
     * Quên vai trò của phiên vừa hết (đăng xuất hoặc refresh bị từ chối). Học phí Trọn khóa đang chờ
     * thì giữ: chỉ có id lớp và số tiền, đăng nhập lại vẫn ghi được ở bước 3.
     */
    suspend fun forgetSession() {
        membershipStore.clear()
        // Hết phiên: bỏ gắn chủ để SyncEngine không gửi gì; lô giữ nguyên chờ chủ đó đăng nhập lại.
        localStore.unbindOwner()
    }
}
