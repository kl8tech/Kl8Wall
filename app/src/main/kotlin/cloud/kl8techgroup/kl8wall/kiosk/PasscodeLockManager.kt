package cloud.kl8techgroup.kl8wall.kiosk

import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PasscodeLockManager(private val settingsRepository: SettingsRepository) {

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun lock() {
        _isLocked.value = true
    }

    fun unlock() {
        _isLocked.value = false
    }

    fun verifyPin(pin: String): Boolean {
        val pinHash = settingsRepository.getPinHash()
        if (pinHash.isEmpty()) {
            _isLocked.value = false
            return true
        }
        val isCorrect = PinHasher.verify(pin, pinHash)
        if (isCorrect) {
            _isLocked.value = false
        }
        return isCorrect
    }
}
