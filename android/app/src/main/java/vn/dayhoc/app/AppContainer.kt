package vn.dayhoc.app

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import vn.dayhoc.app.core.auth.AuthApi
import vn.dayhoc.app.core.auth.AuthRepository
import vn.dayhoc.app.core.auth.KeystoreTokenStore
import vn.dayhoc.app.core.network.Network
import vn.dayhoc.app.core.session.MembershipStore
import vn.dayhoc.app.core.session.SessionApi
import vn.dayhoc.app.teacher.onboarding.OnboardingApi
import vn.dayhoc.app.teacher.today.TodayApi
import vn.dayhoc.app.teacher.onboarding.PendingFeeStore

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/** DI thủ công: một instance cho cả app, tạo ở [DayHocApp]. */
class AppContainer(context: Context) {
    val tokenStore = KeystoreTokenStore(context)

    /** Việc chạy nền không gắn với màn nào (báo vai trò đang mở lên server). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    val authRepository = AuthRepository(network.retrofit.create(AuthApi::class.java), tokenStore, sessionMutex)

    val sessionApi: SessionApi = network.retrofit.create(SessionApi::class.java)

    private val sessionPrefs = context.applicationContext.sessionDataStore

    val membershipStore = MembershipStore(sessionApi, sessionPrefs, appScope)

    val onboardingApi: OnboardingApi = network.retrofit.create(OnboardingApi::class.java)

    val todayApi: TodayApi = network.retrofit.create(TodayApi::class.java)

    val pendingFeeStore = PendingFeeStore(sessionPrefs)

    /**
     * Đăng xuất: thu hồi phiên (nếu được), xóa token và mọi trạng thái của phiên trên máy.
     */
    suspend fun logout() {
        authRepository.logout()
        forgetSession()
    }

    /**
     * Quên vai trò của phiên vừa hết (đăng xuất hoặc refresh bị từ chối). Học phí Trọn khóa đang chờ
     * thì giữ: chỉ có id lớp và số tiền, đăng nhập lại vẫn ghi được ở bước 3.
     */
    suspend fun forgetSession() {
        membershipStore.clear()
    }
}
