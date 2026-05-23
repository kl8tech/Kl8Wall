package cloud.kl8techgroup.kl8wall.system

import android.content.Context
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controls screen wake/sleep state and wake lock management.
 *
 * Maintains [FLAG_KEEP_SCREEN_ON][WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]
 * while the kiosk is active and provides screen on/off control for the REST API.
 */
class ScreenController(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val _isScreenOn = MutableStateFlow(powerManager.isInteractive)

    /** Current screen state. */
    val isScreenOn: StateFlow<Boolean> = _isScreenOn.asStateFlow()

    /** Acquire a wake lock to keep the screen on. Call from Activity.onResume. */
    @Suppress("WakeLockTag", "DEPRECATION")
    fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG
            )
        }
        wakeLock?.let { lock ->
            if (!lock.isHeld) {
                lock.acquire()
                _isScreenOn.value = true
            }
        }
    }

    /** Release the wake lock. Call from Activity.onPause. */
    fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
    }

    /** Wake the screen and bring the activity to the foreground. */
    @Suppress("DEPRECATION")
    fun screenOn(activity: ComponentActivity) {
        activity.window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }

        // Also acquire a temporary wakeup lock to force the screen to turn on
        try {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                val tempLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "kl8wall:tempwake"
                )
                tempLock.acquire(3000L)
            }
        } catch (e: Exception) {
            android.util.Log.e("ScreenController", "Failed to acquire temporary wake lock", e)
        }

        acquireWakeLock()
        _isScreenOn.value = true
    }

    /** Dim the screen and allow the system to sleep. */
    fun screenOff(activity: ComponentActivity) {
        releaseWakeLock()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _isScreenOn.value = false
    }

    /** Refresh the screen state from the system. */
    fun refreshState() {
        _isScreenOn.value = powerManager.isInteractive
    }

    companion object {
        private const val WAKE_LOCK_TAG = "kl8wall:kiosk"
        private const val WAKE_LOCK_TIMEOUT_MS = 24L * 60 * 60 * 1000
    }
}
