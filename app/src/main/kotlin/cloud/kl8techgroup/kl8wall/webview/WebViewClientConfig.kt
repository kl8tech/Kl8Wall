package cloud.kl8techgroup.kl8wall.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Custom [WebViewClient] that enforces allowed-hosts navigation restrictions
 * and injects authentication headers via [AuthInterceptor].
 *
 * Blocks navigation to any host not in the allowed set. Handles SSL errors
 * by rejecting the connection (no user-bypass option in kiosk mode).
 */
class WebViewClientConfig(
    private val authInterceptor: AuthInterceptor,
    private val allowedHosts: () -> Set<String>,
    private val onPageLoaded: (String) -> Unit = {},
    private val onNavigationBlocked: (String) -> Unit = {},
    private val onError: (Int, String) -> Unit = { _, _ -> }
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url?.host?.lowercase() ?: return true

        if (!isHostAllowed(host)) {
            onNavigationBlocked(request.url.toString())
            return true
        }

        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        authInterceptor.intercept(request)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        url?.let { onPageLoaded(it) }
        view.evaluateJavascript(DISABLE_SELECTION_JS, null)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        onError(ERROR_SSL, "SSL error: ${error.primaryError}")
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            onError(error.errorCode, error.description?.toString() ?: "Unknown error")
        }
    }

    private fun isHostAllowed(host: String): Boolean {
        val hosts = allowedHosts()
        if (hosts.isEmpty()) return true
        return hosts.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    companion object {
        private const val ERROR_SSL = -1

        private const val DISABLE_SELECTION_JS = """
            (function() {
                document.documentElement.style.webkitUserSelect = 'none';
                document.documentElement.style.userSelect = 'none';
                document.documentElement.style.webkitTouchCallout = 'none';
            })();
        """
    }
}
