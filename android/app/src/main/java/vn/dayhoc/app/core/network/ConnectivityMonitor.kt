package vn.dayhoc.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Trạng thái mạng của máy ([online]); [onAvailable] chạy mỗi khi có mạng trở lại. */
interface ConnectivityMonitor {
    val online: StateFlow<Boolean>
}

class AndroidConnectivityMonitor(context: Context, private val onAvailable: () -> Unit) : ConnectivityMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isOnline())
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = true
                    onAvailable()
                }

                override fun onLost(network: Network) {
                    _online.value = isOnline()
                }
            },
        )
    }

    private fun isOnline(): Boolean =
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
