package cloud.kl8techgroup.kl8wall.mqtt

import android.content.Context
import android.util.Log
import cloud.kl8techgroup.kl8wall.server.DeviceController
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MqttManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val deviceController: DeviceController
) {
    companion object {
        private const val TAG = "MqttManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mqttClient: MqttAsyncClient? = null
    
    // Callbacks
    var onSnapshotTrigger: (() -> Unit)? = null

    // Local cached config to track changes
    private var currentConfig: MqttConfig? = null
    
    // Job to monitor states and publish periodically
    private var statusPublisherJob: Job? = null

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
    }

    fun stop() {
        Log.d(TAG, "Stopping MqttManager...")
        scope.cancel()
        disconnectSync()
    }

    private suspend fun handleConfigChange(config: MqttConfig) {
        if (config == currentConfig) return
        Log.i(TAG, "MQTT config changed: enabled=${config.enabled}, broker=${config.broker}, port=${config.port}, deviceName=${config.deviceName}")
        currentConfig = config

        disconnectSync()

        if (!config.enabled || config.broker.isBlank()) {
            Log.i(TAG, "MQTT is disabled or broker is empty. Not connecting.")
            return
        }

        connectAsync(config)
    }

    private fun disconnectSync() {
        statusPublisherJob?.cancel()
        statusPublisherJob = null
        
        val client = mqttClient
        if (client != null) {
            try {
                if (client.isConnected) {
                    Log.d(TAG, "Disconnecting from MQTT broker...")
                    client.disconnectForcibly(1000)
                }
                client.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error while disconnecting", e)
            } finally {
                mqttClient = null
            }
        }
    }

    private fun connectAsync(config: MqttConfig) {
        val serverUri = "tcp://${config.broker}:${config.port}"
        val clientId = "kl8wall_${config.deviceName}_${UUID.randomUUID().toString().take(6)}"

        try {
            val client = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
            mqttClient = client

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                isAutomaticReconnect = true
                if (config.username.isNotBlank()) {
                    userName = config.username
                }
                if (config.password.isNotBlank()) {
                    password = config.password.toCharArray()
                }
            }

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "MQTT connected successfully (reconnect=$reconnect) to $serverURI")
                    publishDiscovery(config.deviceName)
                    subscribeToCommands(config.deviceName)
                    startPeriodicStatusPublisher(config.deviceName)
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost", cause)
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = message.payload.toString(Charsets.UTF_8)
                    Log.d(TAG, "MQTT message arrived on topic $topic: $payload")
                    handleCommand(topic, payload, config.deviceName)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // No-op
                }
            })

            Log.i(TAG, "Connecting to MQTT broker at $serverUri...")
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "MQTT connect initiation onSuccess")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "MQTT connection initiation failed", exception)
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MQTT client", e)
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

        // 1. Screen (Light)
        val screenConfig = JSONObject().apply {
            put("name", "Screen")
            put("unique_id", "kl8wall_${deviceName}_screen")
            put("command_topic", "kl8wall/$deviceName/screen/cmd")
            put("state_topic", "kl8wall/$deviceName/screen/state")
            put("brightness_command_topic", "kl8wall/$deviceName/brightness/cmd")
            put("brightness_state_topic", "kl8wall/$deviceName/brightness/state")
            put("brightness_scale", 100)
            put("device", deviceJson)
        }
        publishString("homeassistant/light/kl8wall_$deviceName/screen/config", screenConfig.toString(), retain = true)

        // 2. Presence (Binary Sensor)
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

        // 3. Camera
        val cameraConfig = JSONObject().apply {
            put("name", "Camera")
            put("unique_id", "kl8wall_${deviceName}_camera")
            put("topic", "kl8wall/$deviceName/camera/image")
            put("device", deviceJson)
        }
        publishString("homeassistant/camera/kl8wall_$deviceName/camera/config", cameraConfig.toString(), retain = true)

        // 4. Current URL (Sensor)
        val urlConfig = JSONObject().apply {
            put("name", "Current URL")
            put("unique_id", "kl8wall_${deviceName}_url")
            put("state_topic", "kl8wall/$deviceName/url/state")
            put("device", deviceJson)
        }
        publishString("homeassistant/sensor/kl8wall_$deviceName/url/config", urlConfig.toString(), retain = true)

        // 5. Reload (Button)
        val reloadConfig = JSONObject().apply {
            put("name", "Reload Dashboard")
            put("unique_id", "kl8wall_${deviceName}_reload")
            put("command_topic", "kl8wall/$deviceName/reload/cmd")
            put("device", deviceJson)
        }
        publishString("homeassistant/button/kl8wall_$deviceName/reload/config", reloadConfig.toString(), retain = true)

        // 6. Snapshot (Button)
        val snapshotConfig = JSONObject().apply {
            put("name", "Trigger Photo")
            put("unique_id", "kl8wall_${deviceName}_snapshot")
            put("command_topic", "kl8wall/$deviceName/snapshot/cmd")
            put("device", deviceJson)
        }
        publishString("homeassistant/button/kl8wall_$deviceName/snapshot/config", snapshotConfig.toString(), retain = true)

        // 7. TTS (Text)
        val ttsConfig = JSONObject().apply {
            put("name", "TTS")
            put("unique_id", "kl8wall_${deviceName}_tts")
            put("command_topic", "kl8wall/$deviceName/tts/cmd")
            put("mode", "text")
            put("device", deviceJson)
        }
        publishString("homeassistant/text/kl8wall_$deviceName/tts/config", ttsConfig.toString(), retain = true)

        // 8. Open Settings (Button)
        val openSettingsConfig = JSONObject().apply {
            put("name", "Open Settings")
            put("unique_id", "kl8wall_${deviceName}_open_settings")
            put("command_topic", "kl8wall/$deviceName/settings/cmd")
            put("device", deviceJson)
        }
        publishString("homeassistant/button/kl8wall_$deviceName/open_settings/config", openSettingsConfig.toString(), retain = true)

        Log.i(TAG, "MQTT Discovery payloads published for device $deviceName")
    }

    private fun subscribeToCommands(deviceName: String) {
        val client = mqttClient ?: return
        val topics = arrayOf(
            "kl8wall/$deviceName/screen/cmd",
            "kl8wall/$deviceName/brightness/cmd",
            "kl8wall/$deviceName/reload/cmd",
            "kl8wall/$deviceName/snapshot/cmd",
            "kl8wall/$deviceName/tts/cmd",
            "kl8wall/$deviceName/settings/cmd"
        )
        val qos = IntArray(topics.size) { 1 }

        try {
            client.subscribe(topics, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Successfully subscribed to command topics")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to command topics", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to command topics", e)
        }
    }

    private fun handleCommand(topic: String, payload: String, deviceName: String) {
        scope.launch(Dispatchers.Main) {
            try {
                when (topic) {
                    "kl8wall/$deviceName/screen/cmd" -> {
                        if (payload.uppercase() == "ON") {
                            deviceController.screenOn()
                            publishScreenState(deviceName, true)
                        } else if (payload.uppercase() == "OFF") {
                            deviceController.screenOff()
                            publishScreenState(deviceName, false)
                        }
                    }
                    "kl8wall/$deviceName/brightness/cmd" -> {
                        val percent = payload.toIntOrNull()
                        if (percent != null && percent in 0..100) {
                            deviceController.setBrightness(percent)
                            publishBrightnessState(deviceName, percent)
                        }
                    }
                    "kl8wall/$deviceName/reload/cmd" -> {
                        deviceController.reload()
                    }
                    "kl8wall/$deviceName/snapshot/cmd" -> {
                        Log.i(TAG, "On-demand snapshot requested via MQTT")
                        onSnapshotTrigger?.invoke()
                    }
                    "kl8wall/$deviceName/tts/cmd" -> {
                        deviceController.speak(payload)
                    }
                    "kl8wall/$deviceName/settings/cmd" -> {
                        deviceController.openSettings()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing command on topic $topic with payload $payload", e)
            }
        }
    }

    private fun startPeriodicStatusPublisher(deviceName: String) {
        statusPublisherJob?.cancel()
        statusPublisherJob = scope.launch {
            while (isActive) {
                publishDeviceStates(deviceName)
                delay(30000) // Publish every 30 seconds
            }
        }
    }

    private suspend fun publishDeviceStates(deviceName: String) {
        withContext(Dispatchers.Main) {
            val screenOn = deviceController.isScreenOn()
            val brightness = deviceController.getBrightness()
            val currentUrl = deviceController.getCurrentUrl()

            withContext(Dispatchers.IO) {
                publishScreenState(deviceName, screenOn)
                publishBrightnessState(deviceName, brightness)
                publishString("kl8wall/$deviceName/url/state", currentUrl, retain = false)
            }
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

    fun publishCameraImage(imageBytes: ByteArray) {
        val config = currentConfig ?: return
        publishBytes("kl8wall/${config.deviceName}/camera/image", imageBytes, retain = false)
    }

    private fun publishString(topic: String, message: String, retain: Boolean) {
        publishBytes(topic, message.toByteArray(Charsets.UTF_8), retain)
    }

    private fun publishBytes(topic: String, payload: ByteArray, retain: Boolean) {
        val client = mqttClient
        if (client == null || !client.isConnected) return

        try {
            val mqttMessage = MqttMessage(payload).apply {
                qos = 1
                isRetained = retain
            }
            client.publish(topic, mqttMessage, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    // Success
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to publish to topic $topic", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing to topic $topic", e)
        }
    }
}
