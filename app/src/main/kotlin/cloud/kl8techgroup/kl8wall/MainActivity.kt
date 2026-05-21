package cloud.kl8techgroup.kl8wall

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = application as KL8WallApplication
        settingsViewModel = SettingsViewModel(app.settingsRepository)
        kioskLockManager = KioskLockManager(this)
        screenController = ScreenController(this)
        hotCornerDetector = HotCornerDetector(
            cornerProvider = { app.settingsRepository.hotCorner.value },
            onTriggered = { _settingsRequested.value = true }
        )

        setContent {
            KL8WallTheme {
                KL8WallScreen(
                    viewModel = settingsViewModel,
                    settingsRequested = _settingsRequested,
                    onSettingsHandled = { _settingsRequested.value = false },
                    onWebViewAvailable = { kioskWebView = it },
                    onPageLoaded = { currentWebViewUrl = it }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        kioskLockManager.lock(this)
        screenController.acquireWakeLock()

        val app = application as KL8WallApplication
        app.deviceController = createDeviceController()
        if (!app.settingsRepository.isFirstRun.value) {
            app.startServer()
        }
    }

    override fun onPause() {
        super.onPause()
        screenController.releaseWakeLock()
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as KL8WallApplication).deviceController = null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val root = window.decorView
        hotCornerDetector.onTouchEvent(event, root.width, root.height)
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
