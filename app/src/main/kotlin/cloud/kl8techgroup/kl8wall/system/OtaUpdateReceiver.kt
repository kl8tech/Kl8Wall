package cloud.kl8techgroup.kl8wall.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
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
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "OTA installation requires user confirmation. Launching confirmation UI.")
                val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }
                if (confirmationIntent != null) {
                    otaManager.startConfirmationPrompt(confirmationIntent)
                } else {
                    Log.e(TAG, "Confirmation intent is null, cannot prompt user.")
                    otaManager.resetUpdatingState("Installation failed: No confirmation intent found")
                }
            }
            else -> {
                Log.e(TAG, "OTA installation failed with status $status ($message)")
                val apkPath = intent.getStringExtra("ota_apk_path")
                if (!apkPath.isNullOrBlank()) {
                    Log.i(TAG, "Falling back to interactive installation for APK: $apkPath")
                    val apkFile = java.io.File(apkPath)
                    if (apkFile.exists()) {
                        otaManager.installWithPrompt(apkFile)
                    } else {
                        Log.e(TAG, "Fallback failed: APK file does not exist at $apkPath")
                        otaManager.resetUpdatingState("Installation failed: $message (APK file missing)")
                    }
                } else {
                    otaManager.resetUpdatingState("Installation failed: $message")
                }
            }
        }
    }
}
