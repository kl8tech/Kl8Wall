package cloud.kl8techgroup.kl8wall.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebView

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
        setupCookies()
        setupUserAgent()
        registerJsBridge()
    }

    private fun setupCookies() {
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@KioskWebView, true)
        }
    }

    private fun setupUserAgent() {
        val defaultUserAgent = settings.userAgentString
        if (!defaultUserAgent.contains("HomeAssistant/Android")) {
            settings.userAgentString = "$defaultUserAgent HomeAssistant/Android"
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun registerJsBridge() {
        addJavascriptInterface(ExternalApp(this), "externalApp")
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
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

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
