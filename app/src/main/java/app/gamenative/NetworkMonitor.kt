package app.gamenative

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide reactive network state.
 * Call [init] once from [PluviaApp.onCreate]; never unregistered.
 */
object NetworkMonitor {

    private val _hasInternet = MutableStateFlow(false)
    val hasInternet: StateFlow<Boolean> = _hasInternet.asStateFlow()

    private val _isWifiConnected = MutableStateFlow(false)
    val isWifiConnected: StateFlow<Boolean> = _isWifiConnected.asStateFlow()

    private val initialized = AtomicBoolean(false)

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCaps = ConcurrentHashMap<Network, NetworkCapabilities>()

        fun skip(caps: NetworkCapabilities) =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)

        fun update() {
            val validatedCaps = networkCaps.values.filter {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            _hasInternet.value = validatedCaps.isNotEmpty()
            // only count WiFi/Ethernet that is also validated (excludes captive portals)
            _isWifiConnected.value = validatedCaps.any {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
        }

        // seed from current state before callback fires
        cm.activeNetwork?.let { network ->
            cm.getNetworkCapabilities(network)?.let { caps ->
                if (!skip(caps)) {
                    networkCaps[network] = caps
                    update()
                }
            }
        }

        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (skip(caps)) return
                    networkCaps[network] = caps
                    update()
                }

                override fun onLost(network: Network) {
                    networkCaps.remove(network)
                    update()
                }
            },
        )
    }
}
