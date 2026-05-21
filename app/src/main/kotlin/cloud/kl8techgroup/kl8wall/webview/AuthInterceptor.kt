package cloud.kl8techgroup.kl8wall.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Injects the Home Assistant long-lived access token as an Authorization
 * header into WebView requests targeting allowed hosts.
 *
 * Uses [HttpURLConnection] to re-issue the request with the Bearer header,
 * since WebView's [WebResourceRequest] doesn't allow header modification
 * on the original request.
 */
class AuthInterceptor(
    private val tokenProvider: () -> String,
    private val allowedHosts: () -> Set<String>
) {

    /**
     * Intercepts a WebView request and re-issues it with the Bearer token
     * if the host is in the allowed list and a token is configured.
     *
     * Returns null if the request should proceed without interception
     * (no token set, host not in allowed list, or non-GET method on
     * sub-resources where interception would break POST/WS flows).
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val token = tokenProvider()
        val url = request.url
        val host = url?.host?.lowercase()

        if (token.isEmpty() || url == null || host == null) return null
        if (host !in allowedHosts() || request.method != "GET") return null

        return try {
            executeWithAuth(url.toString(), token, request.requestHeaders)
        } catch (_: IOException) {
            null
        }
    }

    private fun executeWithAuth(
        urlString: String,
        token: String,
        originalHeaders: Map<String, String>
    ): WebResourceResponse? {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            originalHeaders.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            connection.setRequestProperty("Authorization", "Bearer $token")

            val responseCode = connection.responseCode
            val contentType = connection.contentType ?: "text/html"
            val encoding = connection.contentEncoding ?: "utf-8"

            val mimeType = contentType.split(";").firstOrNull()?.trim() ?: "text/html"

            val inputStream = if (responseCode in SUCCESS_RANGE) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseHeaders = mutableMapOf<String, String>()
            connection.headerFields.forEach { (key, values) ->
                if (key != null && values.isNotEmpty()) {
                    responseHeaders[key] = values.last()
                }
            }

            return WebResourceResponse(
                mimeType,
                encoding,
                responseCode,
                connection.responseMessage ?: "OK",
                responseHeaders,
                inputStream
            )
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private val SUCCESS_RANGE = 200..299
    }
}
