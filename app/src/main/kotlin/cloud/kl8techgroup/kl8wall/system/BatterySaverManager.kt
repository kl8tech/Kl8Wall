package cloud.kl8techgroup.kl8wall.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import cloud.kl8techgroup.kl8wall.mqtt.MqttManager
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Monitors device battery level and controls a physical smart plug/charger
 * via Home Assistant's REST API and MQTT to preserve battery health.
 */
class BatterySaverManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "BatterySaverManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _chargerState = MutableStateFlow(false)
    
    /** Current state of the charger plug (true = ON, false = OFF) */
    val chargerState: StateFlow<Boolean> = _chargerState.asStateFlow()

    private var batteryReceiver: BroadcastReceiver? = null

    /**
     * Starts monitoring battery state.
     */
    fun start() {
        Log.d(TAG, "Starting BatterySaverManager...")
        
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                handleBatteryChange(intent)
            }
        }
        
        val stickyIntent = context.registerReceiver(batteryReceiver, filter)
        if (stickyIntent != null) {
            handleBatteryChange(stickyIntent)
        }
    }

    /**
     * Stops monitoring battery state.
     */
    fun stop() {
        Log.d(TAG, "Stopping BatterySaverManager...")
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering battery receiver", e)
            }
            batteryReceiver = null
        }
        scope.cancel()
    }

    /**
     * Set the charger state manually (e.g. via MQTT command or settings sheet).
     */
    fun setChargerStateOverride(on: Boolean) {
        Log.i(TAG, "Manual charger override requested: ON=$on")
        setChargerState(on, isAutomatic = false)
    }

    /**
     * Internal helper to update state and trigger action.
     */
    private fun setChargerState(on: Boolean, isAutomatic: Boolean) {
        _chargerState.value = on
        
        // Publish to MQTT state topic
        val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication
        app?.mqttManager?.publishChargerState(on)

        val enabled = settingsRepository.batterySaverEnabled.value
        // Only trigger REST API call if manually overridden OR if automation is enabled
        if (!isAutomatic || enabled) {
            scope.launch {
                sendHomeAssistantServiceCall(on)
            }
        }
    }

    private fun handleBatteryChange(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        
        val pct = (level * 100f / scale).toInt()
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                           status == BatteryManager.BATTERY_STATUS_FULL

        // Keep local state in sync with actual charging hardware state
        _chargerState.value = isCharging

        val minVal = settingsRepository.batterySaverMin.value
        val maxVal = settingsRepository.batterySaverMax.value

        Log.d(TAG, "Battery status update: level=$pct%, charging=$isCharging, thresholds=[min=$minVal, max=$maxVal]")

        // Charging hysteresis:
        // - Above or equal to max limit: Turn off charger
        // - Below or equal to min limit: Turn on charger
        // - In between: Keep current state (allows manual overrides to persist)
        if (pct >= maxVal && isCharging) {
            Log.i(TAG, "Battery level $pct% >= max threshold $maxVal% and charging. Turning charger OFF.")
            setChargerState(false, isAutomatic = true)
        } else if (pct <= minVal && !isCharging) {
            Log.i(TAG, "Battery level $pct% <= min threshold $minVal% and not charging. Turning charger ON.")
            setChargerState(true, isAutomatic = true)
        }
    }

    private fun sendHomeAssistantServiceCall(on: Boolean) {
        var startUrl = settingsRepository.startUrl.value
        if (startUrl.isBlank()) {
            Log.w(TAG, "Start URL is blank, skipping HA REST service call")
            return
        }
        
        if (!startUrl.contains("://")) {
            startUrl = "http://$startUrl"
        }

        val baseUrl = try {
            val uri = URI(startUrl)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return
            val port = uri.port
            val portSuffix = if (port != -1) ":$port" else ""
            "$scheme://$host$portSuffix"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse base URL from $startUrl", e)
            return
        }

        val token = settingsRepository.getHaToken()
        if (token.isBlank()) {
            Log.w(TAG, "HA Token is blank, skipping REST service call")
            return
        }

        val entityId = settingsRepository.batterySaverEntityId.value
        if (entityId.isBlank()) {
            Log.w(TAG, "Battery saver entity ID is blank, skipping REST service call")
            return
        }

        try {
            val service = if (on) "turn_on" else "turn_off"
            val urlSpec = "$baseUrl/api/services/switch/$service"
            Log.d(TAG, "Sending service call to HA: $urlSpec for $entityId")

            val jsonBody = JSONObject().apply {
                put("entity_id", entityId)
            }.toString()

            val app = context.applicationContext as cloud.kl8techgroup.kl8wall.KL8WallApplication
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonBody.toRequestBody(mediaType)
            val request = okhttp3.Request.Builder()
                .url(urlSpec)
                .post(requestBody)
                .header("Authorization", "Bearer $token")
                .build()

            app.okHttpClient.newCall(request).execute().use { response ->
                val responseCode = response.code
                if (response.isSuccessful) {
                    Log.i(TAG, "Successfully sent HA service call: $service for $entityId (HTTP $responseCode)")
                } else {
                    Log.e(TAG, "Failed to send HA service call: HTTP $responseCode, message: ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HA service call", e)
        }
    }
}
