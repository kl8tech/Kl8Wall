package cloud.kl8techgroup.kl8wall.server

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Discovers Home Assistant instances on the local network via mDNS.
 *
 * Scans for `_home-assistant._tcp.local.` services and emits each
 * discovered instance as an [HaInstance]. The scan acquires a WiFi
 * multicast lock for the duration and cleans up on flow cancellation.
 */
object HaDiscovery {

    /** A discovered Home Assistant instance. */
    data class HaInstance(
        val name: String,
        val url: String
    )

    /**
     * Emit discovered HA instances as a cold [Flow].
     *
     * Each emission is a single instance found on the network. The flow
     * holds a multicast lock and JmDNS session until cancelled. Runs
     * on [Dispatchers.IO] since JmDNS blocks during setup.
     */
    fun discover(context: Context): Flow<HaInstance> = callbackFlow {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        val lock = wifiManager.createMulticastLock(LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiIp = WifiHelper.getWifiIpAddress(context)
        val address = if (wifiIp != null) InetAddress.getByName(wifiIp) else null
        val jmdns = if (address != null) JmDNS.create(address, JMDNS_NAME) else JmDNS.create()

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmdns.requestServiceInfo(event.type, event.name)
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                val port = info.port
                if (port == HTTPS_PORT) {
                    trySend(HaInstance(name = "${info.name} (HTTPS)", url = "https://$host:$port"))
                } else {
                    trySend(HaInstance(name = "${info.name} (HTTP)", url = "http://$host:$port"))
                    trySend(HaInstance(name = "${info.name} (HTTPS)", url = "https://$host:$port"))
                }
            }

            override fun serviceRemoved(event: ServiceEvent) {
                // Ignored — we only care about additions during a scan window
            }
        }

        jmdns.addServiceListener(SERVICE_TYPE, listener)

        awaitClose {
            jmdns.removeServiceListener(SERVICE_TYPE, listener)
            jmdns.close()
            if (lock.isHeld) lock.release()
        }
    }.flowOn(Dispatchers.IO)

    private const val SERVICE_TYPE = "_home-assistant._tcp.local."
    private const val LOCK_TAG = "kl8wall_ha_discovery"
    private const val JMDNS_NAME = "kl8wall-discovery"
    private const val HTTPS_PORT = 443
}
