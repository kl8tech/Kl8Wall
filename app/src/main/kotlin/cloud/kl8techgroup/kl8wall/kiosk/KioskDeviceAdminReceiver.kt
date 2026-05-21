package cloud.kl8techgroup.kl8wall.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin receiver required for Device Owner mode.
 *
 * Set as Device Owner via ADB:
 * `adb shell dpc set-device-owner cloud.kl8techgroup.kl8wall/.kiosk.KioskDeviceAdminReceiver`
 *
 * This receiver has no custom logic — it exists solely to satisfy
 * the Android Device Owner API requirements.
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
