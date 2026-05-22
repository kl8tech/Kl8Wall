package cloud.kl8techgroup.kl8wall.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import cloud.kl8techgroup.kl8wall.KL8WallApplication

/**
 * BroadcastReceiver triggered by PackageInstaller after silent installation.
 */
class OtaUpdateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_OTA_STATUS = "cloud.kl8techgroup.kl8wall.OTA_INSTALL_STATUS"
        private const val TAG = "OtaUpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_OTA_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "No status message"
        Log.i(TAG, "Received OTA status broadcast: status=$status, message=$message")

        val app = context.applicationContext as? KL8WallApplication ?: return
        val otaManager = app.otaManager

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "OTA installation completed successfully! System will restart the app.")
            }
            else -> {
                Log.e(TAG, "OTA installation failed with status $status ($message)")
                otaManager.resetUpdatingState("Installation failed: $message")
            }
        }
    }
}
