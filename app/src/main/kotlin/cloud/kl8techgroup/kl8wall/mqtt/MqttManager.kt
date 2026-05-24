package cloud.kl8techgroup.kl8wall.mqtt

import android.content.Context
import android.content.Intent
import android.util.Log
import cloud.kl8techgroup.kl8wall.KL8WallApplication
import cloud.kl8techgroup.kl8wall.server.DeviceController
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import cloud.kl8techgroup.kl8wall.system.SslUtil
import cloud.kl8techgroup.kl8wall.cast.CastManager
import cloud.kl8techgroup.kl8wall.kiosk.PasscodeLockManager
import cloud.kl8techgroup.kl8wall.system.PresenceSensorManager

enum class MqttConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class MqttManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    @field:Volatile private var deviceController: DeviceController?,
    private val onIncomingAudio: (ByteArray) -> Unit,
    private val onIntercomCommand: (String) -> Unit
) {
    companion object {
        private const val TAG = "MqttManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mqttClient: MqttAsyncClient? = null
    
    var onSnapshotTrigger: (() -> Unit)? = null
    private var currentConfig: MqttConfig? = null
    private var statusPublisherJob: Job? = null
    private var otaCollectorsJob: Job? = null
    private var castAndLockCollectorsJob: Job? = null
    private var connectionJob: Job? = null

    @Volatile
    private var brokerIpOverride: String? = null

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun isConnected(): Boolean {
        return mqttClient?.isConnected ?: false
    }

    fun setBrokerIpOverride(ip: String) {
        if (brokerIpOverride != ip) {
            Log.i(TAG, "Setting MQTT broker IP override: $ip")
            brokerIpOverride = ip
            reconnect()
        }
    }

    fun subscribeExternally(topic: String) {
        val client = mqttClient
        if (client != null && client.isConnected) {
            try {
                client.subscribe(topic, 1)
                Log.i(TAG, "Subscribed externally to $topic")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to subscribe to $topic", e)
            }
        }
    }

    fun unsubscribeExternally(topic: String) {
        val client = mqttClient
        if (client != null && client.isConnected) {
            try {
                client.unsubscribe(topic)
                Log.i(TAG, "Unsubscribed externally from $topic")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unsubscribe from $topic", e)
            }
        }
    }

    fun publishExternally(topic: String, payload: String, retain: Boolean = false) {
        publishString(topic, payload, retain)
    }

    fun publishExternally(topic: String, payload: ByteArray, retain: Boolean = false) {
        publishBytes(topic, payload, retain)
    }

    fun handleRelayedCommand(topic: String, payload: ByteArray) {
        scope.launch(Dispatchers.Main) {
            val deviceName = settingsRepository.deviceName.value
            if (topic.endsWith("/audio_rx")) {
                onIncomingAudio(payload)
            } else {
                val payloadStr = payload.toString(Charsets.UTF_8)
                handleCommand(topic, payloadStr, deviceName)
            }
        }
    }

    private fun isLocalHostOrIp(host: String): Boolean {
        val trimmed = host.trim().lowercase()
        if (trimmed == "localhost" || trimmed == "127.0.0.1" || trimmed.endsWith(".local")) {
            return true
        }
        if (trimmed.startsWith("10.") || trimmed.startsWith("192.168.")) {
            return true
        }
        if (trimmed.startsWith("172.")) {
            val parts = trimmed.split(".")
            if (parts.size >= 2) {
                val secondOctet = parts[1].toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) {
                    return true
                }
            }
        }
        return false
    }

    data class MqttConfig(
        val enabled: Boolean,
        val broker: String,
        val port: Int,
        val username: String,
        val password: String,
        val deviceName: String
    )

    fun start() {
        Log.d(TAG, "Starting MqttManager...")
        scope.launch {
            combine<Any, MqttConfig>(
                settingsRepository.mqttEnabled,
                settingsRepository.mqttBroker,
                settingsRepository.mqttPort,
                settingsRepository.mqttUsername,
                settingsRepository.mqttPassword,
                settingsRepository.deviceName
            ) { array ->
                MqttConfig(
                    enabled = array[0] as Boolean,
                    broker = array[1] as String,
                    port = array[2] as Int,
                    username = array[3] as String,
                    password = array[4] as String,
                    deviceName = array[5] as String
                )
            }.collectLatest { config ->
                handleConfigChange(config)
            }
        }

        scope.launch {
            // Wait for PresenceSensorManager to be initialized
            var presence: PresenceSensorManager? = null
            while (presence == null && isActive) {
                presence = (context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication)?.presenceSensorManager
                if (presence == null) {
                    delay(500)
                }
            }
            val presenceManager = presence ?: return@launch
            
            combine(
                settingsRepository.sensorIntervalSeconds,
                presenceManager.isLowPowerMode
            ) { interval, lowPower ->
                Pair(interval, lowPower)
            }.collectLatest { (interval, lowPower) ->
                val config = currentConfig ?: return@collectLatest
                if (config.enabled && config.broker.isNotBlank()) {
                    startPeriodicStatusPublisher(config.deviceName)
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping MqttManager...")
        scope.cancel()
        disconnectSync()
    }

    fun updateDeviceController(newController: DeviceController) {
        Log.i(TAG, "Updating MqttManager deviceController reference")
        this.deviceController = newController
    }

    private suspend fun handleConfigChange(config: MqttConfig) {
        if (config == currentConfig) return
        Log.i(TAG, "MQTT config changed: enabled=${config.enabled}, broker=${config.broker}, port=${config.port}")
        currentConfig = config
        disconnectSync()
        if (!config.enabled || config.broker.isBlank()) {
            _connectionState.value = MqttConnectionState.DISCONNECTED
            _lastError.value = if (!config.enabled) "MQTT is disabled" else "Broker address is blank"
            return
        }
        publishDiscovery(config.deviceName)
        startPeriodicStatusPublisher(config.deviceName)
        startOtaStateCollectors(config.deviceName)
        startCastAndLockCollectors(config.deviceName)
        connectAsync(config)
    }

    private fun disconnectSync() {
        connectionJob?.cancel()
        connectionJob = null
        statusPublisherJob?.cancel()
        statusPublisherJob = null
        otaCollectorsJob?.cancel()
        otaCollectorsJob = null
        castAndLockCollectorsJob?.cancel()
        castAndLockCollectorsJob = null
        _connectionState.value = MqttConnectionState.DISCONNECTED
        updateMdnsAdvertiser()
        val client = mqttClient
        if (client != null) {
            try {
                if (client.isConnected) client.disconnectForcibly(1000)
                client.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error while disconnecting", e)
            } finally {
                mqttClient = null
            }
        }
    }

    private fun connectAsync(config: MqttConfig) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            var delayMs = 2000L
            while (isActive) {
                try {
                    var brokerAddress = config.broker.trim()
                    val overrideIp = brokerIpOverride
                    if (overrideIp != null && overrideIp.isNotEmpty() && !isLocalHostOrIp(brokerAddress)) {
                        Log.i(TAG, "Overriding broker address with direct IP: $overrideIp (original: $brokerAddress)")
                        brokerAddress = overrideIp
                    }
                    val serverUri = when {
                        brokerAddress.startsWith("tcp://") ||
                        brokerAddress.startsWith("ssl://") ||
                        brokerAddress.startsWith("ws://") ||
                        brokerAddress.startsWith("wss://") -> {
                            val schemeEnd = brokerAddress.indexOf("://") + 3
                            if (brokerAddress.indexOf(":", schemeEnd) != -1) {
                                brokerAddress
                            } else {
                                "$brokerAddress:${config.port}"
                            }
                        }
                        else -> {
                            val scheme = if (config.port == 8883 || config.port == 8884) "ssl" else "tcp"
                            "$scheme://$brokerAddress:${config.port}"
                        }
                    }
                    val clientId = "kl8wall_${config.deviceName}_${UUID.randomUUID().toString().take(6)}"
                    val client = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
                    mqttClient = client

                    _connectionState.value = MqttConnectionState.CONNECTING
                    val options = MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = 10
                        keepAliveInterval = 30
                        isAutomaticReconnect = true
                        if (config.username.isNotBlank()) userName = config.username
                        if (config.password.isNotBlank()) password = config.password.toCharArray()

                        if (serverUri.startsWith("ssl://") || serverUri.startsWith("wss://")) {
                            try {
                                socketFactory = SslUtil.tlsSocketFactory
                                val host = serverUri.substringAfter("://").substringBefore(":")
                                sslHostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, session ->
                                    val expectedHost = config.broker.trim()
                                    if (isLocalHostOrIp(host) || isLocalHostOrIp(hostname) || hostname == expectedHost) {
                                        true
                                    } else {
                                        javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to initialize custom TLSSocketFactory or HostnameVerifier for SSL", e)
                            }
                        }
                    }

                    client.setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                            Log.i(TAG, "MQTT connected to $serverURI")
                            _connectionState.value = MqttConnectionState.CONNECTED
                            _lastError.value = null
                            updateMdnsAdvertiser()
                            publishDiscovery(config.deviceName)
                            subscribeToCommands(config.deviceName)
                            startPeriodicStatusPublisher(config.deviceName)
                            startOtaStateCollectors(config.deviceName)
                            startCastAndLockCollectors(config.deviceName)
                        }
                        override fun connectionLost(cause: Throwable?) {
                            Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                            _connectionState.value = MqttConnectionState.DISCONNECTED
                            _lastError.value = cause?.message ?: "Connection lost"
                            updateMdnsAdvertiser()
                        }
                        override fun messageArrived(topic: String, message: MqttMessage) {
                            val currentDevice = config.deviceName
                            val isLocal = topic.startsWith("kl8wall/$currentDevice/")
                            if (isLocal) {
                                if (topic.endsWith("/audio_rx")) {
                                    onIncomingAudio(message.payload)
                                } else if (topic.endsWith("/intercom/cmd")) {
                                    val payload = message.payload.toString(Charsets.UTF_8)
                                    onIntercomCommand(payload)
                                } else {
                                    val payload = message.payload.toString(Charsets.UTF_8)
                                    handleCommand(topic, payload, currentDevice)
                                }
                            } else {
                                val payloadBytes = message.payload
                                val base64Payload = android.util.Base64.encodeToString(payloadBytes, android.util.Base64.NO_WRAP)
                                KL8WallApplication.instance.peerManager?.handleRelayedMqttMessage(topic, base64Payload, isBase64 = true)
                            }
                        }
                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })

                    val deferred = CompletableDeferred<Boolean>()
                    client.connect(options, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            deferred.complete(true)
                        }
                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            deferred.completeExceptionally(exception ?: Exception("Unknown connection error"))
                        }
                    })

                    deferred.await()
                    Log.i(TAG, "MQTT Connection established successfully")
                    _connectionState.value = MqttConnectionState.CONNECTED
                    _lastError.value = null
                    break
                } catch (e: Exception) {
                    val errMsg = e.message ?: e.toString()
                    Log.e(TAG, "MQTT connection attempt failed, retrying in ${delayMs / 1000}s", e)
                    _connectionState.value = MqttConnectionState.DISCONNECTED
                    _lastError.value = errMsg
                    
                    if (brokerIpOverride != null) {
                        Log.i(TAG, "Clearing broker IP override due to connection failure")
                        brokerIpOverride = null
                    }

                    try {
                        mqttClient?.close()
                    } catch (_: Exception) {}
                    mqttClient = null
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(30000L)
                }
            }
        }
    }

    private fun publishDiscovery(deviceName: String) {
        val deviceJson = JSONObject().apply {
            put("identifiers", JSONArray().apply { put("kl8wall_$deviceName") })
            put("name", deviceName)
            put("manufacturer", "kl8techgroup")
            put("model", "KL8Wall")
            put("sw_version", "1.0.0")
        }

        // Light
        val screenConfig = JSONObject().apply {
            put("name", "Screen")
            put("unique_id", "kl8wall_${deviceName}_screen")
            put("command_topic", "kl8wall/$deviceName/screen/cmd")
            put("state_topic", "kl8wall/$deviceName/screen/state")
            put("brightness_command_topic", "kl8wall/$deviceName/brightness/cmd")
            put("brightness_state_topic", "kl8wall/$deviceName/brightness/state")
            put("brightness_scale", 100)
            put("supported_color_modes", JSONArray().apply { put("brightness") })
            put("color_mode", "brightness")
            put("device", deviceJson)
        }
        publishString("homeassistant/light/kl8wall_$deviceName/screen/config", screenConfig.toString(), retain = true)

        // Presence
        val presenceConfig = JSONObject().apply {
            put("name", "Presence")
            put("unique_id", "kl8wall_${deviceName}_presence")
            put("state_topic", "kl8wall/$deviceName/presence/state")
            put("device_class", "motion")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("device", deviceJson)
        }
        publishString("homeassistant/binary_sensor/kl8wall_$deviceName/presence/config", presenceConfig.toString(), retain = true)

        // Camera
        val cameraConfig = JSONObject().apply {
            put("name", "Camera")
            put("unique_id", "kl8wall_${deviceName}_camera")
            put("topic", "kl8wall/$deviceName/camera/image")
            put("device", deviceJson)
        }
        publishString("homeassistant/camera/kl8wall_$deviceName/camera/config", cameraConfig.toString(), retain = true)

        // Screenshot Camera
        val screenshotConfig = JSONObject().apply {
            put("name", "Screenshot")
            put("unique_id", "kl8wall_${deviceName}_screenshot")
            put("topic", "kl8wall/$deviceName/screenshot/image")
            put("device", deviceJson)
        }
        publishString("homeassistant/camera/kl8wall_$deviceName/screenshot/config", screenshotConfig.toString(), retain = true)

        // Buttons
        fun pubBtn(id: String, name: String, devClass: String = "") {
            val cfg = JSONObject().apply {
                put("name", name)
                put("unique_id", "kl8wall_${deviceName}_$id")
                put("command_topic", "kl8wall/$deviceName/$id/cmd")
                if (devClass.isNotEmpty()) put("device_class", devClass)
                put("device", deviceJson)
            }
            publishString("homeassistant/button/kl8wall_$deviceName/$id/config", cfg.toString(), retain = true)
        }
        pubBtn("reload", "Reload Dashboard", "restart")
        pubBtn("snapshot", "Trigger Photo")
        pubBtn("screenshot", "Trigger Screenshot")
        pubBtn("settings", "Open Settings")
        pubBtn("close_settings", "Close Settings")
        pubBtn("reboot", "Reboot App", "restart")

        // Numbers
        fun pubNum(id: String, name: String, min: Int, max: Int, unit: String) {
            val cfg = JSONObject().apply {
                put("name", name)
                put("unique_id", "kl8wall_${deviceName}_$id")
                put("command_topic", "kl8wall/$deviceName/$id/cmd")
                put("state_topic", "kl8wall/$deviceName/$id/state")
                put("min", min)
                put("max", max)
                if (unit.isNotEmpty()) put("unit_of_measurement", unit)
                put("device", deviceJson)
            }
            publishString("homeassistant/number/kl8wall_$deviceName/$id/config", cfg.toString(), retain = true)
        }
        pubNum("screen_timeout", "Screen Timeout", 10, 3600, "s")
        pubNum("presence_timeout", "Presence Timeout", 10, 3600, "s")
        pubNum("tts_volume", "TTS Volume", 0, 100, "%")

        // Text (TTS)
        val ttsConfig = JSONObject().apply {
            put("name", "TTS")
            put("unique_id", "kl8wall_${deviceName}_tts")
            put("command_topic", "kl8wall/$deviceName/tts/cmd")
            put("mode", "text")
            put("device", deviceJson)
        }
        publishString("homeassistant/text/kl8wall_$deviceName/tts/config", ttsConfig.toString(), retain = true)

        // Sensors
        fun pubSens(id: String, name: String, unit: String, devClass: String, stateClass: String) {
            val cfg = JSONObject().apply {
                put("name", name)
                put("unique_id", "kl8wall_${deviceName}_$id")
                put("state_topic", "kl8wall/$deviceName/$id/state")
                if (unit.isNotEmpty()) put("unit_of_measurement", unit)
                if (devClass.isNotEmpty()) put("device_class", devClass)
                if (stateClass.isNotEmpty()) put("state_class", stateClass)
                put("device", deviceJson)
            }
            publishString("homeassistant/sensor/kl8wall_$deviceName/$id/config", cfg.toString(), retain = true)
        }
        pubSens("battery_level", "Battery Level", "%", "battery", "measurement")
        pubSens("battery_temp", "Battery Temp", "°C", "temperature", "measurement")
        pubSens("wifi_rssi", "WiFi RSSI", "dBm", "signal_strength", "measurement")
        pubSens("ram_usage", "RAM Usage", "%", "", "measurement")
        pubSens("storage_free", "Storage Free", "GB", "data_size", "measurement")
        pubSens("uptime", "Uptime", "s", "duration", "total_increasing")
        pubSens("battery_state", "Battery State", "", "", "")
        pubSens("wifi_ssid", "WiFi SSID", "", "", "")
        pubSens("ip_address", "IP Address", "", "", "")
        pubSens("app_version", "App Version", "", "", "")
        pubSens("url", "Current URL", "", "", "")

        pubSens("ambient_light", "Ambient Light", "lx", "illuminance", "measurement")
        pubSens("proximity", "Proximity", "cm", "", "measurement")
        pubSens("pressure", "Pressure", "hPa", "pressure", "measurement")
        pubSens("ambient_temp", "Ambient Temperature", "°C", "temperature", "measurement")
        pubSens("humidity", "Humidity", "%", "humidity", "measurement")
        pubSens("bluetooth_devices_count", "Bluetooth Devices Count", "", "", "measurement")
        pubSens("bluetooth_devices_list", "Bluetooth Devices List", "", "", "")

        fun pubSwitch(id: String, name: String) {
            val cfg = JSONObject().apply {
                put("name", name)
                put("unique_id", "kl8wall_${deviceName}_$id")
                put("command_topic", "kl8wall/$deviceName/$id/cmd")
                put("state_topic", "kl8wall/$deviceName/$id/state")
                put("payload_on", "ON")
                put("payload_off", "OFF")
                put("device", deviceJson)
            }
            publishString("homeassistant/switch/kl8wall_$deviceName/$id/config", cfg.toString(), retain = true)
        }
        pubSwitch("app_foreground", "App Foreground")
        pubSwitch("camera_streaming", "Camera Streaming")
        pubSwitch("intercom_active", "Intercom Active")
        pubSwitch("charger", "Charger")

        // Intercom Target Text Entity
        val intercomTargetConfig = JSONObject().apply {
            put("name", "Intercom Target")
            put("unique_id", "kl8wall_${deviceName}_intercom_target")
            put("command_topic", "kl8wall/$deviceName/intercom_target/cmd")
            put("state_topic", "kl8wall/$deviceName/intercom_target/state")
            put("mode", "text")
            put("device", deviceJson)
        }
        publishString("homeassistant/text/kl8wall_$deviceName/intercom_target/config", intercomTargetConfig.toString(), retain = true)

        // Update Entity Discovery
        val updateEntityConfig = JSONObject().apply {
            put("name", "App Update")
            put("unique_id", "kl8wall_${deviceName}_update")
            put("device_class", "firmware")
            put("title", "KL8Wall")
            put("state_topic", "kl8wall/$deviceName/update/state")
            put("command_topic", "kl8wall/$deviceName/update/cmd")
            put("payload_install", "install")
            put("device", deviceJson)
        }
        publishString("homeassistant/update/kl8wall_$deviceName/update/config", updateEntityConfig.toString(), retain = true)

        // Binary Sensors for Update State
        fun pubBinarySens(id: String, name: String, devClass: String = "") {
            val cfg = JSONObject().apply {
                put("name", name)
                put("unique_id", "kl8wall_${deviceName}_$id")
                put("state_topic", "kl8wall/$deviceName/$id/state")
                if (devClass.isNotEmpty()) put("device_class", devClass)
                put("payload_on", "ON")
                put("payload_off", "OFF")
                put("device", deviceJson)
            }
            publishString("homeassistant/binary_sensor/kl8wall_$deviceName/$id/config", cfg.toString(), retain = true)
        }
        pubBinarySens("update_available", "Update Available", "update")
        pubBinarySens("updating", "App Updating", "running")

        pubBtn("check_update", "Check Update")
        pubBtn("trigger_update", "Install Update")

        // Cast URL Command (Text)
        val castUrlConfig = JSONObject().apply {
            put("name", "Cast URL")
            put("unique_id", "kl8wall_${deviceName}_cast_url")
            put("command_topic", "kl8wall/$deviceName/cast_url/cmd")
            put("state_topic", "kl8wall/$deviceName/cast_url/state")
            put("mode", "text")
            put("device", deviceJson)
        }
        publishString("homeassistant/text/kl8wall_$deviceName/cast_url/config", castUrlConfig.toString(), retain = true)

        // Cast Volume Command/State (Number)
        val castVolumeConfig = JSONObject().apply {
            put("name", "Cast Volume")
            put("unique_id", "kl8wall_${deviceName}_cast_volume")
            put("command_topic", "kl8wall/$deviceName/cast_volume/cmd")
            put("state_topic", "kl8wall/$deviceName/cast_volume/state")
            put("min", 0)
            put("max", 100)
            put("unit_of_measurement", "%")
            put("device", deviceJson)
        }
        publishString("homeassistant/number/kl8wall_$deviceName/cast_volume/config", castVolumeConfig.toString(), retain = true)

        // Cast Sensors
        pubSens("cast_playback_state", "Cast Playback State", "", "", "")
        pubSens("cast_position", "Cast Position", "s", "duration", "measurement")
        pubSens("cast_duration", "Cast Duration", "s", "duration", "measurement")
        pubSens("cast_current_url", "Cast Current URL", "", "", "")

        // Cast Buttons
        pubBtn("cast_play", "Cast Play")
        pubBtn("cast_pause", "Cast Pause")
        pubBtn("cast_stop", "Cast Stop")

        // Passcode Lock Entity
        val lockConfig = JSONObject().apply {
            put("name", "Passcode Lock")
            put("unique_id", "kl8wall_${deviceName}_lock")
            put("command_topic", "kl8wall/$deviceName/lock/cmd")
            put("state_topic", "kl8wall/$deviceName/lock/state")
            put("device", deviceJson)
        }
        publishString("homeassistant/lock/kl8wall_$deviceName/lock/config", lockConfig.toString(), retain = true)
    }

    private fun subscribeToCommands(deviceName: String) {
        val client = mqttClient ?: return
        val topics = arrayOf(
            "kl8wall/$deviceName/screen/cmd",
            "kl8wall/$deviceName/brightness/cmd",
            "kl8wall/$deviceName/reload/cmd",
            "kl8wall/$deviceName/snapshot/cmd",
            "kl8wall/$deviceName/tts/cmd",
            "kl8wall/$deviceName/settings/cmd",
            "kl8wall/$deviceName/close_settings/cmd",
            "kl8wall/$deviceName/reboot/cmd",
            "kl8wall/$deviceName/screen_timeout/cmd",
            "kl8wall/$deviceName/presence_timeout/cmd",
            "kl8wall/$deviceName/tts_volume/cmd",
            "kl8wall/$deviceName/app_foreground/cmd",
            "kl8wall/$deviceName/camera_streaming/cmd",
            "kl8wall/$deviceName/screenshot/cmd",
            "kl8wall/$deviceName/check_update/cmd",
            "kl8wall/$deviceName/trigger_update/cmd",
            "kl8wall/$deviceName/update/cmd",
            "kl8wall/$deviceName/intercom/cmd",
            "kl8wall/$deviceName/audio_rx",
            "kl8wall/$deviceName/cast_url/cmd",
            "kl8wall/$deviceName/cast_volume/cmd",
            "kl8wall/$deviceName/cast_play/cmd",
            "kl8wall/$deviceName/cast_pause/cmd",
            "kl8wall/$deviceName/cast_stop/cmd",
            "kl8wall/$deviceName/lock/cmd",
            "kl8wall/$deviceName/intercom_active/cmd",
            "kl8wall/$deviceName/intercom_target/cmd",
            "kl8wall/$deviceName/charger/cmd",
            "kl8wall/$deviceName/call/invite",
            "kl8wall/$deviceName/call/accept",
            "kl8wall/$deviceName/call/decline",
            "kl8wall/$deviceName/call/hangup"
        )
        val qos = IntArray(topics.size) { 1 }

        try {
            client.subscribe(topics, qos, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to command topics", e)
        }
    }

    private fun handleCommand(topic: String, payload: String, deviceName: String) {
        scope.launch(Dispatchers.Main) {
            try {
                val app = context.applicationContext as? KL8WallApplication
                when (topic) {
                    "kl8wall/$deviceName/screen/cmd" -> {
                        val controller = deviceController
                        if (payload.uppercase() == "ON") {
                            if (controller != null) {
                                controller.screenOn()
                            } else {
                                app?.launchMainActivity()
                            }
                            publishScreenState(deviceName, true)
                        } else if (payload.uppercase() == "OFF") {
                            controller?.screenOff()
                            publishScreenState(deviceName, false)
                        }
                    }
                    "kl8wall/$deviceName/brightness/cmd" -> {
                        val percent = payload.toIntOrNull()
                        if (percent != null && percent in 0..100) {
                            app?.brightnessController?.setBrightness(percent)
                            publishBrightnessState(deviceName, percent)
                        }
                    }
                    "kl8wall/$deviceName/reload/cmd" -> {
                        val controller = deviceController
                        if (controller != null) {
                            controller.reload()
                        } else {
                            app?.launchMainActivity()
                        }
                    }
                    "kl8wall/$deviceName/snapshot/cmd" -> onSnapshotTrigger?.invoke()
                    "kl8wall/$deviceName/tts/cmd" -> {
                        app?.ttsController?.speak(payload)
                    }
                    "kl8wall/$deviceName/settings/cmd" -> {
                        val controller = deviceController
                        val upperPayload = payload.trim().uppercase()
                        if (upperPayload == "OFF" || upperPayload == "CLOSE" || upperPayload == "0") {
                            controller?.closeSettings()
                        } else {
                            if (controller != null) {
                                controller.openSettings()
                            } else {
                                app?.launchMainActivity(openSettings = true)
                            }
                        }
                    }
                    "kl8wall/$deviceName/close_settings/cmd" -> {
                        deviceController?.closeSettings()
                    }
                    "kl8wall/$deviceName/reboot/cmd" -> {
                        val controller = deviceController
                        if (controller != null) {
                            controller.rebootApp()
                        } else {
                            app?.rebootApplication()
                        }
                    }
                    "kl8wall/$deviceName/screen_timeout/cmd", "kl8wall/$deviceName/presence_timeout/cmd" -> {
                        val secs = payload.toIntOrNull()
                        if (secs != null) {
                            app?.settingsRepository?.setPresenceTimeoutSeconds(secs)
                            publishString("kl8wall/$deviceName/screen_timeout/state", secs.toString(), true)
                            publishString("kl8wall/$deviceName/presence_timeout/state", secs.toString(), true)
                        }
                    }
                    "kl8wall/$deviceName/tts_volume/cmd" -> {
                        val vol = payload.toIntOrNull()
                        if (vol != null) {
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            val target = (vol.coerceIn(0, 100) * max / 100f).toInt()
                            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                            publishString("kl8wall/$deviceName/tts_volume/state", vol.toString(), true)
                        }
                    }
                    "kl8wall/$deviceName/app_foreground/cmd" -> {
                        if (payload.uppercase() == "ON") {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                                publishAppForegroundState(true)
                            }
                        } else if (payload.uppercase() == "OFF") {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(homeIntent)
                            publishAppForegroundState(false)
                        }
                    }
                    "kl8wall/$deviceName/camera_streaming/cmd" -> {
                        if (payload.uppercase() == "ON") {
                            app?.cameraManager?.isStreamingEnabled = true
                            publishCameraStreamingState(deviceName, true)
                        } else if (payload.uppercase() == "OFF") {
                            app?.cameraManager?.isStreamingEnabled = false
                            publishCameraStreamingState(deviceName, false)
                        }
                    }
                    "kl8wall/$deviceName/screenshot/cmd" -> {
                        if (app != null) {
                            scope.launch {
                                val bytes = app.captureCurrentScreen()
                                if (bytes != null) {
                                    publishBytes("kl8wall/$deviceName/screenshot/image", bytes, retain = false)
                                }
                            }
                        }
                    }
                    "kl8wall/$deviceName/check_update/cmd" -> {
                        app?.serverScope?.launch {
                            app.otaManager.checkForUpdates(false)
                        }
                    }
                    "kl8wall/$deviceName/trigger_update/cmd" -> {
                        app?.serverScope?.launch {
                            app.otaManager.triggerUpdate()
                        }
                    }
                    "kl8wall/$deviceName/update/cmd" -> {
                        if (payload.lowercase() == "install") {
                            app?.serverScope?.launch {
                                app.otaManager.triggerUpdate()
                            }
                        }
                    }
                    "kl8wall/$deviceName/cast_url/cmd" -> {
                        app?.castManager?.cast(payload)
                    }
                    "kl8wall/$deviceName/cast_volume/cmd" -> {
                        val vol = payload.toIntOrNull()
                        if (vol != null) {
                            app?.castManager?.setVolume(vol)
                        }
                    }
                    "kl8wall/$deviceName/cast_play/cmd" -> {
                        app?.castManager?.play()
                    }
                    "kl8wall/$deviceName/cast_pause/cmd" -> {
                        app?.castManager?.pause()
                    }
                    "kl8wall/$deviceName/cast_stop/cmd" -> {
                        app?.castManager?.stop()
                    }
                    "kl8wall/$deviceName/lock/cmd" -> {
                        if (payload.uppercase() == "LOCK") {
                            app?.passcodeLockManager?.lock()
                        } else if (payload.uppercase() == "UNLOCK") {
                            app?.passcodeLockManager?.unlock()
                        }
                    }
                    "kl8wall/$deviceName/intercom_active/cmd" -> {
                        if (payload.uppercase() == "ON") {
                            val target = settingsRepository.intercomTarget.value
                            app?.intercomManager?.startRecording(target)
                        } else if (payload.uppercase() == "OFF") {
                            app?.intercomManager?.stopRecording()
                        }
                    }
                    "kl8wall/$deviceName/intercom_target/cmd" -> {
                        settingsRepository.setIntercomTarget(payload)
                        publishIntercomTargetState(payload)
                    }
                    "kl8wall/$deviceName/charger/cmd" -> {
                        if (payload.uppercase() == "ON") {
                            app?.batterySaverManager?.setChargerStateOverride(true)
                        } else if (payload.uppercase() == "OFF") {
                            app?.batterySaverManager?.setChargerStateOverride(false)
                        }
                    }
                    "kl8wall/$deviceName/call/invite" -> {
                        val json = try { JSONObject(payload) } catch (_: Exception) { null }
                        val caller = json?.optString("caller") ?: ""
                        if (caller.isNotEmpty()) {
                            app?.intercomManager?.receiveInvite(caller)
                        }
                    }
                    "kl8wall/$deviceName/call/accept" -> {
                        val json = try { JSONObject(payload) } catch (_: Exception) { null }
                        val responder = json?.optString("responder") ?: ""
                        if (responder.isNotEmpty()) {
                            app?.intercomManager?.receiveAccept(responder)
                        }
                    }
                    "kl8wall/$deviceName/call/decline" -> {
                        val json = try { JSONObject(payload) } catch (_: Exception) { null }
                        val responder = json?.optString("responder") ?: ""
                        if (responder.isNotEmpty()) {
                            app?.intercomManager?.receiveDecline(responder)
                        }
                    }
                    "kl8wall/$deviceName/call/hangup" -> {
                        val json = try { JSONObject(payload) } catch (_: Exception) { null }
                        val sender = json?.optString("sender") ?: ""
                        if (sender.isNotEmpty()) {
                            app?.intercomManager?.receiveHangup(sender)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing command on $topic: $payload", e)
            }
        }
    }

    private fun startPeriodicStatusPublisher(deviceName: String) {
        statusPublisherJob?.cancel()
        statusPublisherJob = scope.launch {
            val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication
            val presence = app?.presenceSensorManager
            while (isActive) {
                publishDeviceStates(deviceName)
                val isLowPower = presence?.isLowPowerMode?.value ?: false
                val intervalSeconds = if (isLowPower) 300 else settingsRepository.sensorIntervalSeconds.value
                delay(intervalSeconds * 1000L)
            }
        }
    }

    private suspend fun publishDeviceStates(deviceName: String) {
        val controller = deviceController
        val app = context.applicationContext as? KL8WallApplication
        val presence = app?.presenceSensorManager

        val screenOn: Boolean
        val brightness: Int
        val currentUrl: String
        val batteryLevel: Float
        val batteryTemp: Float
        val batteryState: String
        val wifiRssi: Int
        val wifiSsid: String
        val ramUsage: Float
        val storageFree: Float
        val uptime: Long
        val ipAddress: String
        val appVersion: String
        val ambientLight: Float
        val proximity: Float
        val pressure: Float
        val ambientTemp: Float
        val humidity: Float
        val screenTimeout: Int
        val ttsVolume: Int

        if (controller != null) {
            val stats = withContext(Dispatchers.Main) {
                android.os.Bundle().apply {
                    putBoolean("screenOn", controller.isScreenOn())
                    putInt("brightness", controller.getBrightness())
                    putString("currentUrl", controller.getCurrentUrl())
                    putFloat("batteryLevel", controller.getBatteryLevel())
                    putFloat("batteryTemp", controller.getBatteryTemp())
                    putString("batteryState", controller.getBatteryState())
                    putInt("wifiRssi", controller.getWifiRssi())
                    putString("wifiSsid", controller.getWifiSsid())
                    putFloat("ramUsage", controller.getRamUsagePercent())
                    putFloat("storageFree", controller.getStorageFreeGb())
                    putLong("uptime", controller.getUptimeSeconds())
                    putString("ipAddress", controller.getIpAddress())
                    putString("appVersion", controller.getAppVersion())
                    putFloat("ambientLight", controller.getAmbientLight())
                    putFloat("proximity", controller.getProximity())
                    putFloat("pressure", controller.getPressure())
                    putFloat("ambientTemp", controller.getAmbientTemp())
                    putFloat("humidity", controller.getHumidity())
                    putInt("screenTimeout", controller.getScreenTimeoutSeconds())
                    putInt("ttsVolume", controller.getTtsVolume())
                }
            }
            screenOn = stats.getBoolean("screenOn")
            brightness = stats.getInt("brightness")
            currentUrl = stats.getString("currentUrl") ?: ""
            batteryLevel = stats.getFloat("batteryLevel")
            batteryTemp = stats.getFloat("batteryTemp")
            batteryState = stats.getString("batteryState") ?: "unknown"
            wifiRssi = stats.getInt("wifiRssi")
            wifiSsid = stats.getString("wifiSsid") ?: "unknown"
            ramUsage = stats.getFloat("ramUsage")
            storageFree = stats.getFloat("storageFree")
            uptime = stats.getLong("uptime")
            ipAddress = stats.getString("ipAddress") ?: "0.0.0.0"
            appVersion = stats.getString("appVersion") ?: "1.0.0"
            ambientLight = stats.getFloat("ambientLight")
            proximity = stats.getFloat("proximity")
            pressure = stats.getFloat("pressure")
            ambientTemp = stats.getFloat("ambientTemp")
            humidity = stats.getFloat("humidity")
            screenTimeout = stats.getInt("screenTimeout")
            ttsVolume = stats.getInt("ttsVolume")
        } else {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            screenOn = powerManager.isInteractive
            
            val rawBright = android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
            brightness = (rawBright * 100 + 127) / 255
            
            currentUrl = app?.settingsRepository?.startUrl?.value ?: ""
            
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
            batteryTemp = 0.0f
            batteryState = "unknown"
            
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val connectionInfo = wifiManager.connectionInfo
            wifiRssi = connectionInfo.rssi
            @Suppress("DEPRECATION")
            val ssid = connectionInfo.ssid
            wifiSsid = if (ssid != null && ssid != android.net.wifi.WifiManager.UNKNOWN_SSID) {
                ssid.replace("\"", "")
            } else {
                "unknown"
            }
            
            val mi = android.app.ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.getMemoryInfo(mi)
            val total = mi.totalMem.toDouble()
            val avail = mi.availMem.toDouble()
            ramUsage = if (total > 0) (((total - avail) / total) * 100).toFloat() else 0f
            
            storageFree = try {
                val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
                (bytesAvailable / (1024f * 1024f * 1024f))
            } catch (e: Exception) {
                0f
            }
            
            uptime = android.os.SystemClock.elapsedRealtime() / 1000
            
            var ip = "0.0.0.0"
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                if (interfaces != null) {
                    for (intf in java.util.Collections.list(interfaces)) {
                        if (!intf.isUp || intf.isLoopback) continue
                        val addrs = intf.inetAddresses
                        for (addr in java.util.Collections.list(addrs)) {
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                ip = addr.hostAddress ?: "0.0.0.0"
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            ipAddress = ip
            
            appVersion = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                pInfo.versionName ?: "1.0.0"
            } catch (e: Exception) {
                "1.0.0"
            }
            
            ambientLight = presence?.latestLux ?: 0.0f
            proximity = presence?.latestProximity ?: 0.0f
            pressure = presence?.latestPressure ?: 0.0f
            ambientTemp = presence?.latestAmbientTemp ?: 0.0f
            humidity = presence?.latestHumidity ?: 0.0f
            screenTimeout = app?.settingsRepository?.presenceTimeoutSeconds?.value ?: 30
            
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val currentVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            ttsVolume = if (maxVol > 0) (currentVol * 100 / maxVol) else 0
        }

        withContext(Dispatchers.IO) {
            publishScreenState(deviceName, screenOn)
            publishBrightnessState(deviceName, brightness)
            
            publishString("kl8wall/$deviceName/url/state", currentUrl, false)
            publishString("kl8wall/$deviceName/battery_level/state", batteryLevel.toString(), false)
            publishString("kl8wall/$deviceName/battery_temp/state", batteryTemp.toString(), false)
            publishString("kl8wall/$deviceName/battery_state/state", batteryState, false)
            publishString("kl8wall/$deviceName/wifi_rssi/state", wifiRssi.toString(), false)
            publishString("kl8wall/$deviceName/wifi_ssid/state", wifiSsid, false)
            publishString("kl8wall/$deviceName/ram_usage/state", ramUsage.toString(), false)
            publishString("kl8wall/$deviceName/storage_free/state", storageFree.toString(), false)
            publishString("kl8wall/$deviceName/uptime/state", uptime.toString(), false)
            publishString("kl8wall/$deviceName/ip_address/state", ipAddress, false)
            publishString("kl8wall/$deviceName/app_version/state", appVersion, false)
            
            publishString("kl8wall/$deviceName/ambient_light/state", ambientLight.toString(), false)
            publishString("kl8wall/$deviceName/proximity/state", proximity.toString(), false)
            publishString("kl8wall/$deviceName/pressure/state", pressure.toString(), false)
            publishString("kl8wall/$deviceName/ambient_temp/state", ambientTemp.toString(), false)
            publishString("kl8wall/$deviceName/humidity/state", humidity.toString(), false)

            val inForeground = app?.isAppInForeground ?: false
            publishString("kl8wall/$deviceName/app_foreground/state", if (inForeground) "ON" else "OFF", false)
            
            val isStreaming = app?.cameraManager?.isStreamingEnabled ?: false
            publishString("kl8wall/$deviceName/camera_streaming/state", if (isStreaming) "ON" else "OFF", false)

            val bleProxy = app?.bluetoothProxyServer
            val bleCount = bleProxy?.getNearbyDevicesCount() ?: 0
            val bleList = bleProxy?.getNearbyDevicesList() ?: ""
            publishString("kl8wall/$deviceName/bluetooth_devices_count/state", bleCount.toString(), false)
            publishString("kl8wall/$deviceName/bluetooth_devices_list/state", bleList, false)

            publishString("kl8wall/$deviceName/screen_timeout/state", screenTimeout.toString(), false)
            publishString("kl8wall/$deviceName/presence_timeout/state", screenTimeout.toString(), false)
            publishString("kl8wall/$deviceName/tts_volume/state", ttsVolume.toString(), false)

            val intercomActive = app?.intercomManager?.isRecordingActive ?: false
            publishString("kl8wall/$deviceName/intercom_active/state", if (intercomActive) "ON" else "OFF", false)
            publishString("kl8wall/$deviceName/intercom_target/state", settingsRepository.intercomTarget.value, false)
            
            val chargerOn = app?.batterySaverManager?.chargerState?.value ?: false
            publishString("kl8wall/$deviceName/charger/state", if (chargerOn) "ON" else "OFF", false)
        }
    }

    fun publishScreenState(deviceName: String, isOn: Boolean) {
        publishString("kl8wall/$deviceName/screen/state", if (isOn) "ON" else "OFF", retain = true)
    }

    fun publishBrightnessState(deviceName: String, percent: Int) {
        publishString("kl8wall/$deviceName/brightness/state", percent.toString(), retain = true)
    }

    fun publishPresenceState(isPresent: Boolean) {
        val config = currentConfig ?: return
        publishString("kl8wall/${config.deviceName}/presence/state", if (isPresent) "ON" else "OFF", retain = true)
    }

    fun publishAppForegroundState(inForeground: Boolean) {
        val config = currentConfig ?: return
        publishString("kl8wall/${config.deviceName}/app_foreground/state", if (inForeground) "ON" else "OFF", retain = true)
    }

    fun publishCameraStreamingState(deviceName: String, isStreaming: Boolean) {
        publishString("kl8wall/$deviceName/camera_streaming/state", if (isStreaming) "ON" else "OFF", retain = true)
    }

    fun publishCameraImage(imageBytes: ByteArray) {
        val config = currentConfig ?: return
        publishBytes("kl8wall/${config.deviceName}/camera/image", imageBytes, retain = false)
    }

    fun publishAudio(targetDevice: String, audioBytes: ByteArray) {
        publishBytes("kl8wall/$targetDevice/audio_rx", audioBytes, retain = false)
    }

    fun publishIntercomActiveState(isRecording: Boolean) {
        val config = currentConfig ?: return
        publishString("kl8wall/${config.deviceName}/intercom_active/state", if (isRecording) "ON" else "OFF", retain = true)
    }

    fun publishIntercomTargetState(target: String) {
        val config = currentConfig ?: return
        publishString("kl8wall/${config.deviceName}/intercom_target/state", target, retain = true)
    }

    fun publishChargerState(isOn: Boolean) {
        val config = currentConfig ?: return
        publishString("kl8wall/${config.deviceName}/charger/state", if (isOn) "ON" else "OFF", retain = true)
    }

    private fun publishString(topic: String, message: String, retain: Boolean) {
        publishBytes(topic, message.toByteArray(Charsets.UTF_8), retain)
    }

    private fun publishBytes(topic: String, payload: ByteArray, retain: Boolean) {
        val client = mqttClient
        if (client == null || !client.isConnected) {
            val app = context.applicationContext as? KL8WallApplication
            val peerManager = app?.peerManager
            if (peerManager != null) {
                val base64Payload = android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
                val relayed = peerManager.sendRelayMessage(topic, base64Payload, isBase64 = true)
                if (relayed) {
                    Log.d(TAG, "MQTT disconnected; relayed packet to peer: $topic")
                } else {
                    Log.d(TAG, "MQTT disconnected; failed to find online peer to relay packet: $topic")
                }
            }
            return
        }
        try {
            val mqttMessage = MqttMessage(payload).apply {
                qos = 1
                isRetained = retain
            }
            client.publish(topic, mqttMessage, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing to topic $topic", e)
        }
    }

    private fun startCastAndLockCollectors(deviceName: String) {
        castAndLockCollectorsJob?.cancel()
        val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication ?: return
        val cast = app.castManager
        val lock = app.passcodeLockManager

        castAndLockCollectorsJob = scope.launch {
            if (cast != null) {
                launch {
                    cast.castUrl.collect { url ->
                        publishString("kl8wall/$deviceName/cast_url/state", url ?: "", true)
                        publishString("kl8wall/$deviceName/cast_current_url/state", url ?: "", true)
                    }
                }
                launch {
                    cast.volume.collect { vol ->
                        publishString("kl8wall/$deviceName/cast_volume/state", vol.toString(), true)
                    }
                }
                launch {
                    cast.playbackState.collect { state ->
                        publishString("kl8wall/$deviceName/cast_playback_state/state", state, true)
                    }
                }
                launch {
                    cast.position.collect { pos ->
                        publishString("kl8wall/$deviceName/cast_position/state", pos.toString(), true)
                    }
                }
                launch {
                    cast.duration.collect { dur ->
                        publishString("kl8wall/$deviceName/cast_duration/state", dur.toString(), true)
                    }
                }
            }
            if (lock != null) {
                launch {
                    lock.isLocked.collect { isLocked ->
                        publishString("kl8wall/$deviceName/lock/state", if (isLocked) "LOCKED" else "UNLOCKED", true)
                    }
                }
            }
            launch {
                settingsRepository.intercomTarget.collect { target ->
                    publishIntercomTargetState(target)
                }
            }
            val batterySaver = app.batterySaverManager
            if (batterySaver != null) {
                launch {
                    batterySaver.chargerState.collect { chargerState ->
                        publishChargerState(chargerState)
                    }
                }
            }
        }
    }

    private fun startOtaStateCollectors(deviceName: String) {
        otaCollectorsJob?.cancel()
        val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication ?: return
        val ota = app.otaManager

        otaCollectorsJob = scope.launch {
            launch {
                ota.updateAvailable.collect {
                    publishOtaStates(deviceName)
                }
            }
            launch {
                ota.isUpdating.collect {
                    publishOtaStates(deviceName)
                }
            }
            launch {
                ota.latestVersion.collect {
                    publishOtaStates(deviceName)
                }
            }
            launch {
                ota.updateProgress.collect {
                    publishOtaStates(deviceName)
                }
            }
        }
    }

    private fun publishOtaStates(deviceName: String) {
        val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication ?: return
        val ota = app.otaManager
        val available = ota.updateAvailable.value
        val updating = ota.isUpdating.value
        val progress = ota.updateProgress.value
        val latestVer = ota.latestVersion.value
        val currentVer = ota.currentVersionName

        publishString("kl8wall/$deviceName/update_available/state", if (available) "ON" else "OFF", true)
        publishString("kl8wall/$deviceName/updating/state", if (updating) "ON" else "OFF", true)

        val updateStateJson = JSONObject().apply {
            put("installed_version", currentVer)
            put("latest_version", if (available && latestVer.isNotEmpty()) latestVer else currentVer)
            put("title", "KL8Wall")
            put("release_url", "https://github.com/kl8tech/Kl8Wall/releases/latest")
            put("in_progress", updating)
            put("update_percentage", progress ?: JSONObject.NULL)
        }
        publishString("kl8wall/$deviceName/update/state", updateStateJson.toString(), true)
    }

    fun reconnect() {
        val config = currentConfig ?: return
        if (!config.enabled || config.broker.isBlank()) return
        Log.i(TAG, "Forcing MQTT reconnect...")
        disconnectSync()
        publishDiscovery(config.deviceName)
        startPeriodicStatusPublisher(config.deviceName)
        startOtaStateCollectors(config.deviceName)
        startCastAndLockCollectors(config.deviceName)
        connectAsync(config)
    }

    private fun updateMdnsAdvertiser() {
        val app = context.applicationContext as? KL8WallApplication
        app?.updateMdnsMqttState()
    }
}
