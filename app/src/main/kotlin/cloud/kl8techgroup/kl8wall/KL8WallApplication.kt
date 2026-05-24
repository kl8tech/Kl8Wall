package cloud.kl8techgroup.kl8wall

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import cloud.kl8techgroup.kl8wall.cast.CastManager
import cloud.kl8techgroup.kl8wall.kiosk.PasscodeLockManager
import cloud.kl8techgroup.kl8wall.system.KL8WallService
import cloud.kl8techgroup.kl8wall.system.BatterySaverManager
import android.content.Intent
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
    var peerManager: cloud.kl8techgroup.kl8wall.peer.PeerManager? = null
        private set
    var intercomManager: cloud.kl8techgroup.kl8wall.intercom.IntercomManager? = null
        private set
    var castManager: CastManager? = null
        private set
    var passcodeLockManager: PasscodeLockManager? = null
        private set
    var batterySaverManager: BatterySaverManager? = null
        private set
    var voiceAssistantManager: cloud.kl8techgroup.kl8wall.voice.VoiceAssistantManager? = null
        private set
    private var voiceAssistantJob: kotlinx.coroutines.Job? = null

    var isAppInForeground: Boolean = false
        private set

    @Volatile
    var currentActivity: android.app.Activity? = null
        private set

    private var activeActivities = 0
    private var boundIpAddress: String? = null
    private val lastReconnectTime = java.util.concurrent.atomic.AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        brightnessController = BrightnessController(this)
        ttsController = TtsController(this)
        otaManager = OtaManager(this)
        passcodeLockManager = PasscodeLockManager(settingsRepository)
        peerManager = cloud.kl8techgroup.kl8wall.peer.PeerManager(this, settingsRepository)

        registerNetworkCallback()

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
        val resolvedIp = WifiHelper.getWifiIpAddress(this)
        val ip = resolvedIp ?: if (BuildConfig.DEBUG) "127.0.0.1" else return

        if (httpServer != null) {
            if (boundIpAddress == ip) {
                return
            } else {
                stopServer()
            }
        }

        boundIpAddress = ip
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

        serverScope.launch {
            @Suppress("TooGenericExceptionCaught")
            try {
                mdnsAdvertiser = MdnsAdvertiser(this@KL8WallApplication)
                mdnsAdvertiser?.start(ip, port)
            } catch (_: Exception) {
                // mDNS is non-critical — device is still reachable by IP
            }
        }
    }

    /** Stop the HTTP server and mDNS advertiser. */
    fun stopServer() {
        httpServer?.stop()
        httpServer = null
        boundIpAddress = null
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

    private fun registerNetworkCallback() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()

            connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastReconnectTime.get() < 15000) {
                        android.util.Log.d("KL8Wall", "Network callback onAvailable ignored (debounced)")
                        return
                    }
                    lastReconnectTime.set(now)

                    android.util.Log.i("KL8Wall", "Network connection available, starting/restarting network services")
                    serverScope.launch {
                        var ip: String? = null
                        for (i in 1..10) {
                            ip = WifiHelper.getWifiIpAddress(this@KL8WallApplication)
                            if (ip != null) break
                            android.util.Log.d("KL8Wall", "IP address not available yet, retrying in 500ms... (attempt $i/10)")
                            delay(500)
                        }

                        closeSharedJmdns()
                        if (!settingsRepository.isFirstRun.value) {
                            startServer()
                        }
                        mqttManager?.reconnect()
                        bluetoothProxyServer?.restart()
                        peerManager?.restart()

                        otaManager.checkForUpdates(forceInstall = false)

                        deviceController?.let { devCtrl ->
                            android.util.Log.i("KL8Wall", "Network connection available, reloading configured start URL...")
                            delay(1000)
                            devCtrl.navigate(settingsRepository.startUrl.value)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("KL8Wall", "Failed to register network callback", e)
        }
    }

    /**
     * Initialize and start all background managers (MQTT, Camera, Presence, BLE Proxy).
     */
    fun startServices(activity: androidx.activity.ComponentActivity, devController: DeviceController) {
        if (mqttManager != null) {
            mqttManager?.updateDeviceController(devController)
            presenceSensorManager?.updateDeviceController(devController)
            cameraManager?.updateLifecycleOwner(activity)
            return
        }

        val serviceIntent = Intent(this, KL8WallService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)

        castManager = CastManager(this)

        val batterySaver = BatterySaverManager(this, settingsRepository)
        batterySaverManager = batterySaver
        batterySaver.start()

        val intercom = cloud.kl8techgroup.kl8wall.intercom.IntercomManager(this) { target, bytes ->
            mqttManager?.publishAudio(target, bytes)
        }
        intercom.onStateChanged = { isRecording ->
            mqttManager?.publishIntercomActiveState(isRecording)
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

        peerManager?.start()

        val voiceAssistant = cloud.kl8techgroup.kl8wall.voice.VoiceAssistantManager(this, settingsRepository)
        voiceAssistantManager = voiceAssistant
        voiceAssistantJob = serverScope.launch(Dispatchers.Main) {
            settingsRepository.voiceAssistantEnabled.collect { enabled ->
                if (enabled) {
                    voiceAssistant.start()
                } else {
                    voiceAssistant.stop()
                }
            }
        }
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
        castManager = null
        val serviceIntent = Intent(this, KL8WallService::class.java)
        stopService(serviceIntent)

        voiceAssistantJob?.cancel()
        voiceAssistantJob = null
        voiceAssistantManager?.stop()
        voiceAssistantManager = null

        batterySaverManager?.stop()
        batterySaverManager = null

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

        peerManager?.stop()
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

    fun rebootApplication() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                123456,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.setExact(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                pendingIntent
            )
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(0)
    }

    fun launchMainActivity(url: String? = null, openSettings: Boolean = false) {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "KL8Wall::WakeFromBackground"
            )
            wakeLock.acquire(3000L)

            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (url != null) {
                    putExtra("start_url", url)
                }
                if (openSettings) {
                    putExtra("open_settings", true)
                }
            }
            if (intent != null) {
                startActivity(intent)
                android.util.Log.i("KL8WallApplication", "Woke screen and launched MainActivity")
            }
        } catch (e: Exception) {
            android.util.Log.e("KL8WallApplication", "Failed to launch MainActivity from background", e)
        }
    }

    fun updateMdnsMqttState() {
        mdnsAdvertiser?.updateMqttState()
    }

    private var sharedJmdns: javax.jmdns.JmDNS? = null
    private var sharedJmdnsIp: String? = null
    private var sharedMulticastLock: android.net.wifi.WifiManager.MulticastLock? = null

    @Synchronized
    fun getOrCreateJmdns(ipAddress: String): javax.jmdns.JmDNS? {
        val current = sharedJmdns
        if (current != null && sharedJmdnsIp == ipAddress) {
            return current
        }
        if (current != null) {
            android.util.Log.i("KL8WallApplication", "JmDNS IP changed from $sharedJmdnsIp to $ipAddress. Recreating.")
            closeSharedJmdns()
        }

        try {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            sharedMulticastLock = wifiManager.createMulticastLock("kl8wall_shared_mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            
            val address = java.net.InetAddress.getByName(ipAddress)
            val j = javax.jmdns.JmDNS.create(address, "kl8wall-shared")
            sharedJmdns = j
            sharedJmdnsIp = ipAddress
            android.util.Log.i("KL8WallApplication", "Created shared JmDNS instance on $ipAddress")
            return j
        } catch (e: Exception) {
            android.util.Log.e("KL8WallApplication", "Failed to create shared JmDNS instance", e)
            closeSharedJmdns()
            return null
        }
    }

    @Synchronized
    fun closeSharedJmdns() {
        try {
            sharedJmdns?.close()
        } catch (_: Exception) {}
        sharedJmdns = null
        sharedJmdnsIp = null
        try {
            sharedMulticastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        sharedMulticastLock = null
    }

    data class PendingSyncApproval(
        val requestId: String,
        val requesterName: String,
        val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>
    )

    val pendingSyncApproval = kotlinx.coroutines.flow.MutableStateFlow<PendingSyncApproval?>(null)

    companion object {
        lateinit var instance: KL8WallApplication
            private set
    }
}
