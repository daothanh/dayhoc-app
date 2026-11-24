package vn.dayhoc.app

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
        setContent {
            TeachHubTheme {
                AppRoot(container)
            }
        }
    }
}
