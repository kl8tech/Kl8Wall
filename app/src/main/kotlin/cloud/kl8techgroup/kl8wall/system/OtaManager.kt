package cloud.kl8techgroup.kl8wall.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import cloud.kl8techgroup.kl8wall.MainActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Manages Over-The-Air app update downloads and package installation.
 */
class OtaManager(private val context: Context) {

    companion object {
        private const val TAG = "OtaManager"
        private const val UPDATE_URL = "https://raw.githubusercontent.com/kl8tech/Kl8Wall/master/update.json"
        private const val APK_TEMP_NAME = "kl8wall_ota.apk"
    }

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    private val _latestVersion = MutableStateFlow("")
    val latestVersion: StateFlow<String> = _latestVersion.asStateFlow()

    private val _latestVersionCode = MutableStateFlow(0)
    val latestVersionCode: StateFlow<Int> = _latestVersionCode.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError.asStateFlow()

    private val _updateProgress = MutableStateFlow<Int?>(null)
    val updateProgress: StateFlow<Int?> = _updateProgress.asStateFlow()

    private val _isInstallingInteractive = MutableStateFlow(false)
    val isInstallingInteractive: StateFlow<Boolean> = _isInstallingInteractive.asStateFlow()

    fun resetInstallingInteractive() {
        _isInstallingInteractive.value = false
    }

    private var latestApkUrl: String? = null

    val currentVersionName: String
        get() = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

    val currentVersionCode: Int
        get() = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (packageInfo.longVersionCode and 0xFFFFFFFFL).toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            0
        }

    private val isDeviceOwner: Boolean
        get() = try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            dpm?.isDeviceOwnerApp(context.packageName) ?: false
        } catch (e: Exception) {
            false
        }

    suspend fun checkForUpdates(forceInstall: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for updates at $UPDATE_URL...")
            val url = URL(UPDATE_URL)
            val connection = url.openConnection() as HttpURLConnection
            if (connection is javax.net.ssl.HttpsURLConnection) {
                connection.sslSocketFactory = SslUtil.tlsSocketFactory
            }
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            connection.useCaches = false

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${connection.responseCode}")
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val serverVersionCode = json.getInt("versionCode")
            val serverVersionName = if (json.has("version")) json.getString("version") else json.getString("versionName")
            val apkUrl = if (json.has("url")) json.getString("url") else json.getString("apkUrl")

            Log.i(TAG, "Server version info: code=$serverVersionCode, name=$serverVersionName, currentCode=$currentVersionCode")

            _latestVersion.value = serverVersionName
            _latestVersionCode.value = serverVersionCode
            latestApkUrl = apkUrl

            val updateAvailable = serverVersionCode > currentVersionCode
            _updateAvailable.value = updateAvailable

            if (updateAvailable && forceInstall) {
                downloadAndInstallApk(apkUrl)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Check for updates failed", e)
            _updateError.value = e.message ?: "Network error"
            false
        }
    }

    suspend fun triggerUpdate(): Boolean {
        val apkUrl = latestApkUrl
        if (apkUrl.isNullOrBlank()) {
            _updateError.value = "No update URL available. Please check for updates first."
            return false
        }
        return downloadAndInstallApk(apkUrl)
    }

    private suspend fun downloadAndInstallApk(apkUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (_isUpdating.value) {
            Log.w(TAG, "An update process is already running")
            return@withContext false
        }
        _isUpdating.value = true
        _updateError.value = null
        _updateProgress.value = 0
        try {
            Log.i(TAG, "Downloading APK from $apkUrl...")
            val url = URL(apkUrl)
            val connection = url.openConnection() as HttpURLConnection
            if (connection is javax.net.ssl.HttpsURLConnection) {
                connection.sslSocketFactory = SslUtil.tlsSocketFactory
            }
            connection.connectTimeout = 15000
            connection.readTimeout = 30000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Download server returned ${connection.responseCode}")
            }

            val contentLength = connection.contentLength
            val tempFile = File(context.cacheDir, APK_TEMP_NAME)
            if (tempFile.exists()) {
                tempFile.delete()
            }

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalBytesRead * 100) / contentLength).toInt()
                            _updateProgress.value = percent.coerceIn(0, 100)
                        }
                    }
                }
            }
            Log.i(TAG, "APK downloaded to ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            _updateProgress.value = null

            installApk(tempFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "OTA Download or Installation failed", e)
            _updateError.value = e.message ?: "Failed to download update"
            _isUpdating.value = false
            _updateProgress.value = null
            false
        }
    }

    private fun installApk(apkFile: File) {
        if (isDeviceOwner) {
            Log.i(TAG, "Device Owner mode: initiating silent background installation")
            installSilently(apkFile)
        } else {
            Log.i(TAG, "Standard mode: prompting interactive package installation")
            installWithPrompt(apkFile)
            _isUpdating.value = false
        }
    }

    private fun installWithPrompt(apkFile: File) {
        try {
            _isInstallingInteractive.value = true
            
            // Wake the screen using a temporary WakeLock
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (powerManager != null) {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                    "KL8Wall::OtaWakeLock"
                )
                wakeLock.acquire(3000L)
            }

            // Launch MainActivity with extras
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("wake_for_ota", true)
                putExtra("ota_apk_path", apkFile.absolutePath)
            }
            context.startActivity(mainIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch interactive installer flow", e)
            _updateError.value = "Failed to launch installer UI"
            _isInstallingInteractive.value = false
        }
    }

    private fun installSilently(apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        var session: PackageInstaller.Session? = null
        try {
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            apkFile.inputStream().use { input ->
                session.openWrite("kl8wall_ota_install", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val intent = Intent(context, OtaUpdateReceiver::class.java).apply {
                action = OtaUpdateReceiver.ACTION_OTA_STATUS
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                456,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            session.commit(pendingIntent.intentSender)
            Log.i(TAG, "Silent update session committed successfully: sessionId=$sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Silent installation failed during staging", e)
            _updateError.value = "Silent install failed: ${e.message}"
            _isUpdating.value = false
            session?.abandon()
        }
    }

    fun resetUpdatingState(error: String) {
        _isUpdating.value = false
        _updateError.value = error
    }
}
