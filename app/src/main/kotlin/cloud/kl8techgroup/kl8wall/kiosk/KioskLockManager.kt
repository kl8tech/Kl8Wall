package cloud.kl8techgroup.kl8wall.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages kiosk lockdown via Device Owner or screen pinning.
 *
 * If the app is registered as Device Owner (`dpc set-device-owner`),
 * enables full lock task mode: disables the status bar, blocks other
 * apps, and prevents uninstall. Falls back to screen pinning if
 * Device Owner is not configured.
 */
class KioskLockManager(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, KioskDeviceAdminReceiver::class.java)

    private val _lockState = MutableStateFlow(LockState.UNLOCKED)

    /** Current lock state. */
    val lockState: StateFlow<LockState> = _lockState.asStateFlow()

    /** Whether the app is registered as Device Owner. */
    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * Enable kiosk lockdown. Uses Device Owner lock task if available,
     * otherwise falls back to screen pinning.
     */
    fun lock(activity: Activity) {
        if (isDeviceOwner) {
            enableDeviceOwnerLock(activity)
        } else {
            enableScreenPinning(activity)
        }
    }

    /** Disable kiosk lockdown. */
    fun unlock(activity: Activity) {
        when (_lockState.value) {
            LockState.DEVICE_OWNER -> disableDeviceOwnerLock(activity)
            LockState.SCREEN_PINNED -> disableScreenPinning(activity)
            LockState.UNLOCKED -> { }
        }
    }

    private fun enableDeviceOwnerLock(activity: Activity) {
        dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))

        if (dpm.isLockTaskPermitted(context.packageName)) {
            activity.startLockTask()
            _lockState.value = LockState.DEVICE_OWNER

            dpm.setStatusBarDisabled(adminComponent, true)
            dpm.setKeyguardDisabled(adminComponent, true)
        }
    }

    private fun disableDeviceOwnerLock(activity: Activity) {
        dpm.setStatusBarDisabled(adminComponent, false)
        dpm.setKeyguardDisabled(adminComponent, false)
        activity.stopLockTask()
        _lockState.value = LockState.UNLOCKED
    }

    @Suppress("DEPRECATION")
    private fun enableScreenPinning(activity: Activity) {
        activity.startLockTask()
        _lockState.value = LockState.SCREEN_PINNED
    }

    private fun disableScreenPinning(activity: Activity) {
        activity.stopLockTask()
        _lockState.value = LockState.UNLOCKED
    }

    /** Kiosk lock states. */
    enum class LockState {
        UNLOCKED,
        DEVICE_OWNER,
        SCREEN_PINNED
    }
}
