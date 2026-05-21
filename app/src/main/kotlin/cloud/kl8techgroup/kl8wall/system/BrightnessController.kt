package cloud.kl8techgroup.kl8wall.system

import android.content.Context
import android.provider.Settings

/**
 * Controls screen brightness via [Settings.System].
 *
 * Requires [Settings.ACTION_MANAGE_WRITE_SETTINGS] permission (manual user
 * grant on API 23+). Exposes brightness as a 0–100 percentage scale,
 * mapped internally to the system 0–255 range. Forces manual brightness
 * mode before applying changes.
 */
class BrightnessController(private val context: Context) {

    /** Whether the app has permission to write system settings. */
    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    /** Current brightness as a 0–100 percentage. */
    fun getBrightness(): Int {
        val raw = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            DEFAULT_BRIGHTNESS
        )
        return (raw * MAX_PERCENT + MAX_SYSTEM / 2) / MAX_SYSTEM
    }

    /**
     * Set brightness to [percent] (clamped to 0–100).
     *
     * Switches to manual brightness mode before applying. Returns false
     * if the app lacks [Settings.ACTION_MANAGE_WRITE_SETTINGS] permission.
     */
    fun setBrightness(percent: Int): Boolean {
        if (!canWriteSettings()) return false
        val clamped = percent.coerceIn(0, MAX_PERCENT)
        val system = (clamped * MAX_SYSTEM + MAX_PERCENT / 2) / MAX_PERCENT

        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            system
        )
        return true
    }

    companion object {
        private const val MAX_SYSTEM = 255
        private const val MAX_PERCENT = 100
        private const val DEFAULT_BRIGHTNESS = 128
    }
}
