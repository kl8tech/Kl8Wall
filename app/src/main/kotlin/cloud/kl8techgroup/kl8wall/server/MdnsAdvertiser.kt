package cloud.kl8techgroup.kl8wall.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import cloud.kl8techgroup.kl8wall.BuildConfig
import cloud.kl8techgroup.kl8wall.KL8WallApplication
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
    
    private var currentHostname: String? = null
    private var currentPort: Int? = null
    private var activeServiceInfo: ServiceInfo? = null
    private var lastMqttState = false

    /**
     * Start advertising the service at the given [hostname] and [port].
     *
     * Acquires a multicast lock, creates the JmDNS responder, and
     * registers the service with device metadata in TXT records.
     * This method blocks while JmDNS probes the network — call from
     * a background thread.
     */
    @Synchronized
    fun start(hostname: String, port: Int) {
        currentHostname = hostname
        currentPort = port

        try {
            val app = context.applicationContext as KL8WallApplication
            val j = app.getOrCreateJmdns(hostname)
            jmdns = j
            if (j != null) {
                rebuildServiceInfo()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start mDNS advertising", e)
        }
    }

    @Synchronized
    private fun rebuildServiceInfo() {
        val j = jmdns ?: return
        val port = currentPort ?: return

        activeServiceInfo?.let {
            try {
                j.unregisterService(it)
            } catch (_: Exception) {}
        }

        val app = context.applicationContext as? KL8WallApplication
        val isMqttConnected = app?.mqttManager?.isConnected() == true
        lastMqttState = isMqttConnected

        val deviceName = app?.settingsRepository?.deviceName?.value?.ifBlank { Build.MODEL } ?: Build.MODEL

        val props = mapOf(
            "version" to BuildConfig.VERSION_NAME,
            "device" to Build.MODEL,
            "mqtt_connected" to isMqttConnected.toString()
        )

        val serviceInfo = ServiceInfo.create(
            SERVICE_TYPE,
            "KL8Wall-$deviceName",
            port,
            0,
            0,
            props
        )
        activeServiceInfo = serviceInfo
        try {
            j.registerService(serviceInfo)
            android.util.Log.i(TAG, "Registered mDNS service: KL8Wall-$deviceName on port $port (mqtt_connected=$isMqttConnected)")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to register mDNS service info", e)
        }
    }

    @Synchronized
    fun updateMqttState() {
        val app = context.applicationContext as? KL8WallApplication
        val isMqttConnected = app?.mqttManager?.isConnected() == true
        if (isMqttConnected != lastMqttState) {
            android.util.Log.i(TAG, "Updating mDNS advertisement with mqtt_connected=$isMqttConnected")
            rebuildServiceInfo()
        }
    }

    /** Stop advertising. Sleeps briefly after unregistering to allow the goodbye packet to propagate. */
    @Synchronized
    fun stop() {
        val j = jmdns
        val service = activeServiceInfo
        if (j != null && service != null) {
            try {
                j.unregisterService(service)
                Thread.sleep(200)
            } catch (_: Exception) {}
        }
        jmdns = null
        activeServiceInfo = null
    }

    companion object {
        private const val TAG = "MdnsAdvertiser"
        /** mDNS service type for KL8Wall device discovery. */
        const val SERVICE_TYPE = "_kl8wall._tcp.local."
        private const val LOCK_TAG = "kl8wall_mdns"
        private const val JMDNS_NAME = "kl8wall"
    }
}
