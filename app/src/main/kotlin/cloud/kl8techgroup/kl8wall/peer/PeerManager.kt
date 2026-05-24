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
        private const val PEER_EXPIRY_MS = 1800000L
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

    @Volatile
    var activeSyncCode: String? = null
        private set
    @Volatile
    var syncCodeExpiry: Long = 0L
        private set

    // Track active external dynamic subscriptions to peer command topics
    private val activePeerSubscriptions = ConcurrentHashMap.newKeySet<String>()

    private class EsphomeProxy(
        val peerName: String,
        val localPort: Int,
        val serverSocket: java.net.ServerSocket,
        val serviceInfo: javax.jmdns.ServiceInfo,
        val job: kotlinx.coroutines.Job
    )

    private val activeProxies = java.util.concurrent.ConcurrentHashMap<String, EsphomeProxy>()

    @Volatile
    var localHaUrl: String? = null
        private set

    // Timestamp when this device last received a request to relay.
    // Used to track if this device is actively operating in relay mode.
    private var lastRelayUsageTime = 0L

    fun start() {
        Log.d(TAG, "Starting PeerManager...")
        startDiscovery()
        startPolling()
        startRelayTracking()

        scope.launch {
            settingsRepository.manualPeers.collect {
                Log.d(TAG, "manualPeers settings changed. Triggering immediate manual peer poll.")
                pollManualPeers()
            }
        }

        scope.launch {
            val app = context.applicationContext as? KL8WallApplication
            while (app?.mqttManager == null && isActive) {
                delay(200)
            }
            app?.mqttManager?.connectionState?.collect { state ->
                Log.i(TAG, "Local MQTT connection state changed to: $state. Re-evaluating dynamic subscriptions and proxies.")
                evaluateDynamicSubscriptions()
                evaluateEsphomeProxies()
            }
        }
    }

    fun stop() {
        scope.cancel()
        pollingJob?.cancel()
        relayTrackingJob?.cancel()
        stopDiscovery()
        activePeerSubscriptions.clear()
        peers.clear()
        activeProxies.keys.forEach { name ->
            stopEsphomeProxy(name)
        }
        activeProxies.clear()
    }

    @Synchronized
    fun restart() {
        Log.i(TAG, "Restarting PeerManager...")
        stopDiscovery()
        pollingJob?.cancel()
        relayTrackingJob?.cancel()
        activePeerSubscriptions.clear()
        peers.clear()
        activeProxies.keys.forEach { name ->
            stopEsphomeProxy(name)
        }
        activeProxies.clear()
        
        startDiscovery()
        startPolling()
        startRelayTracking()
    }

    private fun startDiscovery() {
        scope.launch {
            var delayMs = 2000L
            while (isActive) {
                try {
                    val resolvedIp = WifiHelper.getWifiIpAddress(context)
                    if (resolvedIp == null) {
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(30000L)
                        continue
                    }

                    val app = context.applicationContext as KL8WallApplication
                    val j = app.getOrCreateJmdns(resolvedIp)
                    if (j == null) {
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(30000L)
                        continue
                    }
                    jmdns = j
                    j.addServiceListener(SERVICE_TYPE, object : ServiceListener {
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
                            
                            // Re-evaluate dynamic subscriptions and proxies based on updated status
                            scope.launch {
                                evaluateDynamicSubscriptions()
                                evaluateEsphomeProxies()
                            }
                        }
                    })

                    jmdns?.addServiceListener("_home-assistant._tcp.local.", object : ServiceListener {
                        override fun serviceAdded(event: ServiceEvent) {
                            jmdns?.requestServiceInfo(event.type, event.name)
                        }

                        override fun serviceRemoved(event: ServiceEvent) {}

                        override fun serviceResolved(event: ServiceEvent) {
                            val info = event.info
                            val ip = info.inetAddresses.firstOrNull()?.hostAddress
                            if (ip != null) {
                                val port = info.port
                                val scheme = if (port == 443 || info.propertyNames.toList().contains("requires_ssl")) "https" else "http"
                                localHaUrl = "$scheme://$ip:$port"
                                Log.i(TAG, "Discovered local Home Assistant instance: $localHaUrl")
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
        jmdns = null
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                delay(PEER_STATUS_INTERVAL_MS)
                try {
                    jmdns?.list(SERVICE_TYPE)
                    jmdns?.list("_home-assistant._tcp.local.")
                } catch (e: Exception) {
                    Log.w(TAG, "Error performing active mDNS poll", e)
                }
                pruneAndPollPeers()
                pollManualPeers()
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
                evaluateEsphomeProxies()
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

    private fun pollManualPeers() {
        val manualList = settingsRepository.manualPeers.value.trim()
        if (manualList.isEmpty()) return
        
        val hosts = manualList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        hosts.forEach { hostStr ->
            scope.launch {
                val parts = hostStr.split(":")
                val ip = parts[0]
                val port = if (parts.size > 1) parts[1].toIntOrNull() ?: settingsRepository.httpPort.value else settingsRepository.httpPort.value
                
                pollManualPeerInfo(ip, port)
            }
        }
    }

    private suspend fun pollManualPeerInfo(ip: String, port: Int) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://$ip:$port/api/peer/public_config")
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
                val name = json.optString("deviceName", "")
                if (name.isNotEmpty() && name != settingsRepository.deviceName.value) {
                    val mqttConn = pollMqttStatusForPeer(ip, port)
                    val peer = PeerInfo(
                        name = name,
                        ip = ip,
                        port = port,
                        mqttConnected = mqttConn,
                        lastSeen = System.currentTimeMillis()
                    )
                    peers[name] = peer
                    Log.d(TAG, "Manual peer updated: $name at $ip:$port (MQTT connected: $mqttConn)")
                    
                    // Re-evaluate dynamic subscriptions and proxies based on updated status
                    scope.launch {
                        evaluateDynamicSubscriptions()
                        evaluateEsphomeProxies()
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to poll manual peer info at $ip:$port: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun pollMqttStatusForPeer(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://$ip:$port/api/status")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            
            val meshAuth = getMeshAuthToken()
            if (meshAuth.isNotEmpty()) {
                connection.setRequestProperty("x-kl8wall-mesh-auth", meshAuth)
            }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                return@withContext json.optBoolean("mqttConnected", false)
            }
        } catch (_: Exception) {}
        finally {
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
        val callTopic = "kl8wall/$peerName/call/+"
        if (activePeerSubscriptions.remove(callTopic)) {
            val mqtt = KL8WallApplication.instance.mqttManager
            mqtt?.unsubscribeExternally(callTopic)
        }
        stopEsphomeProxy(peerName)
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
            val callTopic = "kl8wall/$name/call/+"
            if (!peer.mqttConnected) {
                // If the peer's MQTT connection is down, we subscribe to their commands on their behalf
                if (activePeerSubscriptions.add(cmdTopic)) {
                    Log.i(TAG, "Subscribing to command relay topic on behalf of offline peer $name: $cmdTopic")
                    mqtt.subscribeExternally(cmdTopic)
                }
                if (activePeerSubscriptions.add(callTopic)) {
                    Log.i(TAG, "Subscribing to call relay topic on behalf of offline peer $name: $callTopic")
                    mqtt.subscribeExternally(callTopic)
                }
            } else {
                // If the peer is back online, unsubscribe
                if (activePeerSubscriptions.remove(cmdTopic)) {
                    Log.i(TAG, "Unsubscribing from relayed command topic since peer $name is back online: $cmdTopic")
                    mqtt.unsubscribeExternally(cmdTopic)
                }
                if (activePeerSubscriptions.remove(callTopic)) {
                    Log.i(TAG, "Unsubscribing from relayed call topic since peer $name is back online: $callTopic")
                    mqtt.unsubscribeExternally(callTopic)
                }
            }
        }
    }

    /**
     * Called when a dynamic subscription receives a command meant for a peer.
     * Relays the message to the peer via a local HTTP request.
     */
    fun handleRelayedMqttMessage(topic: String, payload: String, isBase64: Boolean = false) {
        scope.launch {
            // Find target peer name from the topic (e.g., kl8wall/device_name/component/cmd)
            val parts = topic.split("/")
            if (parts.size < 2) return@launch
            val peerName = parts[1]
            val peer = peers[peerName] ?: return@launch
            
            Log.i(TAG, "Relaying command message to peer $peerName at ${peer.ip}: Topic=$topic, Payload=$payload")
            relayCommandToPeer(peer, topic, payload, isBase64)
        }
    }

    private suspend fun relayCommandToPeer(peer: PeerInfo, topic: String, payload: String, isBase64: Boolean = false) = withContext(Dispatchers.IO) {
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
                put("is_base64", isBase64)
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
    fun sendRelayMessage(topic: String, payload: String, isBase64: Boolean = false): Boolean {
        val candidates = peers.values.filter { it.mqttConnected }.toList()
        if (candidates.isEmpty()) return false
        
        scope.launch {
            for (peer in candidates) {
                val success = relayMessageToPeer(peer, topic, payload, isBase64)
                if (success) {
                    lastRelayUsageTime = System.currentTimeMillis()
                    break // Relay succeeded, stop trying others
                } else {
                    Log.w(TAG, "Relay to peer ${peer.name} failed, trying next candidate if available...")
                    // Mark this peer as disconnected locally so we don't spam it
                    peer.mqttConnected = false
                }
            }
        }
        return true
    }

    private suspend fun relayMessageToPeer(peer: PeerInfo, topic: String, payload: String, isBase64: Boolean = false): Boolean = withContext(Dispatchers.IO) {
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
                put("is_base64", isBase64)
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

    fun generateSyncCode(): String {
        val code = (100000..999999).random().toString()
        activeSyncCode = code
        syncCodeExpiry = System.currentTimeMillis() + 120000 // 2 minutes
        Log.i(TAG, "Generated configuration sync code: $code")
        return code
    }

    fun verifySyncCode(code: String): Boolean {
        val active = activeSyncCode ?: return false
        if (System.currentTimeMillis() > syncCodeExpiry) {
            activeSyncCode = null
            return false
        }
        val match = active == code.trim()
        if (match) {
            activeSyncCode = null // consume on success
        }
        return match
    }

    private fun evaluateEsphomeProxies() {
        val mqtt = KL8WallApplication.instance.mqttManager ?: return
        if (!mqtt.isConnected()) {
            activeProxies.keys.forEach { name ->
                stopEsphomeProxy(name)
            }
            return
        }

        peers.forEach { (name, peer) ->
            if (!peer.mqttConnected) {
                if (!activeProxies.containsKey(name)) {
                    startEsphomeProxy(peer)
                }
            } else {
                if (activeProxies.containsKey(name)) {
                    stopEsphomeProxy(name)
                }
            }
        }

        activeProxies.keys.forEach { name ->
            if (!peers.containsKey(name)) {
                stopEsphomeProxy(name)
            }
        }
    }

    private fun startEsphomeProxy(peer: PeerInfo) {
        val peerName = peer.name
        val peerIp = peer.ip
        val port = 6100 + (peerName.hashCode() and 0x7FFF) % 100
        
        Log.i(TAG, "Starting ESPHome proxy for $peerName on port $port -> $peerIp:6053")
        
        val serverSocket = try {
            java.net.ServerSocket(port)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind proxy ServerSocket on port $port for $peerName", e)
            return
        }
        
        val proxyJob = scope.launch {
            while (isActive) {
                try {
                    val clientSocket = serverSocket.accept()
                    launch {
                        handleProxyConnection(clientSocket, peerIp, 6053)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error accepting proxy connection on port $port for $peerName", e)
                    }
                }
            }
        }
        
        val txtRecords = mapOf(
            "version" to "2026.1.0",
            "device" to "KL8Wall Proxy",
            "mac" to getPseudoMacAddress(peerName)
        )
        val serviceInfo = ServiceInfo.create(
            "_esphomelib._tcp.local.",
            "KL8Wall-BLE-$peerName",
            port,
            0,
            0,
            txtRecords
        )
        
        try {
            jmdns?.registerService(serviceInfo)
            Log.i(TAG, "Registered proxy mDNS service: KL8Wall-BLE-$peerName on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register proxy mDNS service for $peerName", e)
        }
        
        activeProxies[peerName] = EsphomeProxy(
            peerName = peerName,
            localPort = port,
            serverSocket = serverSocket,
            serviceInfo = serviceInfo,
            job = proxyJob
        )
    }

    private fun getPseudoMacAddress(peerName: String): String {
        val hash = peerName.hashCode().toLong()
        val macBytes = byteArrayOf(
            0x02,
            ((hash shr 24) and 0xFF.toLong()).toByte(),
            ((hash shr 16) and 0xFF.toLong()).toByte(),
            ((hash shr 8) and 0xFF.toLong()).toByte(),
            (hash and 0xFF.toLong()).toByte(),
            0x01
        )
        return macBytes.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
    }

    private fun stopEsphomeProxy(peerName: String) {
        val proxy = activeProxies.remove(peerName) ?: return
        Log.i(TAG, "Stopping ESPHome proxy for $peerName on port ${proxy.localPort}")
        proxy.job.cancel()
        try {
            proxy.serverSocket.close()
        } catch (_: Exception) {}
        try {
            jmdns?.unregisterService(proxy.serviceInfo)
        } catch (_: Exception) {}
    }

    private suspend fun handleProxyConnection(clientSocket: java.net.Socket, destIp: String, destPort: Int) = withContext(Dispatchers.IO) {
        var peerSocket: java.net.Socket? = null
        try {
            Log.d(TAG, "New proxy connection from ${clientSocket.remoteSocketAddress} to relay for $destIp:$destPort")
            peerSocket = java.net.Socket(destIp, destPort)
            
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val peerIn = peerSocket.getInputStream()
            val peerOut = peerSocket.getOutputStream()
            
            coroutineScope {
                val job1 = launch {
                    try {
                        val buffer = ByteArray(4096)
                        var read: Int
                        while (isActive) {
                            read = clientIn.read(buffer)
                            if (read == -1) break
                            peerOut.write(buffer, 0, read)
                            peerOut.flush()
                        }
                    } catch (_: Exception) {}
                }
                val job2 = launch {
                    try {
                        val buffer = ByteArray(4096)
                        var read: Int
                        while (isActive) {
                            read = peerIn.read(buffer)
                            if (read == -1) break
                            clientOut.write(buffer, 0, read)
                            clientOut.flush()
                        }
                    } catch (_: Exception) {}
                }
                
                while (job1.isActive && job2.isActive) {
                    delay(100)
                }
                job1.cancel()
                job2.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in proxy connection relay for $destIp:$destPort", e)
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { peerSocket?.close() } catch (_: Exception) {}
            Log.d(TAG, "Closed proxy connection relay for $destIp:$destPort")
        }
    }
}
