package cloud.kl8techgroup.kl8wall.peer

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import cloud.kl8techgroup.kl8wall.KL8WallApplication
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import cloud.kl8techgroup.kl8wall.server.WifiHelper
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceInfo

/**
 * Manages the Peer-to-Peer mesh network between KL8Wall devices.
 * Handles discovery of other panels, periodic status queries, sensor relaying,
 * and command forwarding.
 */
class PeerManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "PeerManager"
        private const val SERVICE_TYPE = "_kl8wall._tcp.local."
        private const val PEER_STATUS_INTERVAL_MS = 30000L
        private const val PEER_EXPIRY_MS = 60000L
    }

    data class PeerInfo(
        val name: String,
        val ip: String,
        val port: Int,
        var mqttConnected: Boolean = false,
        var lastSeen: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pollingJob: Job? = null
    private var relayTrackingJob: Job? = null

    // Map of discovered peers, keyed by device name (friendly name)
    val peers = ConcurrentHashMap<String, PeerInfo>()

    // Track active external dynamic subscriptions to peer command topics
    private val activePeerSubscriptions = ConcurrentHashMap.newKeySet<String>()

    // Timestamp when this device last received a request to relay.
    // Used to track if this device is actively operating in relay mode.
    private var lastRelayUsageTime = 0L

    fun start() {
        Log.d(TAG, "Starting PeerManager...")
        startDiscovery()
        startPolling()
        startRelayTracking()
    }

    fun stop() {
        scope.cancel()
        pollingJob?.cancel()
        relayTrackingJob?.cancel()
        stopDiscovery()
        activePeerSubscriptions.clear()
        peers.clear()
    }

    private fun startDiscovery() {
        scope.launch {
            var delayMs = 2000L
            while (isActive) {
                try {
                    val wifiManager = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    multicastLock = wifiManager.createMulticastLock("kl8wall_peer_scan").apply {
                        setReferenceCounted(false)
                        acquire()
                    }

                    val resolvedIp = WifiHelper.getWifiIpAddress(context)
                    if (resolvedIp == null) {
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(30000L)
                        continue
                    }

                    val address = InetAddress.getByName(resolvedIp)
                    jmdns = JmDNS.create(address, "kl8wall-peer-browser")
                    jmdns?.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                        override fun serviceAdded(event: ServiceEvent) {
                            jmdns?.requestServiceInfo(event.type, event.name)
                        }

                        override fun serviceRemoved(event: ServiceEvent) {
                            val name = event.name.replace("KL8Wall-", "")
                            peers.remove(name)?.let {
                                Log.i(TAG, "Peer removed: $name")
                                handlePeerOffline(name)
                            }
                        }

                        override fun serviceResolved(event: ServiceEvent) {
                            val info = event.info
                            val name = event.name.replace("KL8Wall-", "")
                            if (name == settingsRepository.deviceName.value) return // Ignore self

                            val ip = info.inetAddresses.firstOrNull()?.hostAddress ?: return
                            val port = info.port
                            val isMqttConnected = info.propertyNames.toList()
                                .contains("mqtt_connected") && info.getPropertyString("mqtt_connected") == "true"

                            val peer = PeerInfo(
                                name = name,
                                ip = ip,
                                port = port,
                                mqttConnected = isMqttConnected,
                                lastSeen = System.currentTimeMillis()
                            )
                            peers[name] = peer
                            Log.i(TAG, "Peer discovered/resolved: $name at $ip:$port (MQTT connected: $isMqttConnected)")
                            
                            // Re-evaluate dynamic subscriptions based on updated status
                            scope.launch {
                                evaluateDynamicSubscriptions()
                            }
                        }
                    })
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start JmDNS peer browser, retrying...", e)
                    cleanupJmdns()
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(30000L)
                }
            }
        }
    }

    private fun stopDiscovery() {
        cleanupJmdns()
    }

    private fun cleanupJmdns() {
        try {
            jmdns?.close()
        } catch (_: Exception) {}
        jmdns = null
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                delay(PEER_STATUS_INTERVAL_MS)
                pruneAndPollPeers()
            }
        }
    }

    private suspend fun pruneAndPollPeers() {
        val now = System.currentTimeMillis()
        val iterator = peers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val peer = entry.value
            if (now - peer.lastSeen > PEER_EXPIRY_MS) {
                Log.i(TAG, "Peer expired due to inactivity: ${peer.name}")
                iterator.remove()
                handlePeerOffline(peer.name)
                continue
            }
            
            // Poll peer status over HTTP
            scope.launch {
                val success = pollPeerStatus(peer)
                if (!success) {
                    // Mark as offline if HTTP status check fails
                    peer.mqttConnected = false
                }
                evaluateDynamicSubscriptions()
            }
        }
    }

    private suspend fun pollPeerStatus(peer: PeerInfo): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://${peer.ip}:${peer.port}/api/status")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            
            val meshAuth = getMeshAuthToken()
            if (meshAuth.isNotEmpty()) {
                connection.setRequestProperty("x-kl8wall-mesh-auth", meshAuth)
            }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val isConnected = json.optBoolean("mqttConnected", false)
                peer.mqttConnected = isConnected
                peer.lastSeen = System.currentTimeMillis()
                return@withContext true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to poll status for peer ${peer.name}: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        false
    }

    private fun handlePeerOffline(peerName: String) {
        val topic = "kl8wall/$peerName/+/cmd"
        if (activePeerSubscriptions.remove(topic)) {
            val mqtt = KL8WallApplication.instance.mqttManager
            mqtt?.unsubscribeExternally(topic)
        }
    }

    private fun evaluateDynamicSubscriptions() {
        val mqtt = KL8WallApplication.instance.mqttManager ?: return
        if (!mqtt.isConnected()) {
            // If our own MQTT is disconnected, unsubscribe from everything
            activePeerSubscriptions.forEach { topic ->
                mqtt.unsubscribeExternally(topic)
            }
            activePeerSubscriptions.clear()
            return
        }

        // For each discovered peer
        peers.forEach { (name, peer) ->
            val cmdTopic = "kl8wall/$name/+/cmd"
            if (!peer.mqttConnected) {
                // If the peer's MQTT connection is down, we subscribe to their commands on their behalf
                if (activePeerSubscriptions.add(cmdTopic)) {
                    Log.i(TAG, "Subscribing to command relay topic on behalf of offline peer $name: $cmdTopic")
                    mqtt.subscribeExternally(cmdTopic)
                }
            } else {
                // If the peer is back online, unsubscribe
                if (activePeerSubscriptions.remove(cmdTopic)) {
                    Log.i(TAG, "Unsubscribing from relayed command topic since peer $name is back online: $cmdTopic")
                    mqtt.unsubscribeExternally(cmdTopic)
                }
            }
        }
    }

    /**
     * Called when a dynamic subscription receives a command meant for a peer.
     * Relays the message to the peer via a local HTTP request.
     */
    fun handleRelayedMqttMessage(topic: String, payload: String) {
        scope.launch {
            // Find target peer name from the topic (e.g., kl8wall/device_name/component/cmd)
            val parts = topic.split("/")
            if (parts.size < 2) return@launch
            val peerName = parts[1]
            val peer = peers[peerName] ?: return@launch
            
            Log.i(TAG, "Relaying command message to peer $peerName at ${peer.ip}: Topic=$topic, Payload=$payload")
            relayCommandToPeer(peer, topic, payload)
        }
    }

    private suspend fun relayCommandToPeer(peer: PeerInfo, topic: String, payload: String) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://${peer.ip}:${peer.port}/api/peer/command")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            
            val meshAuth = getMeshAuthToken()
            if (meshAuth.isNotEmpty()) {
                connection.setRequestProperty("x-kl8wall-mesh-auth", meshAuth)
            }

            val body = JSONObject().apply {
                put("topic", topic)
                put("payload", payload)
            }.toString()

            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Peer command relay response code: $responseCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to relay command to peer ${peer.name}", e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Relays a sensor report from this device to an online peer over HTTP.
     * Called when our own MQTT connection is offline.
     */
    fun sendRelayMessage(topic: String, payload: String): Boolean {
        // Find an online peer that has working MQTT
        val targetPeer = peers.values.firstOrNull { it.mqttConnected } ?: return false
        
        scope.launch {
            val success = relayMessageToPeer(targetPeer, topic, payload)
            if (success) {
                lastRelayUsageTime = System.currentTimeMillis()
            }
        }
        return true
    }

    private suspend fun relayMessageToPeer(peer: PeerInfo, topic: String, payload: String): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://${peer.ip}:${peer.port}/api/peer/relay")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            
            val meshAuth = getMeshAuthToken()
            if (meshAuth.isNotEmpty()) {
                connection.setRequestProperty("x-kl8wall-mesh-auth", meshAuth)
            }

            val body = JSONObject().apply {
                put("topic", topic)
                put("payload", payload)
            }.toString()

            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                
                val reconnectRequested = json.optBoolean("reconnect_requested", false)
                val resolvedBrokerIp = json.optString("broker_resolved_ip", "")

                if (reconnectRequested) {
                    Log.i(TAG, "Peer requested reconnection. Triggering MQTT reconnect...")
                    val app = context.applicationContext as KL8WallApplication
                    app.mqttManager?.reconnect()
                }

                if (resolvedBrokerIp.isNotEmpty()) {
                    Log.i(TAG, "Peer provided resolved broker IP: $resolvedBrokerIp. Applying override...")
                    val app = context.applicationContext as KL8WallApplication
                    app.mqttManager?.setBrokerIpOverride(resolvedBrokerIp)
                }
                
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send relay message to peer ${peer.name}", e)
        } finally {
            connection?.disconnect()
        }
        false
    }

    /**
     * Start the background tracking job that checks if we are stuck in peer relay mode
     * for too long and need a soft recovery restart.
     */
    private fun startRelayTracking() {
        relayTrackingJob = scope.launch {
            while (isActive) {
                delay(60000L) // Check every minute
                val now = System.currentTimeMillis()
                val isMqttConnected = KL8WallApplication.instance.mqttManager?.isConnected() == true
                
                // If we are actively relaying through peers (meaning we failed to connect directly)
                // and it has been more than 10 minutes (600,000ms) since we started relaying
                if (!isMqttConnected && lastRelayUsageTime > 0 && (now - lastRelayUsageTime > 600000L)) {
                    Log.w(TAG, "Device stuck in peer relay mode for >10 minutes. Triggering soft recovery reboot...")
                    
                    // Reset relay time to prevent loop if reboot is pending
                    lastRelayUsageTime = 0
                    
                    withContext(Dispatchers.Main) {
                        val app = context.applicationContext as KL8WallApplication
                        app.rebootApplication()
                    }
                }
            }
        }
    }

    fun getMeshAuthToken(): String {
        val password = settingsRepository.mqttPassword.value
        if (password.isBlank()) return ""
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
