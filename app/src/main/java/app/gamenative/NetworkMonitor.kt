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

    private val _hasWifiOrEthernet = MutableStateFlow(false)
    val hasWifiOrEthernet: StateFlow<Boolean> = _hasWifiOrEthernet.asStateFlow()

    private val initialized = AtomicBoolean(false)

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCaps = ConcurrentHashMap<Network, NetworkCapabilities>()

        fun skip(caps: NetworkCapabilities) =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)

        // VPN networks can report stale transports (e.g. WIFI+VPN after wifi drops)
        fun hasVpn(caps: NetworkCapabilities) =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        fun update() {
            val validatedCaps = networkCaps.values.filter {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            val nonVpnCaps = validatedCaps.filter { !hasVpn(it) }
            val nonVpnExists = networkCaps.values.any { !hasVpn(it) }
            // trust VPN for internet only if a non-VPN network physically exists
            // (guards against stale VPN after underlying WiFi drops;
            //  allows VPN in censorship scenarios where WiFi exists but isn't validated)
            val vpnValidated = validatedCaps.any { hasVpn(it) }
            _hasInternet.value = nonVpnCaps.isNotEmpty() || (vpnValidated && nonVpnExists)
            // WiFi/Ethernet transport: only trust non-VPN networks (VPN reports stale transports).
            // known edge case: censored WiFi + VPN → WiFi not validated → hasWifiOrEthernet=false,
            // so "WiFi only" blocks downloads. user must disable "WiFi only" to download via VPN.
            // fixing this would risk treating always-on VPN without real WiFi as valid.
            _hasWifiOrEthernet.value = nonVpnCaps.any {
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
