package cloud.kl8techgroup.kl8wall.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import cloud.kl8techgroup.kl8wall.MainActivity

class KL8WallService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        releaseWifiLock()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "KL8Wall Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps KL8Wall services running reliably in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = android.R.drawable.ic_menu_info_details

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KL8Wall Active")
            .setContentText("Kiosk server and MQTT client running")
            .setSmallIcon(iconRes)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KL8Wall::BackgroundWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wifiManager != null) {
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                } else {
                    @Suppress("DEPRECATION")
                    android.net.wifi.WifiManager.WIFI_MODE_FULL
                }
                wifiLock = wifiManager.createWifiLock(lockType, "KL8Wall::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i("KL8WallService", "Acquired WifiLock type=$lockType")
            }
        } catch (e: Exception) {
            Log.e("KL8WallService", "Failed to acquire WifiLock", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wifiLock = null
        Log.i("KL8WallService", "Released WifiLock")
    }

    companion object {
        private const val CHANNEL_ID = "KL8WallServiceChannel"
        private const val NOTIFICATION_ID = 108
    }
}
