package cloud.kl8techgroup.kl8wall

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import cloud.kl8techgroup.kl8wall.kiosk.HotCornerDetector
import cloud.kl8techgroup.kl8wall.kiosk.KioskLockManager
import cloud.kl8techgroup.kl8wall.kiosk.PinGate
import cloud.kl8techgroup.kl8wall.server.DeviceController
import cloud.kl8techgroup.kl8wall.settings.FirstRunSetup
import cloud.kl8techgroup.kl8wall.settings.SettingsSheet
import cloud.kl8techgroup.kl8wall.settings.SettingsViewModel
import cloud.kl8techgroup.kl8wall.system.ScreenController
import cloud.kl8techgroup.kl8wall.ui.theme.KL8WallTheme
import cloud.kl8techgroup.kl8wall.webview.AuthInterceptor
import cloud.kl8techgroup.kl8wall.webview.KioskWebView
import cloud.kl8techgroup.kl8wall.webview.WebViewClientConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.wifi.WifiManager
import android.media.AudioManager
import android.app.ActivityManager
import android.os.StatFs
import android.os.Environment
import android.os.SystemClock
import java.util.Locale
import java.net.NetworkInterface
import java.net.InetAddress
import java.net.Inet4Address
import java.util.Collections
import android.net.Uri
import android.provider.Settings

