package cloud.kl8techgroup.kl8wall.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cloud.kl8techgroup.kl8wall.MainActivity

/**
 * Launches [MainActivity] when the device boots.
 *
 * Handles both [Intent.ACTION_BOOT_COMPLETED] and
 * [Intent.ACTION_LOCKED_BOOT_COMPLETED] for direct-boot-aware startup.
 * Contains no IPC logic beyond starting the main activity.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
