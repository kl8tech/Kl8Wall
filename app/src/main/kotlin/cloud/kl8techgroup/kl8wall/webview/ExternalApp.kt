package cloud.kl8techgroup.kl8wall.webview

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import cloud.kl8techgroup.kl8wall.KL8WallApplication
import org.json.JSONObject

/**
 * Native JavaScript interface exposed to the WebView as `window.externalApp`.
 *
 * Implements the official Home Assistant Companion App external authentication
 * and external bus APIs. This enables auto-login using the stored long-lived
 * access token and binds Lovelace interface commands (like launching native settings)
 * back to our native layers.
 */
class ExternalApp(private val webView: WebView) {

    private val app = webView.context.applicationContext as KL8WallApplication

    /**
     * Called by the Home Assistant frontend to request an authentication token.
     *
     * @param callback The JavaScript global function name to invoke with results.
     *                 For security, only "externalAuthSetToken" is accepted.
     * @param force True if the frontend wants to force token renewal.
     */
    @JavascriptInterface
    fun getExternalAuth(callback: String, force: Boolean) {
        Log.d("ExternalApp", "getExternalAuth called: callback=$callback, force=$force")

        // Strictest possible security: reject any callback other than the exact expected function
        if (callback != "externalAuthSetToken") {
            Log.w("ExternalApp", "Rejected getExternalAuth callback: $callback")
            return
        }

        val token = app.settingsRepository.getHaToken()
        val script = if (token.isNotEmpty()) {
            // Deliver token back to the Lovelace authentication module
            "window.$callback(true, { access_token: '$token', expires_in: 1800 })"
        } else {
            "window.$callback(false, null)"
        }

        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * Called by the Home Assistant frontend to revoke the current authentication session.
     *
     * @param callback The JavaScript function to call when done.
     *                 For security, only "externalAuthRevokeToken" is accepted.
     */
    @JavascriptInterface
    fun revokeExternalAuth(callback: String) {
        Log.d("ExternalApp", "revokeExternalAuth called: callback=$callback")

        if (callback != "externalAuthRevokeToken") {
            Log.w("ExternalApp", "Rejected revokeExternalAuth callback: $callback")
            return
        }

        val script = "window.$callback(true)"
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * Generalized message bus endpoint for frontend-to-app commands.
     *
     * @param message A serialized JSON payload containing "id", "type", and "payload".
     */
    @JavascriptInterface
    fun externalBus(message: String) {
        Log.d("ExternalApp", "externalBus message received: $message")
        try {
            val json = JSONObject(message)
            val id = json.optLong("id")
            val type = json.optString("type")

            when (type) {
                "config/get" -> {
                    // Report capabilities so the Lovelace frontend knows we support settings integrations
                    val result = JSONObject().apply {
                        put("hasSettingsScreen", true)
                        put("canWriteTag", false)
                        put("hasExoPlayer", false)
                        put("canCommissionMatter", false)
                        put("canImportThreadCredentials", false)
                        put("hasAssist", false)
                        put("hasBarCodeScanner", false)
                        put("canSetupImprov", false)
                    }
                    sendBusResult(id, result)
                }
                "config_screen/show" -> {
                    // Open our settings overlay when clicking 'App Settings' in the sidebar menu
                    webView.post {
                        app.deviceController?.openSettings()
                    }
                }
                else -> {
                    Log.d("ExternalApp", "Unhandled externalBus message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e("ExternalApp", "Error parsing externalBus message", e)
        }
    }

    /**
     * Send command success results back to the Home Assistant frontend.
     */
    private fun sendBusResult(id: Long, result: JSONObject) {
        val response = JSONObject().apply {
            put("id", id)
            put("type", "result")
            put("success", true)
            put("result", result)
        }
        val escaped = response.toString().replace("'", "\\'")
        val script = "window.externalBus('$escaped')"
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }
}