/**
 * Single activity hosting the kiosk WebView and Compose settings overlay.
 *
 * On first launch, shows the setup wizard. After setup, displays the
 * HA dashboard in a hardened WebView with the HTTP API server running.
 * Settings are accessible via the hot corner long-press, optionally
 * gated by a PIN.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var kioskLockManager: KioskLockManager
    private lateinit var screenController: ScreenController
    private lateinit var hotCornerDetector: HotCornerDetector

    private val _settingsRequested = MutableStateFlow(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentWebViewUrl: String = ""
    private var kioskWebView: KioskWebView? = null
    private var clearCacheRequested = false

    private var isRequestingPermissions = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        isRequestingPermissions = false
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] ?: false
        Log.i("MainActivity", "Permissions callback: CAMERA granted=$cameraGranted")
        if (cameraGranted) {
            val app = application as? KL8WallApplication
            app?.cameraManager?.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = application as KL8WallApplication
        settingsViewModel = SettingsViewModel(app.settingsRepository)

        handleIntent(intent)

        kioskLockManager = KioskLockManager(this)
        screenController = ScreenController(this)
        hotCornerDetector = HotCornerDetector(
            cornerProvider = { app.settingsRepository.hotCorner.value },
            onTriggered = { _settingsRequested.value = true }
        )

        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missingPermissions = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            isRequestingPermissions = true
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }

        if (!Settings.System.canWrite(this)) {
            try {
                val writeSettingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(writeSettingsIntent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start WRITE_SETTINGS settings intent", e)
            }
        }

        setContent {
            KL8WallTheme {
                KL8WallScreen(
                    viewModel = settingsViewModel,
                    settingsRequested = _settingsRequested,
                    onSettingsHandled = { _settingsRequested.value = false },
                    onWebViewAvailable = { wv ->
                        kioskWebView = wv
                        if (clearCacheRequested) {
                            wv.clearCache(true)
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            clearCacheRequested = false
                        }
                    },
                    onPageLoaded = { currentWebViewUrl = it }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val app = application as KL8WallApplication
        val startUrl = intent.getStringExtra("start_url")
        val haToken = intent.getStringExtra("ha_token")
        val bypassSetup = intent.getBooleanExtra("bypass_setup", false)
        val clearCache = intent.getBooleanExtra("clear_cache", false)
        val openSettings = intent.getBooleanExtra("open_settings", false)

        if (clearCache) {
            clearCacheRequested = true
        }
        if (openSettings) {
            _settingsRequested.value = true
        }
        if (!startUrl.isNullOrBlank()) {
            app.settingsRepository.setStartUrl(startUrl)
        }
        if (haToken != null) {
            app.settingsRepository.setHaToken(haToken)
        }
        
        val deviceName = intent.getStringExtra("device_name")
        if (!deviceName.isNullOrBlank()) {
            app.settingsRepository.setDeviceName(deviceName)
        }
        if (intent.hasExtra("mqtt_enabled")) {
            app.settingsRepository.setMqttEnabled(intent.getBooleanExtra("mqtt_enabled", false))
        }
        val mqttBroker = intent.getStringExtra("mqtt_broker")
        if (!mqttBroker.isNullOrBlank()) {
            app.settingsRepository.setMqttBroker(mqttBroker)
        }
        val mqttUsername = intent.getStringExtra("mqtt_username")
        if (mqttUsername != null) {
            app.settingsRepository.setMqttUsername(mqttUsername)
        }
        val mqttPassword = intent.getStringExtra("mqtt_password")
        if (mqttPassword != null) {
            app.settingsRepository.setMqttPassword(mqttPassword)
        }
        if (intent.hasExtra("bluetooth_proxy_enabled")) {
            app.settingsRepository.setBluetoothProxyEnabled(intent.getBooleanExtra("bluetooth_proxy_enabled", false))
        }
        if (intent.hasExtra("presence_sensor_enabled")) {
            app.settingsRepository.setPresenceSensorEnabled(intent.getBooleanExtra("presence_sensor_enabled", false))
        }

        if (bypassSetup || !startUrl.isNullOrBlank()) {
            app.settingsRepository.completeFirstRun()
        }
    }

    override fun onResume() {
        super.onResume()
        screenController.acquireWakeLock()

        val app = application as KL8WallApplication
        val devController = createDeviceController()
        app.deviceController = devController
        if (!app.settingsRepository.isFirstRun.value && !isRequestingPermissions) {
            kioskLockManager.lock(this)
        }
        if (!app.settingsRepository.isFirstRun.value) {
            app.startServer()
            app.startServices(this, devController)
        }
    }

    override fun onPause() {
        super.onPause()
        screenController.releaseWakeLock()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val app = application as KL8WallApplication
        if (!hasFocus && !app.settingsRepository.isFirstRun.value && !isRequestingPermissions) {
            // App lost focus, bring it back!
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as KL8WallApplication
        app.deviceController = null
        app.stopServices()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val root = window.decorView
        val density = root.resources.displayMetrics.density
        hotCornerDetector.onTouchEvent(event, root.width, root.height, density)
        
        val app = application as KL8WallApplication
        app.presenceSensorManager?.onUserInteraction()

        return super.dispatchTouchEvent(event)
    }

    private fun createDeviceController(): DeviceController {
        val activity = this
        val app = application as KL8WallApplication
        return object : DeviceController {
            override fun isScreenOn(): Boolean = screenController.isScreenOn.value
            override fun getCurrentUrl(): String = currentWebViewUrl
            override fun getLockState(): String = kioskLockManager.lockState.value.name
            override fun getBrightness(): Int = app.brightnessController.getBrightness()
            override fun canWriteSettings(): Boolean = app.brightnessController.canWriteSettings()

            override fun screenOn() {
                mainHandler.post { screenController.screenOn(activity) }
            }

            override fun screenOff() {
                mainHandler.post { screenController.screenOff(activity) }
            }

            override fun navigate(url: String) {
                val finalUrl = buildStartUrl(url) { app.settingsRepository.getHaToken() }
                mainHandler.post { kioskWebView?.loadUrl(finalUrl) }
            }

            override fun reload() {
                mainHandler.post { kioskWebView?.reload() }
            }

            override fun setBrightness(percent: Int): Boolean = app.brightnessController.setBrightness(percent)

            override fun speak(text: String) {
                app.ttsController.speak(text)
            }

            override fun stopSpeaking() {
                app.ttsController.stopSpeaking()
            }

            override fun openSettings() {
                mainHandler.post { _settingsRequested.value = true }
            }

            override fun getBatteryLevel(): Float {
                val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                return if (level >= 0 && scale > 0) (level * 100f / scale) else 0f
            }

            override fun getBatteryTemp(): Float {
                val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                return if (temp >= 0) (temp / 10f) else 0f
            }

            override fun getBatteryState(): String {
                val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                return when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                    else -> "unknown"
                }
            }

            @Suppress("DEPRECATION")
            override fun getWifiRssi(): Int {
                val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                return wifiManager.connectionInfo.rssi
            }

            @Suppress("DEPRECATION")
            override fun getWifiSsid(): String {
                val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ssid = wifiManager.connectionInfo.ssid
                return if (ssid != null && ssid != WifiManager.UNKNOWN_SSID) {
                    ssid.replace("\"", "")
                } else {
                    "unknown"
                }
            }

            override fun getRamUsagePercent(): Float {
                val mi = ActivityManager.MemoryInfo()
                val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                activityManager.getMemoryInfo(mi)
                val total = mi.totalMem.toDouble()
                val avail = mi.availMem.toDouble()
                return if (total > 0) (((total - avail) / total) * 100).toFloat() else 0f
            }

            override fun getStorageFreeGb(): Float {
                return try {
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
                    (bytesAvailable / (1024f * 1024f * 1024f))
                } catch (e: Exception) {
                    0f
                }
            }

            private fun getLocalIpAddress(): InetAddress? {
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                    for (intf in Collections.list(interfaces)) {
                        if (!intf.isUp || intf.isLoopback) continue
                        val addrs = intf.inetAddresses
                        for (addr in Collections.list(addrs)) {
                            if (!addr.isLoopbackAddress && addr is Inet4Address) {
                                return addr
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error getting local IP address", e)
                }
                return null
            }

            override fun getIpAddress(): String {
                return getLocalIpAddress()?.hostAddress ?: "0.0.0.0"
            }

            override fun getAppVersion(): String {
                return try {
                    val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    pInfo.versionName ?: "1.0.0"
                } catch (e: Exception) {
                    "1.0.0"
                }
            }

            override fun getUptimeSeconds(): Long {
                return SystemClock.elapsedRealtime() / 1000
            }

            override fun getTtsVolume(): Int {
                val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                return if (max > 0) (current * 100 / max) else 0
            }

            override fun setTtsVolume(volume: Int) {
                val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = (volume.coerceIn(0, 100) * max / 100f).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }

            override fun getScreenTimeoutSeconds(): Int {
                return app.settingsRepository.presenceTimeoutSeconds.value
            }

            override fun setScreenTimeoutSeconds(seconds: Int) {
                app.settingsRepository.setPresenceTimeoutSeconds(seconds)
            }

            override fun rebootApp() {
                mainHandler.post {
                    try {
                        Log.i("MainActivity", "Relaunching KL8Wall app...")
                        val packageManager = activity.packageManager
                        val intent = packageManager.getLaunchIntentForPackage(activity.packageName)
                        val componentName = intent?.component
                        val mainIntent = Intent.makeRestartActivityTask(componentName)
                        activity.startActivity(mainIntent)
                        Runtime.getRuntime().exit(0)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to relaunch app", e)
                    }
                }
            }

            override fun getAmbientLight(): Float {
                return app.presenceSensorManager?.latestLux ?: 0.0f
            }

            override fun getProximity(): Float {
                return app.presenceSensorManager?.latestProximity ?: 0.0f
            }

            override fun getPressure(): Float {
                return app.presenceSensorManager?.latestPressure ?: 0.0f
            }

            override fun getAmbientTemp(): Float {
                return app.presenceSensorManager?.latestAmbientTemp ?: 0.0f
            }

            override fun getHumidity(): Float {
                return app.presenceSensorManager?.latestHumidity ?: 0.0f
            }
        }
    }
}

@Composable
private fun KL8WallScreen(
    viewModel: SettingsViewModel,
    settingsRequested: StateFlow<Boolean>,
    onSettingsHandled: () -> Unit,
    onWebViewAvailable: (KioskWebView) -> Unit,
    onPageLoaded: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepo = (context.applicationContext as KL8WallApplication).settingsRepository
    val isFirstRun by viewModel.isFirstRun.collectAsState()
    val startUrl by viewModel.startUrl.collectAsState()
    val allowedHosts by viewModel.allowedHosts.collectAsState()
    val mediaGesture by viewModel.mediaPlaybackRequiresGesture.collectAsState()
    val isPinSet by viewModel.isPinSet.collectAsState()
    val settingsTriggered by settingsRequested.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showPinGate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var setupComplete by remember { mutableStateOf(!isFirstRun) }
    var webView by remember { mutableStateOf<KioskWebView?>(null) }
    var currentUrl by remember { mutableStateOf("") }

    LaunchedEffect(settingsTriggered) {
        if (settingsTriggered && !showPinGate && !showSettings) {
            if (isPinSet) {
                showPinGate = true
            } else {
                showSettings = true
            }
            onSettingsHandled()
        }
    }

    LaunchedEffect(mediaGesture) {
        webView?.setMediaGestureRequired(mediaGesture)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!setupComplete) {
                FirstRunSetup(
                    viewModel = viewModel,
                    onComplete = { setupComplete = true }
                )
            } else {
                KioskWebViewContainer(
                    startUrl = startUrl,
                    viewModel = viewModel,
                    allowedHosts = allowedHosts,
                    onWebViewCreated = { wv ->
                        webView = wv
                        onWebViewAvailable(wv)
                    },
                    onUrlChanged = { url ->
                        currentUrl = url
                        onPageLoaded(url)
                    },
                    onNavigationBlocked = { url ->
                        scope.launch {
                            snackbarHostState.showSnackbar("Navigation blocked: $url")
                        }
                    },
                    onError = { code, message ->
                        scope.launch {
                            snackbarHostState.showSnackbar("Error ($code): $message")
                        }
                    }
                )
            }
        }

        if (showPinGate) {
            Dialog(onDismissRequest = { showPinGate = false }) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    PinGate(
                        onPinVerified = {
                            showPinGate = false
                            showSettings = true
                        },
                        verifyPin = viewModel::verifyPin
                    )
                }
            }
        }

        if (showSettings) {
            SettingsSheet(
                viewModel = viewModel,
                onDismiss = {
                    showSettings = false
                    val newUrl = viewModel.startUrl.value
                    val finalUrl = buildStartUrl(newUrl) { settingsRepo.getHaToken() }
                    if (finalUrl.isNotBlank() && finalUrl != currentUrl) {
                        webView?.loadUrl(finalUrl)
                    }
                }
            )
        }
    }
}

@Composable
private fun KioskWebViewContainer(
    startUrl: String,
    viewModel: SettingsViewModel,
    allowedHosts: Set<String>,
    onWebViewCreated: (KioskWebView) -> Unit,
    onUrlChanged: (String) -> Unit,
    onNavigationBlocked: (String) -> Unit,
    onError: (Int, String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepo = (context.applicationContext as KL8WallApplication).settingsRepository

    androidx.compose.runtime.key(startUrl, allowedHosts) {
        AndroidView(
            factory = {
                KioskWebView(it).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    initBridge(startUrl, allowedHosts)

                    val interceptor = AuthInterceptor(
                        tokenProvider = { settingsRepo.getHaToken() },
                        allowedHosts = { viewModel.allowedHosts.value }
                    )

                    webViewClient = WebViewClientConfig(
                        authInterceptor = interceptor,
                        allowedHosts = { viewModel.allowedHosts.value },
                        onPageLoaded = onUrlChanged,
                        onNavigationBlocked = onNavigationBlocked,
                        onError = onError
                    )

                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                            request?.let {
                                val grantedResources = mutableListOf<String>()
                                for (res in it.resources) {
                                    if (res == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            grantedResources.add(res)
                                        }
                                    } else if (res == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            grantedResources.add(res)
                                        }
                                    }
                                }
                                if (grantedResources.isNotEmpty()) {
                                    it.grant(grantedResources.toTypedArray())
                                } else {
                                    it.deny()
                                }
                            }
                        }
                    }

                    onWebViewCreated(this)

                    val finalUrl = buildStartUrl(startUrl) { settingsRepo.getHaToken() }
                    if (finalUrl.isNotBlank()) {
                        loadUrl(finalUrl)
                    }
                }
            },
            update = { view ->
                val currentViewUrl = view.url ?: ""
                if (startUrl.isNotBlank() && currentViewUrl.isEmpty()) {
                    val finalUrl = buildStartUrl(startUrl) { settingsRepo.getHaToken() }
                    view.loadUrl(finalUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun buildStartUrl(baseUrl: String, tokenProvider: () -> String): String {
    if (baseUrl.isBlank()) return ""
    val token = tokenProvider()
    if (token.isBlank()) return baseUrl

    return try {
        val uri = android.net.Uri.parse(baseUrl)
        val builder = uri.buildUpon()
        if (uri.getQueryParameter("external_auth") == null) {
            builder.appendQueryParameter("external_auth", "1")
        }
        builder.build().toString()
    } catch (e: Exception) {
        baseUrl
    }
}
