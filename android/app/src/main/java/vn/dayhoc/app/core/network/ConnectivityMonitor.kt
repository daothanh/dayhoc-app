package vn.dayhoc.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Trạng thái mạng của máy ([online]); [onAvailable] chạy mỗi khi có mạng trở lại. */
interface ConnectivityMonitor {
    val online: StateFlow<Boolean>
}

class AndroidConnectivityMonitor(context: Context, private val onAvailable: () -> Unit) : ConnectivityMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isValidated(manager.getNetworkCapabilities(manager.activeNetwork)))
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        // Mạng mặc định và đã được hệ thống kiểm có ra Internet (Wi-Fi cổng đăng nhập, mạng chưa thông không tính).
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val validated = isValidated(capabilities)
                    val was = _online.getAndUpdate { validated }
                    if (validated && !was) onAvailable()
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            },
        )
    }

    private fun isValidated(caps: NetworkCapabilities?): Boolean =
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}
