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
