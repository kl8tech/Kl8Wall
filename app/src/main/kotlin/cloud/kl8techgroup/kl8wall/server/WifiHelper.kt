package cloud.kl8techgroup.kl8wall.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Resolves the device's WiFi interface IPv4 address.
 *
 * Used to bind the HTTP server exclusively to the WiFi interface,
 * ensuring it never listens on 0.0.0.0 or a cellular interface.
 */
object WifiHelper {

    /**
     * Return the IPv4 address of the active WiFi connection.
     *
     * Returns null if WiFi is not connected or no IPv4 address
     * is assigned to the interface.
     */
    fun getWifiIpAddress(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val capabilities = cm.getNetworkCapabilities(network)

        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }

        return cm.getLinkProperties(network)
            ?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
