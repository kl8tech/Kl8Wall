package cloud.kl8techgroup.kl8wall

import android.app.Application
import cloud.kl8techgroup.kl8wall.server.DeviceController
import cloud.kl8techgroup.kl8wall.server.KL8WallHttpServer
import cloud.kl8techgroup.kl8wall.server.MdnsAdvertiser
import cloud.kl8techgroup.kl8wall.server.WifiHelper
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import cloud.kl8techgroup.kl8wall.system.BrightnessController
import cloud.kl8techgroup.kl8wall.system.TtsController
import cloud.kl8techgroup.kl8wall.mqtt.MqttManager
import cloud.kl8techgroup.kl8wall.camera.CameraManager
import cloud.kl8techgroup.kl8wall.system.PresenceSensorManager
import cloud.kl8techgroup.kl8wall.bluetooth.BluetoothProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point for KL8Wall.
 *
 * Owns the lifecycle of app-wide services: settings repository, HTTP server,
 * mDNS advertiser, brightness control, and TTS. Acts as the manual DI
 * container — all components obtain their dependencies through this class.
 */
class KL8WallApplication : Application() {

    /** Encrypted settings persistence — initialized once, shared everywhere. */
    lateinit var settingsRepository: SettingsRepository
        private set

    /** Screen brightness control via Settings.System. */
    lateinit var brightnessController: BrightnessController
        private set

    /** Text-to-speech engine for the REST API. */
    lateinit var ttsController: TtsController
        private set

    private var httpServer: KL8WallHttpServer? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Activity-scoped device controller for HTTP API commands.
     * Set by [MainActivity] on resume, cleared on destroy.
     */
    @Volatile
    var deviceController: DeviceController? = null

    var mqttManager: MqttManager? = null
        private set
    var cameraManager: CameraManager? = null
        private set
    var presenceSensorManager: PresenceSensorManager? = null
        private set
    var bluetoothProxyServer: BluetoothProxyServer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        brightnessController = BrightnessController(this)
        ttsController = TtsController(this)
    }

    /**
     * Start the HTTP server and mDNS advertiser on the WiFi interface.
     *
     * No-ops if the server is already running or WiFi is not connected.
     * mDNS registration runs on a background thread since JmDNS probing
     * blocks for several seconds.
     */
    fun startServer() {
        if (httpServer != null) return

        val resolvedIp = WifiHelper.getWifiIpAddress(this)
        val ip = resolvedIp ?: if (BuildConfig.DEBUG) "127.0.0.1" else return
        val port = settingsRepository.httpPort.value

        // In debug builds, bind to null (0.0.0.0) so localhost / adb forward work
        val serverHostname = if (BuildConfig.DEBUG) null else ip

        httpServer = KL8WallHttpServer(
            hostname = serverHostname,
            port = port,
            bearerTokenProvider = { settingsRepository.httpBearerToken.value },
            deviceControllerProvider = { deviceController },
            settingsRepository = settingsRepository
        )
        httpServer?.startDaemon()

        if (BuildConfig.DEBUG) {
            android.util.Log.d("KL8Wall", "HTTP Server started on port $port. Bearer Token: ${settingsRepository.httpBearerToken.value}")
        }

        if (resolvedIp != null) {
            serverScope.launch {
                @Suppress("TooGenericExceptionCaught")
                try {
                    mdnsAdvertiser = MdnsAdvertiser(this@KL8WallApplication)
                    mdnsAdvertiser?.start(resolvedIp, port)
                } catch (_: Exception) {
                    // mDNS is non-critical — device is still reachable by IP
                }
            }
        }
    }

    /** Stop the HTTP server and mDNS advertiser. */
    fun stopServer() {
        httpServer?.stop()
        httpServer = null
        serverScope.launch {
            @Suppress("TooGenericExceptionCaught")
            try {
                mdnsAdvertiser?.stop()
            } catch (_: Exception) {
                // Best-effort cleanup
            }
            mdnsAdvertiser = null
        }
    }

    /**
     * Initialize and start all background managers (MQTT, Camera, Presence, BLE Proxy).
     */
    fun startServices(activity: androidx.activity.ComponentActivity, devController: DeviceController) {
        if (mqttManager != null) return

        val mqtt = MqttManager(this, settingsRepository, devController)
        mqttManager = mqtt
        mqtt.start()

        val camera = CameraManager(this, activity, settingsRepository, mqtt)
        cameraManager = camera
        camera.start()

        val presence = PresenceSensorManager(this, settingsRepository, mqtt, devController)
        presenceSensorManager = presence
        presence.start()

        // Link camera face detection to presence trigger
        camera.onFaceDetected = { detected ->
            if (detected) {
                presence.onFaceDetected()
            }
        }

        val ble = BluetoothProxyServer(this, settingsRepository)
        bluetoothProxyServer = ble
        ble.start()
    }

    /**
     * Stop all background managers.
     */
    fun stopServices() {
        cameraManager?.stop()
        cameraManager = null

        presenceSensorManager?.stop()
        presenceSensorManager = null

        bluetoothProxyServer?.stop()
        bluetoothProxyServer = null

        mqttManager?.stop()
        mqttManager = null
    }

    companion object {
        lateinit var instance: KL8WallApplication
            private set
    }
}
