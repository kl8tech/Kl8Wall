package cloud.kl8techgroup.kl8wall.ha

import android.util.Log
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

data class HaEventAlert(
    val entityId: String,
    val friendlyName: String,
    val newState: String,
    val oldState: String,
    val timestamp: Long = System.currentTimeMillis()
)

class HaEventManager(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "HaEventManager"
        private const val ALERT_AUTO_DISMISS_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 15_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var msgId = 1
    private var dismissJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile private var isRunning = false

    private val _activeAlert = MutableStateFlow<HaEventAlert?>(null)
    val activeAlert: StateFlow<HaEventAlert?> = _activeAlert.asStateFlow()

    fun start() {
        if (isRunning) return
        isRunning = true
        connect()
    }

    fun stop() {
        isRunning = false
        dismissJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Manager stopped")
        webSocket = null
        _activeAlert.value = null
    }

    fun dismissAlert() {
        dismissJob?.cancel()
        _activeAlert.value = null
    }

    private fun haBaseUrl(): String? {
        var startUrl = settingsRepository.startUrl.value
        if (startUrl.isBlank()) return null
        if (!startUrl.contains("://")) startUrl = "http://$startUrl"
        return try {
            val uri = URI(startUrl)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return null
            val port = uri.port
            val portSuffix = if (port != -1) ":$port" else ""
            "$scheme://$host$portSuffix"
        } catch (_: Exception) { null }
    }

    private fun wsUrl(base: String): String =
        base.replace("https://", "wss://").replace("http://", "ws://") + "/api/websocket"

    private fun connect() {
        if (!isRunning) return
        val base = haBaseUrl() ?: run { scheduleReconnect(); return }
        val token = settingsRepository.getHaToken()
        if (token.isBlank()) { scheduleReconnect(); return }

        val url = wsUrl(base)
        Log.i(TAG, "Connecting to HA WebSocket: $url")

        val wsClient = okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        msgId = 1
        webSocket = wsClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "HA WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text, token)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "HA WebSocket failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "HA WebSocket closed ($code): $reason")
                if (isRunning) scheduleReconnect()
            }
        })
    }

    private fun handleMessage(ws: WebSocket, text: String, token: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "auth_required" -> ws.send(
                    JSONObject().apply {
                        put("type", "auth")
                        put("access_token", token)
                    }.toString()
                )
                "auth_ok" -> {
                    ws.send(JSONObject().apply {
                        put("id", msgId++)
                        put("type", "subscribe_events")
                        put("event_type", "state_changed")
                    }.toString())
                    Log.i(TAG, "Authenticated — subscribed to state_changed")
                }
                "auth_invalid" -> {
                    Log.e(TAG, "HA token rejected — check settings")
                    ws.close(1000, "auth_invalid")
                }
                "event" -> {
                    val event = json.optJSONObject("event") ?: return
                    if (event.optString("event_type") != "state_changed") return
                    val data = event.optJSONObject("data") ?: return
                    handleStateChange(data)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling HA WebSocket message", e)
        }
    }

    private fun handleStateChange(data: JSONObject) {
        val entityId = data.optString("entity_id")
        val watchList = settingsRepository.haEventEntities.value
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (watchList.isEmpty()) return
        val matched = watchList.any { pattern ->
            when {
                pattern.endsWith("*") -> entityId.startsWith(pattern.dropLast(1))
                else -> entityId == pattern
            }
        }
        if (!matched) return

        val newStateObj = data.optJSONObject("new_state") ?: return
        val oldStateObj = data.optJSONObject("old_state")
        val newState = newStateObj.optString("state", "unknown")
        val oldState = oldStateObj?.optString("state", "") ?: ""
        if (newState == oldState) return

        val friendlyName = newStateObj
            .optJSONObject("attributes")
            ?.optString("friendly_name", entityId) ?: entityId

        Log.i(TAG, "Alert: $entityId → $newState (was $oldState)")
        _activeAlert.value = HaEventAlert(entityId, friendlyName, newState, oldState)
        scheduleDismiss()
    }

    private fun scheduleDismiss() {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(ALERT_AUTO_DISMISS_MS)
            _activeAlert.value = null
        }
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (isRunning && isActive) connect()
        }
    }
}
