package cloud.kl8techgroup.kl8wall.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import cloud.kl8techgroup.kl8wall.BuildConfig
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises the KL8Wall HTTP server via mDNS (Bonjour/Zeroconf).
 *
 * Registers a `_kl8wall._tcp.local.` service so that Home Assistant
 * and admin tools can auto-discover the device on the local network.
 * Acquires a WiFi multicast lock for the duration of advertisement.
 */
class MdnsAdvertiser(private val context: Context) {

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Start advertising the service at the given [hostname] and [port].
     *
     * Acquires a multicast lock, creates the JmDNS responder, and
     * registers the service with device metadata in TXT records.
     * This method blocks while JmDNS probes the network — call from
     * a background thread.
     */
    fun start(hostname: String, port: Int) {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        multicastLock = wifiManager.createMulticastLock(LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }

        val address = InetAddress.getByName(hostname)
        jmdns = JmDNS.create(address, JMDNS_NAME)

        val props = mapOf(
            "version" to BuildConfig.VERSION_NAME,
            "device" to Build.MODEL
        )

        val serviceInfo = ServiceInfo.create(
            SERVICE_TYPE,
            "KL8Wall-${Build.MODEL}",
            port,
            0,
            0,
            props
        )
        jmdns?.registerService(serviceInfo)
    }

    /** Stop advertising and release the multicast lock. */
    fun stop() {
        jmdns?.unregisterAllServices()
        jmdns?.close()
        jmdns = null

        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    companion object {
        /** mDNS service type for KL8Wall device discovery. */
        const val SERVICE_TYPE = "_kl8wall._tcp.local."
        private const val LOCK_TAG = "kl8wall_mdns"
        private const val JMDNS_NAME = "kl8wall"
    }
}
