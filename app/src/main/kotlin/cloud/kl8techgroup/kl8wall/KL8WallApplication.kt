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
import cloud.kl8techgroup.kl8wall.system.OtaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View

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

    /** Over-the-air update manager. */
    lateinit var otaManager: OtaManager
        private set

    private var httpServer: KL8WallHttpServer? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    var intercomManager: cloud.kl8techgroup.kl8wall.intercom.IntercomManager? = null
        private set

    var isAppInForeground: Boolean = false
        private set

    @Volatile
    var currentActivity: android.app.Activity? = null
        private set

    private var activeActivities = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        brightnessController = BrightnessController(this)
        ttsController = TtsController(this)
        otaManager = OtaManager(this)

        serverScope.launch {
            delay(5000)
            while (isActive) {
                otaManager.checkForUpdates(forceInstall = false)
                delay(12 * 60 * 60 * 1000) // check every 12 hours
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {
                currentActivity = activity
                if (activeActivities == 0) {
                    isAppInForeground = true
                    notifyForegroundState(true)
                }
                activeActivities++
            }
            override fun onActivityResumed(activity: android.app.Activity) {
                currentActivity = activity
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (currentActivity == activity) {
                    currentActivity = null
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                activeActivities--
                if (activeActivities == 0) {
                    isAppInForeground = false
                    notifyForegroundState(false)
                }
                if (currentActivity == activity) {
                    currentActivity = null
                }
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (currentActivity == activity) {
                    currentActivity = null
                }
            }
        })
    }

    private fun notifyForegroundState(inForeground: Boolean) {
        mqttManager?.publishAppForegroundState(inForeground)
        bluetoothProxyServer?.publishAppForegroundState(inForeground)
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

        val intercom = cloud.kl8techgroup.kl8wall.intercom.IntercomManager(this) { target, bytes ->
            mqttManager?.publishAudio(target, bytes)
        }
        intercomManager = intercom

        val mqtt = MqttManager(
            context = this,
            settingsRepository = settingsRepository,
            deviceController = devController,
            onIncomingAudio = { bytes ->
                intercom.handleIncomingAudio(bytes)
            },
            onIntercomCommand = { cmd ->
                handleIntercomCommand(cmd)
            }
        )
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

    private fun handleIntercomCommand(cmd: String) {
        val cleanCmd = cmd.trim()
        val lowerCmd = cleanCmd.lowercase()
        if (lowerCmd.startsWith("start:")) {
            val target = cleanCmd.substringAfter("start:").trim()
            if (target.isNotEmpty()) {
                intercomManager?.startRecording(target)
            }
        } else if (lowerCmd == "stop") {
            intercomManager?.stopRecording()
            intercomManager?.stopPlayback()
        }
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

        intercomManager?.stopRecording()
        intercomManager?.stopPlayback()
        intercomManager = null

        mqttManager?.stop()
        mqttManager = null
    }

    /**
     * Captures a screenshot of the current foreground activity, compressing it as a JPEG.
     * Returns null if no activity is in the foreground or the capture fails.
     */
    suspend fun captureCurrentScreen(): ByteArray? {
        val activity = currentActivity ?: return null
        return suspendCancellableCoroutine { continuation ->
            try {
                val window = activity.window
                val view = window.decorView
                if (view.width <= 0 || view.height <= 0) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                
                PixelCopy.request(window, bitmap, { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        try {
                            val outStream = java.io.ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outStream)
                            val bytes = outStream.toByteArray()
                            bitmap.recycle()
                            continuation.resume(bytes)
                        } catch (e: Exception) {
                            continuation.resume(null)
                        }
                    } else {
                        // Fallback: draw view directly to Canvas
                        try {
                            val fallbackBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(fallbackBitmap)
                            view.draw(canvas)
                            val outStream = java.io.ByteArrayOutputStream()
                            fallbackBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outStream)
                            val bytes = outStream.toByteArray()
                            fallbackBitmap.recycle()
                            continuation.resume(bytes)
                        } catch (e: Exception) {
                            continuation.resume(null)
                        }
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }
    }

    companion object {
        lateinit var instance: KL8WallApplication
            private set
    }
}
