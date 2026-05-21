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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
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

        val permissions = mutableListOf(android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())

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
        if (!app.settingsRepository.isFirstRun.value) {
            kioskLockManager.lock(this)
            app.startServer()
            app.startServices(this, devController)
        }
    }

    override fun onPause() {
        super.onPause()
        screenController.releaseWakeLock()
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
                mainHandler.post { kioskWebView?.loadUrl(url) }
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

            @Suppress("DEPRECATION")
            override fun getIpAddress(): String {
                val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ipInt = wifiManager.connectionInfo.ipAddress
                return if (ipInt != 0) {
                    String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                } else {
                    "0.0.0.0"
                }
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
                    if (newUrl.isNotBlank() && newUrl != currentUrl) {
                        webView?.loadUrl(newUrl)
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

    AndroidView(
        factory = {
            KioskWebView(it).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

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

                webChromeClient = android.webkit.WebChromeClient()

                onWebViewCreated(this)

                if (startUrl.isNotBlank()) {
                    loadUrl(startUrl)
                }
            }
        },
        update = { view ->
            val currentViewUrl = view.url ?: ""
            if (startUrl.isNotBlank() && currentViewUrl.isEmpty()) {
                view.loadUrl(startUrl)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
