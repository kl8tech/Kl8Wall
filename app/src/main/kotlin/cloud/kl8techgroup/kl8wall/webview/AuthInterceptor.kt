package cloud.kl8techgroup.kl8wall.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * Hook point for injecting authentication into WebView requests.
 *
 * Currently a no-op pass-through: returns null for all requests so the
 * WebView handles resource loading natively. HA's frontend manages its
 * own auth via localStorage after login, and intercepting GET requests
 * with HttpURLConnection broke the WebView's cookie jar, content-encoding
 * handling, and HTTP/2 connection pooling — causing a black screen.
 *
 * The long-lived token stored in settings is used by the KL8Wall HTTP
 * server's API endpoints (e.g. `/api/navigate`) rather than by the
 * WebView directly.
 *
 * Retained as an extension point for future auto-login support
 * (e.g. injecting the token via JavaScript or custom scheme).
 */
@Suppress("UnusedPrivateProperty")
class AuthInterceptor(
    private val tokenProvider: () -> String,
    private val allowedHosts: () -> Set<String>
) {

    /**
     * Intercept a WebView resource request.
     *
     * @return null to let the WebView handle the request natively.
     */
    @Suppress("FunctionOnlyReturningConstant", "UNUSED_PARAMETER")
    fun intercept(request: WebResourceRequest): WebResourceResponse? = null
}
