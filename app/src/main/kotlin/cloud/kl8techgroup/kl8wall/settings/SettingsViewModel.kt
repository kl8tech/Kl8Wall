package cloud.kl8techgroup.kl8wall.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.kl8techgroup.kl8wall.kiosk.PinHasher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray

/**
 * ViewModel bridging the settings UI to [SettingsRepository].
 *
 * Exposes settings as [StateFlow] for the Compose UI to observe and
 * provides action methods for user modifications.
 */
class SettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {

    val startUrl: StateFlow<String> = repository.startUrl
    val haTokenSet: StateFlow<Boolean> = repository.haTokenSet
    val httpPort: StateFlow<Int> = repository.httpPort
    val httpBearerToken: StateFlow<String> = repository.httpBearerToken
    val allowedHosts: StateFlow<Set<String>> = repository.allowedHosts
    val hotCorner: StateFlow<HotCorner> = repository.hotCorner
    val isPinSet: StateFlow<Boolean> = repository.isPinSet
    val isFirstRun: StateFlow<Boolean> = repository.isFirstRun
    val mediaPlaybackRequiresGesture: StateFlow<Boolean> = repository.mediaPlaybackRequiresGesture
    
    val screenAlwaysOn: StateFlow<Boolean> = repository.screenAlwaysOn
    val ignoreSslErrors: StateFlow<Boolean> = repository.ignoreSslErrors
    val micShimEnabled: StateFlow<Boolean> = repository.micShimEnabled
    val autoWakeOnPower: StateFlow<Boolean> = repository.autoWakeOnPower
    val mdnsEnabled: StateFlow<Boolean> = repository.mdnsEnabled
    val sensorIntervalSeconds: StateFlow<Int> = repository.sensorIntervalSeconds
    val autoBrightnessEnabled: StateFlow<Boolean> = repository.autoBrightnessEnabled
    val lowPowerModeEnabled: StateFlow<Boolean> = repository.lowPowerModeEnabled
    val minBrightnessPercent: StateFlow<Int> = repository.minBrightnessPercent
    val manualBrightnessPercent: StateFlow<Int> = repository.manualBrightnessPercent
    
    val batterySaverEnabled: StateFlow<Boolean> = repository.batterySaverEnabled
    val batterySaverEntityId: StateFlow<String> = repository.batterySaverEntityId
    val batterySaverMin: StateFlow<Int> = repository.batterySaverMin
    val batterySaverMax: StateFlow<Int> = repository.batterySaverMax
    val intercomTarget: StateFlow<String> = repository.intercomTarget
    val voiceAssistantEnabled: StateFlow<Boolean> = repository.voiceAssistantEnabled
    val voiceWakeWord: StateFlow<String> = repository.voiceWakeWord
    val manualPeers: StateFlow<String> = repository.manualPeers
    val webViewLivenessProbeEnabled: StateFlow<Boolean> = repository.webViewLivenessProbeEnabled
    val dimScheduleEnabled: StateFlow<Boolean> = repository.dimScheduleEnabled
    val dimScheduleStartHour: StateFlow<Int> = repository.dimScheduleStartHour
    val dimScheduleStartMinute: StateFlow<Int> = repository.dimScheduleStartMinute
    val dimScheduleEndHour: StateFlow<Int> = repository.dimScheduleEndHour
    val dimScheduleEndMinute: StateFlow<Int> = repository.dimScheduleEndMinute
    val haKioskMode: StateFlow<Boolean> = repository.haKioskMode
    val haEventsEnabled: StateFlow<Boolean> = repository.haEventsEnabled
    val haEventEntities: StateFlow<String> = repository.haEventEntities
    val dashboardContextEnabled: StateFlow<Boolean> = repository.dashboardContextEnabled
    val dashboardMorningUrl: StateFlow<String> = repository.dashboardMorningUrl
    val dashboardMorningStartHour: StateFlow<Int> = repository.dashboardMorningStartHour
    val dashboardMorningEndHour: StateFlow<Int> = repository.dashboardMorningEndHour
    val dashboardNightUrl: StateFlow<String> = repository.dashboardNightUrl
    val dashboardNightStartHour: StateFlow<Int> = repository.dashboardNightStartHour
    val dashboardNightEndHour: StateFlow<Int> = repository.dashboardNightEndHour
    val voiceOfflinePreferred: StateFlow<Boolean> = repository.voiceOfflinePreferred
    val localLlmEnabled: StateFlow<Boolean> = repository.localLlmEnabled
    val localLlmEndpoint: StateFlow<String> = repository.localLlmEndpoint
    val localLlmModel: StateFlow<String> = repository.localLlmModel
    val facePresenceEnabled: StateFlow<Boolean> = repository.facePresenceEnabled

