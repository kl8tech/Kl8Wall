package cloud.kl8techgroup.kl8wall.settings

import androidx.lifecycle.ViewModel
import cloud.kl8techgroup.kl8wall.kiosk.PinHasher
import kotlinx.coroutines.flow.StateFlow

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
}
