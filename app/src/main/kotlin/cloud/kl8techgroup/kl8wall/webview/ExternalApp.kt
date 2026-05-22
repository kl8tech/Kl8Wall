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
     * V2 Message Handler for `WebViewCompat.addWebMessageListener` ("externalAppV2").
     */
    fun handleV2Message(message: String?) {
        if (message == null) return
        Log.d("ExternalApp", "V2 Message received: $message")
        try {
            val json = JSONObject(message)
            val type = json.optString("type")

            when (type) {
                "getExternalAuth" -> {
                    val payload = json.optJSONObject("payload")
                    val callback = payload?.optString("callback") ?: "externalAuthSetToken"
                    val force = payload?.optBoolean("force") ?: false
                    handleGetExternalAuth(callback, force)
                }
                "revokeExternalAuth" -> {
                    val payload = json.optJSONObject("payload")
                    val callback = payload?.optString("callback") ?: "externalAuthRevokeToken"
                    handleRevokeExternalAuth(callback)
                }
                else -> {
                    // Treat as standard external bus message
                    handleExternalBus(message)
                }
            }
        } catch (e: Exception) {
            Log.e("ExternalApp", "Error handling V2 message: $message", e)
        }
    }

    /**
     * V1 Fallback: Called by the Home Assistant frontend to request an authentication token.
     */
    @JavascriptInterface
    fun getExternalAuth(callback: String, force: Boolean) {
        handleGetExternalAuth(callback, force)
    }

    /**
     * V1 Fallback: Called by the Home Assistant frontend to revoke the current authentication session.
     */
    @JavascriptInterface
    fun revokeExternalAuth(callback: String) {
        handleRevokeExternalAuth(callback)
    }

    /**
     * V1 Fallback: Generalized message bus endpoint for frontend-to-app commands.
     */
    @JavascriptInterface
    fun externalBus(message: String) {
        handleExternalBus(message)
    }

    // --- Unified Internal Handlers ---

    private fun handleGetExternalAuth(callback: String, force: Boolean) {
        val token = app.settingsRepository.getHaToken()
        val script = if (token.isNotEmpty()) {
            "window.$callback(true, { access_token: '$token', expires_in: 1800 })"
        } else {
            "window.$callback(false, null)"
        }

        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun handleRevokeExternalAuth(callback: String) {
        webView.post {
            webView.evaluateJavascript("window.$callback(true)", null)
        }
        app.settingsRepository.setHaToken("")
    }

    private fun handleExternalBus(message: String) {
        Log.d("ExternalApp", "handleExternalBus message received: $message")
        try {
            val json = JSONObject(message)
            val id = json.optLong("id")
            val type = json.optString("type")

            when (type) {
                "config/get" -> {
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