    val deviceName: StateFlow<String> = repository.deviceName
    val mqttEnabled: StateFlow<Boolean> = repository.mqttEnabled
    val mqttBroker: StateFlow<String> = repository.mqttBroker
    val mqttPort: StateFlow<Int> = repository.mqttPort
    val mqttUsername: StateFlow<String> = repository.mqttUsername
    val mqttPassword: StateFlow<String> = repository.mqttPassword
    val bluetoothProxyEnabled: StateFlow<Boolean> = repository.bluetoothProxyEnabled
    val presenceSensorEnabled: StateFlow<Boolean> = repository.presenceSensorEnabled
    val presenceTimeoutSeconds: StateFlow<Int> = repository.presenceTimeoutSeconds
    val cameraIntervalMinutes: StateFlow<Int> = repository.cameraIntervalMinutes

    fun setDeviceName(name: String) = repository.setDeviceName(name)
    fun setMqttEnabled(enabled: Boolean) = repository.setMqttEnabled(enabled)
    fun setMqttBroker(broker: String) = repository.setMqttBroker(broker)
    fun setMqttPort(port: Int) = repository.setMqttPort(port)
    fun setMqttUsername(username: String) = repository.setMqttUsername(username)
    fun setMqttPassword(password: String) = repository.setMqttPassword(password)
    fun setBluetoothProxyEnabled(enabled: Boolean) = repository.setBluetoothProxyEnabled(enabled)
    fun setPresenceSensorEnabled(enabled: Boolean) = repository.setPresenceSensorEnabled(enabled)
    fun setPresenceTimeoutSeconds(seconds: Int) = repository.setPresenceTimeoutSeconds(seconds)
    fun setCameraIntervalMinutes(minutes: Int) = repository.setCameraIntervalMinutes(minutes)

    fun setScreenAlwaysOn(enabled: Boolean) = repository.setScreenAlwaysOn(enabled)
    fun setIgnoreSslErrors(ignore: Boolean) = repository.setIgnoreSslErrors(ignore)
    fun setMicShimEnabled(enabled: Boolean) = repository.setMicShimEnabled(enabled)
    fun setAutoWakeOnPower(enabled: Boolean) = repository.setAutoWakeOnPower(enabled)
    fun setMdnsEnabled(enabled: Boolean) = repository.setMdnsEnabled(enabled)
    fun setSensorIntervalSeconds(seconds: Int) = repository.setSensorIntervalSeconds(seconds)
    fun setAutoBrightnessEnabled(enabled: Boolean) = repository.setAutoBrightnessEnabled(enabled)
    fun setLowPowerModeEnabled(enabled: Boolean) = repository.setLowPowerModeEnabled(enabled)
    fun setMinBrightnessPercent(percent: Int) = repository.setMinBrightnessPercent(percent)
    fun setManualBrightnessPercent(percent: Int) = repository.setManualBrightnessPercent(percent)

