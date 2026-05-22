package cloud.kl8techgroup.kl8wall.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Hardened WebView for kiosk display of a Home Assistant dashboard.
 *
 * Disables file/content access, geolocation, mixed content, and universal
 * file access. Suppresses long-press context menus, text selection, and
 * pull-to-refresh swipe gestures. JavaScript is enabled for HA to function.
 */
class KioskWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        applyHardeningFlags()
        applyKioskBehavior()
    }

    @SuppressLint("JavascriptInterface")
    fun initBridge(startUrl: String, allowedHosts: Set<String>) {
        setupCookies()
        setupUserAgent(startUrl)

        val externalApp = ExternalApp(this)
        addJavascriptInterface(externalApp, "externalApp")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val originRules = getOriginRules(startUrl, allowedHosts)
            if (originRules.isNotEmpty()) {
                try {
                    WebViewCompat.addWebMessageListener(this, "externalAppV2", originRules) { _, message, _, _, _ ->
                        externalApp.handleV2Message(message.data)
                    }
                    Log.d("KioskWebView", "Registered externalAppV2 listener with rules: $originRules")
                } catch (e: Exception) {
                    Log.e("KioskWebView", "Failed to register externalAppV2 listener", e)
                }
            }
        }
    }

    private fun getOriginRules(startUrl: String, allowedHosts: Set<String>): Set<String> {
        val rules = mutableSetOf<String>()
        if (startUrl.isBlank()) return rules

        try {
            val startUri = android.net.Uri.parse(startUrl)
            val startScheme = startUri.scheme ?: "http"
            val startHost = startUri.host ?: ""
            val startPort = startUri.port

            if (startHost.isNotEmpty()) {
                if (startPort != -1) {
                    rules.add("$startScheme://$startHost:$startPort")
                } else {
                    rules.add("$startScheme://$startHost")
                }
            }

            allowedHosts.forEach { host ->
                rules.add("http://$host")
                rules.add("https://$host")
                if (startPort != -1 && startPort != 80 && startPort != 443) {
                    rules.add("http://$host:$startPort")
                    rules.add("https://$host:$startPort")
                }
            }
        } catch (e: Exception) {
            Log.e("KioskWebView", "Error generating origin rules", e)
        }
        return rules
    }

    private fun setupCookies() {
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@KioskWebView, true)
        }
    }

    private fun setupUserAgent(startUrl: String) {
        val defaultUserAgent = settings.userAgentString
        if (startUrl.contains("external_auth=1")) {
            if (!defaultUserAgent.contains("HomeAssistant/Android")) {
                settings.userAgentString = "$defaultUserAgent HomeAssistant/Android"
            }
        } else {
            settings.userAgentString = defaultUserAgent.replace(" HomeAssistant/Android", "")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun applyHardeningFlags() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true

            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
    }

    private fun applyKioskBehavior() {
        isFocusable = true
        isFocusableInTouchMode = true

        isLongClickable = false
        setOnLongClickListener { true }
        isHapticFeedbackEnabled = false

        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    /** Update media playback gesture policy at runtime from settings. */
    fun setMediaGestureRequired(required: Boolean) {
        settings.mediaPlaybackRequiresUserGesture = required
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    /**
     * Consumes flings to suppress pull-to-refresh and swipe-to-navigate
     * gestures that could break the kiosk lockdown.
     */
    private class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = true
    }
}
