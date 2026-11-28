package vn.dayhoc.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import vn.dayhoc.app.core.designsystem.TeachHubTheme
import vn.dayhoc.app.shell.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cửa sổ mở với Theme.DayHoc.Launch (nền lime + ký hiệu D). Đổi ngay về Theme.DayHoc
        // để Compose vẽ nền theo token thay vì giữ launch background.
        setTheme(R.style.Theme_DayHoc)
        enableEdgeToEdge()
        val container = (application as DayHocApp).container
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent {
            TeachHubTheme {
                AppRoot(container)
            }
        }
    }

    /** Mở app hoặc quay lại: gửi outbox điểm danh còn chờ (US-3.5). */
    override fun onStart() {
        super.onStart()
        (application as DayHocApp).container.requestSync()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "dayhoc" || uri.host != "auth" || uri.path != "/apple/callback") return
        val code = uri.getQueryParameter("code") ?: return
        (application as DayHocApp).container.appleCallbackCode.value = code
    }
}