    fun setBatterySaverEnabled(enabled: Boolean) = repository.setBatterySaverEnabled(enabled)
    fun setBatterySaverEntityId(entityId: String) = repository.setBatterySaverEntityId(entityId)
    fun setBatterySaverMin(min: Int) = repository.setBatterySaverMin(min)
    fun setBatterySaverMax(max: Int) = repository.setBatterySaverMax(max)
    fun setIntercomTarget(target: String) = repository.setIntercomTarget(target)
    fun setVoiceAssistantEnabled(enabled: Boolean) = repository.setVoiceAssistantEnabled(enabled)
    fun setVoiceWakeWord(wakeWord: String) = repository.setVoiceWakeWord(wakeWord)
    fun setManualPeers(peersList: String) = repository.setManualPeers(peersList)
    fun setWebViewLivenessProbeEnabled(enabled: Boolean) = repository.setWebViewLivenessProbeEnabled(enabled)
    fun setDimScheduleEnabled(enabled: Boolean) = repository.setDimScheduleEnabled(enabled)
    fun setDimScheduleStartHour(hour: Int) = repository.setDimScheduleStartHour(hour)
    fun setDimScheduleStartMinute(minute: Int) = repository.setDimScheduleStartMinute(minute)
    fun setDimScheduleEndHour(hour: Int) = repository.setDimScheduleEndHour(hour)
    fun setDimScheduleEndMinute(minute: Int) = repository.setDimScheduleEndMinute(minute)
    fun setHaKioskMode(enabled: Boolean) = repository.setHaKioskMode(enabled)
    fun setHaEventsEnabled(enabled: Boolean) = repository.setHaEventsEnabled(enabled)
    fun setHaEventEntities(entities: String) = repository.setHaEventEntities(entities)
    fun setDashboardContextEnabled(enabled: Boolean) = repository.setDashboardContextEnabled(enabled)
    fun setDashboardMorningUrl(url: String) = repository.setDashboardMorningUrl(url)
    fun setDashboardMorningStartHour(hour: Int) = repository.setDashboardMorningStartHour(hour)
    fun setDashboardMorningEndHour(hour: Int) = repository.setDashboardMorningEndHour(hour)
    fun setDashboardNightUrl(url: String) = repository.setDashboardNightUrl(url)
    fun setDashboardNightStartHour(hour: Int) = repository.setDashboardNightStartHour(hour)
    fun setDashboardNightEndHour(hour: Int) = repository.setDashboardNightEndHour(hour)
    fun setVoiceOfflinePreferred(preferred: Boolean) = repository.setVoiceOfflinePreferred(preferred)
    fun setLocalLlmEnabled(enabled: Boolean) = repository.setLocalLlmEnabled(enabled)
    fun setLocalLlmEndpoint(endpoint: String) = repository.setLocalLlmEndpoint(endpoint)
    fun setLocalLlmModel(model: String) = repository.setLocalLlmModel(model)
    fun setFacePresenceEnabled(enabled: Boolean) = repository.setFacePresenceEnabled(enabled)

    fun setStartUrl(url: String) = repository.setStartUrl(url)

    fun setHaToken(token: String) = repository.setHaToken(token)

    fun setHttpPort(port: Int) = repository.setHttpPort(port)

    fun rotateHttpBearerToken(): String = repository.rotateHttpBearerToken()

    fun setAllowedHosts(hosts: Set<String>) = repository.setAllowedHosts(hosts)

    fun addAllowedHost(host: String) = repository.addAllowedHost(host)

    fun removeAllowedHost(host: String) {
        val updated = repository.allowedHosts.value - host
        repository.setAllowedHosts(updated)
    }

    fun setHotCorner(corner: HotCorner) = repository.setHotCorner(corner)

    fun setMediaPlaybackRequiresGesture(required: Boolean) = repository.setMediaPlaybackRequiresGesture(required)

    /** Hash and store a new settings PIN. */
    fun setPin(pin: String) {
        val encoded = PinHasher.hash(pin)
        repository.setPinHash(encoded, "")
    }

    /** Remove the settings PIN. */
    fun clearPin() = repository.setPinHash("", "")

    /** Verify a PIN attempt against the stored hash. */
    fun verifyPin(pin: String): Boolean = PinHasher.verify(pin, repository.getPinHash())

