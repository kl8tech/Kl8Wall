package cloud.kl8techgroup.kl8wall.mqtt

import android.content.Context
import android.content.Intent
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
    private val deviceController: DeviceController,
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
        Log.i(TAG, "MQTT config changed: enabled=${config.enabled}, broker=${config.broker}, port=${config.port}")
        currentConfig = config
        disconnectSync()
        if (!config.enabled || config.broker.isBlank()) return
        connectAsync(config)
    }

    private fun disconnectSync() {
        statusPublisherJob?.cancel()
        statusPublisherJob = null
        otaCollectorsJob?.cancel()
        otaCollectorsJob = null
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
                if (config.username.isNotBlank()) userName = config.username
                if (config.password.isNotBlank()) password = config.password.toCharArray()
            }

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "MQTT connected to $serverURI")
                    publishDiscovery(config.deviceName)
                    subscribeToCommands(config.deviceName)
                    startPeriodicStatusPublisher(config.deviceName)
                    startOtaStateCollectors(config.deviceName)
                }
                override fun connectionLost(cause: Throwable?) {}
                override fun messageArrived(topic: String, message: MqttMessage) {
                    if (topic.endsWith("/audio_rx")) {
                        onIncomingAudio(message.payload)
                    } else if (topic.endsWith("/intercom/cmd")) {
                        val payload = message.payload.toString(Charsets.UTF_8)
                        onIntercomCommand(payload)
                    } else {
                        val payload = message.payload.toString(Charsets.UTF_8)
                        handleCommand(topic, payload, config.deviceName)
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "MQTT connection failed", exception)
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

        // Update Entity Discovery
        val updateEntityConfig = JSONObject().apply {
            put("name", "App Update")
            put("unique_id", "kl8wall_${deviceName}_update")
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
            "kl8wall/$deviceName/audio_rx"
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
                    "kl8wall/$deviceName/reload/cmd" -> deviceController.reload()
                    "kl8wall/$deviceName/snapshot/cmd" -> onSnapshotTrigger?.invoke()
                    "kl8wall/$deviceName/tts/cmd" -> deviceController.speak(payload)
                    "kl8wall/$deviceName/settings/cmd" -> deviceController.openSettings()
                    "kl8wall/$deviceName/reboot/cmd" -> deviceController.rebootApp()
                    "kl8wall/$deviceName/screen_timeout/cmd", "kl8wall/$deviceName/presence_timeout/cmd" -> {
                        val secs = payload.toIntOrNull()
                        if (secs != null) {
                            deviceController.setScreenTimeoutSeconds(secs)
                            publishString("kl8wall/$deviceName/screen_timeout/state", secs.toString(), true)
                            publishString("kl8wall/$deviceName/presence_timeout/state", secs.toString(), true)
                        }
                    }
                    "kl8wall/$deviceName/tts_volume/cmd" -> {
                        val vol = payload.toIntOrNull()
                        if (vol != null) {
                            deviceController.setTtsVolume(vol)
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
                        val app = context as? cloud.kl8techgroup.kl8wall.KL8WallApplication
                        if (payload.uppercase() == "ON") {
                            app?.cameraManager?.isStreamingEnabled = true
                            publishCameraStreamingState(deviceName, true)
                        } else if (payload.uppercase() == "OFF") {
                            app?.cameraManager?.isStreamingEnabled = false
                            publishCameraStreamingState(deviceName, false)
                        }
                    }
                    "kl8wall/$deviceName/screenshot/cmd" -> {
                        val app = context as? cloud.kl8techgroup.kl8wall.KL8WallApplication
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
                        val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication
                        app?.serverScope?.launch {
                            app.otaManager.checkForUpdates(false)
                        }
                    }
                    "kl8wall/$deviceName/trigger_update/cmd" -> {
                        val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication
                        app?.serverScope?.launch {
                            app.otaManager.triggerUpdate()
                        }
                    }
                    "kl8wall/$deviceName/update/cmd" -> {
                        if (payload.lowercase() == "install") {
                            val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication
                            app?.serverScope?.launch {
                                app.otaManager.triggerUpdate()
                            }
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
            while (isActive) {
                publishDeviceStates(deviceName)
                delay(30000)
            }
        }
    }

    private suspend fun publishDeviceStates(deviceName: String) {
        withContext(Dispatchers.Main) {
            val screenOn = deviceController.isScreenOn()
            val brightness = deviceController.getBrightness()
            
            withContext(Dispatchers.IO) {
                publishScreenState(deviceName, screenOn)
                publishBrightnessState(deviceName, brightness)
                
                publishString("kl8wall/$deviceName/url/state", deviceController.getCurrentUrl(), false)
                publishString("kl8wall/$deviceName/battery_level/state", deviceController.getBatteryLevel().toString(), false)
                publishString("kl8wall/$deviceName/battery_temp/state", deviceController.getBatteryTemp().toString(), false)
                publishString("kl8wall/$deviceName/battery_state/state", deviceController.getBatteryState(), false)
                publishString("kl8wall/$deviceName/wifi_rssi/state", deviceController.getWifiRssi().toString(), false)
                publishString("kl8wall/$deviceName/wifi_ssid/state", deviceController.getWifiSsid(), false)
                publishString("kl8wall/$deviceName/ram_usage/state", deviceController.getRamUsagePercent().toString(), false)
                publishString("kl8wall/$deviceName/storage_free/state", deviceController.getStorageFreeGb().toString(), false)
                publishString("kl8wall/$deviceName/uptime/state", deviceController.getUptimeSeconds().toString(), false)
                publishString("kl8wall/$deviceName/ip_address/state", deviceController.getIpAddress(), false)
                publishString("kl8wall/$deviceName/app_version/state", deviceController.getAppVersion(), false)
                
                publishString("kl8wall/$deviceName/ambient_light/state", deviceController.getAmbientLight().toString(), false)
                publishString("kl8wall/$deviceName/proximity/state", deviceController.getProximity().toString(), false)
                publishString("kl8wall/$deviceName/pressure/state", deviceController.getPressure().toString(), false)
                publishString("kl8wall/$deviceName/ambient_temp/state", deviceController.getAmbientTemp().toString(), false)
                publishString("kl8wall/$deviceName/humidity/state", deviceController.getHumidity().toString(), false)

                val app = context as? cloud.kl8techgroup.kl8wall.KL8WallApplication
                val inForeground = app?.isAppInForeground ?: false
                publishString("kl8wall/$deviceName/app_foreground/state", if (inForeground) "ON" else "OFF", false)
                
                val isStreaming = app?.cameraManager?.isStreamingEnabled ?: false
                publishString("kl8wall/$deviceName/camera_streaming/state", if (isStreaming) "ON" else "OFF", false)

                val bleProxy = app?.bluetoothProxyServer
                val bleCount = bleProxy?.getNearbyDevicesCount() ?: 0
                val bleList = bleProxy?.getNearbyDevicesList() ?: ""
                publishString("kl8wall/$deviceName/bluetooth_devices_count/state", bleCount.toString(), false)
                publishString("kl8wall/$deviceName/bluetooth_devices_list/state", bleList, false)

                publishString("kl8wall/$deviceName/screen_timeout/state", deviceController.getScreenTimeoutSeconds().toString(), false)
                publishString("kl8wall/$deviceName/presence_timeout/state", deviceController.getScreenTimeoutSeconds().toString(), false)
                publishString("kl8wall/$deviceName/tts_volume/state", deviceController.getTtsVolume().toString(), false)
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

    private fun publishString(topic: String, message: String, retain: Boolean) {
        publishBytes(topic, message.toByteArray(Charsets.UTF_8), retain)
    }

    private fun publishBytes(topic: String, payload: ByteArray, retain: Boolean) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
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
}