    fun completeFirstRun() = repository.completeFirstRun()

    fun syncPublicConfig(
        peerIp: String,
        peerPort: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("http://$peerIp:$peerPort/api/peer/public_config")
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                
                val app = cloud.kl8techgroup.kl8wall.KL8WallApplication.instance
                val meshAuth = app.peerManager?.getMeshAuthToken() ?: ""
                if (meshAuth.isNotEmpty()) {
                    connection.setRequestProperty("x-kl8wall-mesh-auth", meshAuth)
                }
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    
                    withContext(Dispatchers.Main) {
                        repository.setStartUrl(json.optString("startUrl", ""))
                        repository.setMqttBroker(json.optString("mqttBroker", ""))
                        repository.setMqttPort(json.optInt("mqttPort", 1883))
                        
                        val hostsArray = json.optJSONArray("allowedHosts")
                        if (hostsArray != null) {
                            val hostsSet = mutableSetOf<String>()
                            for (i in 0 until hostsArray.length()) {
                                hostsSet.add(hostsArray.getString(i))
                            }
                            repository.setAllowedHosts(hostsSet)
                        }
                        
                        repository.setSensorIntervalSeconds(json.optInt("sensorIntervalSeconds", 30))
                        repository.setPresenceTimeoutSeconds(json.optInt("presenceTimeoutSeconds", 60))
                        repository.setBluetoothProxyEnabled(json.optBoolean("bluetoothProxyEnabled", false))
                        repository.setCameraIntervalMinutes(json.optInt("cameraIntervalMinutes", 60))

                        // Sync battery saver, voice assistant, and intercom target settings
                        repository.setBatterySaverEnabled(json.optBoolean("batterySaverEnabled", false))
                        repository.setBatterySaverEntityId(json.optString("batterySaverEntityId", "switch.tablet_charger"))
                        repository.setBatterySaverMin(json.optInt("batterySaverMin", 20))
                        repository.setBatterySaverMax(json.optInt("batterySaverMax", 80))
                        repository.setVoiceAssistantEnabled(json.optBoolean("voiceAssistantEnabled", false))
                        repository.setVoiceWakeWord(json.optString("voiceWakeWord", "hey wall"))
                        repository.setIntercomTarget(json.optString("intercomTarget", "living_room"))
                        repository.setManualPeers(json.optString("manualPeers", ""))
                        
                        onSuccess()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError("Server returned status ${connection.responseCode}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Failed to connect to peer")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun syncSecureConfig(
        peerIp: String,
        peerPort: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val localName = repository.deviceName.value
                val encodedName = java.net.URLEncoder.encode(localName, "UTF-8")
                val url = URL("http://$peerIp:$peerPort/api/peer/secure_config?requester_name=$encodedName")
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 35000 // 35 seconds to allow time for approval
                connection.readTimeout = 35000
                connection.requestMethod = "GET"
                
                val app = cloud.kl8techgroup.kl8wall.KL8WallApplication.instance
                val meshAuth = app.peerManager?.getMeshAuthToken() ?: ""
                if (meshAuth.isNotEmpty()) {
                    connection.setRequestProperty("x-kl8wall-mesh-auth", meshAuth)
                }
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    
                    withContext(Dispatchers.Main) {
                        val token = json.optString("haToken", "")
                        if (token.isNotEmpty()) {
                            repository.setHaToken(token)
                        }
                        val mqttPassword = json.optString("mqttPassword", "")
                        if (mqttPassword.isNotEmpty()) {
                            repository.setMqttPassword(mqttPassword)
                        }
                        onSuccess()
                    }
                } else {
                    val errorText = try {
                        val errorJson = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        if (errorJson != null) JSONObject(errorJson).optString("message", "") else ""
                    } catch (_: Exception) { "" }
                    
                    withContext(Dispatchers.Main) {
                        onError(errorText.ifBlank { "Request denied or timed out" })
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Sync request failed")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }
}
