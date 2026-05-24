package cloud.kl8techgroup.kl8wall.server

/**
 * Contract for Activity-scoped device control operations.
 *
 * Implemented by [MainActivity][cloud.kl8techgroup.kl8wall.MainActivity]
 * and consumed by [KL8WallHttpServer] to serve REST commands.
 * Read methods are thread-safe (backed by [StateFlow][kotlinx.coroutines.flow.StateFlow]).
 * Write methods post to the main looper internally.
 */
interface DeviceController {

    /** Whether the screen is currently on. Thread-safe read. */
    fun isScreenOn(): Boolean

    /** Current WebView URL. Thread-safe read. */
    fun getCurrentUrl(): String

    /** Current kiosk lock state name. Thread-safe read. */
    fun getLockState(): String

    /** Current brightness as a 0–100 percentage. Thread-safe read. */
    fun getBrightness(): Int

    /** Whether the app has WRITE_SETTINGS permission. Thread-safe read. */
    fun canWriteSettings(): Boolean

    /** Turn the screen on. Posts to main thread. */
    fun screenOn()

    /** Turn the screen off. Posts to main thread. */
    fun screenOff()

    /** Navigate the WebView to [url]. Posts to main thread. */
    fun navigate(url: String)

    /** Reload the current WebView page. Posts to main thread. */
    fun reload()

    /** Set screen brightness to [percent] (0–100). Returns false if no permission. */
    fun setBrightness(percent: Int): Boolean

    /** Speak [text] via the TTS engine. */
    fun speak(text: String)

    /** Stop any in-progress TTS speech. */
    fun stopSpeaking()

    /** Show the settings sheet. */
    fun openSettings()

    /** Close the settings sheet if open. */
    fun closeSettings()

    // --- New getters for diagnostics and settings controls ---

    /** Get current battery level as a percentage (0 to 100). */
    fun getBatteryLevel(): Float

    /** Get battery temperature in degrees Celsius. */
    fun getBatteryTemp(): Float

    /** Get battery status string (e.g. "charging", "discharging", "full", "unknown"). */
    fun getBatteryState(): String

    /** Get Wi-Fi RSSI in dBm. */
    fun getWifiRssi(): Int

    /** Get Wi-Fi SSID. */
    fun getWifiSsid(): String

    /** Get system RAM usage as a percentage (0 to 100). */
    fun getRamUsagePercent(): Float

    /** Get free disk storage in GB. */
    fun getStorageFreeGb(): Float

    /** Get device local IP address. */
    fun getIpAddress(): String

    /** Get the app version name. */
    fun getAppVersion(): String

    /** Get app uptime in seconds since launch. */
    fun getUptimeSeconds(): Long

    /** Get current TTS speaker volume percentage (0 to 100). */
    fun getTtsVolume(): Int

    /** Set TTS speaker volume percentage (0 to 100). */
    fun setTtsVolume(volume: Int)

    /** Get the screen presence/off timeout in seconds. */
    fun getScreenTimeoutSeconds(): Int

    /** Set the screen presence/off timeout in seconds. */
    fun setScreenTimeoutSeconds(seconds: Int)

    /** Reboot the application (relaunch process/activity). */
    fun rebootApp()

    /** Get latest ambient light sensor reading in lux. */
    fun getAmbientLight(): Float

    /** Get latest proximity sensor reading in cm. */
    fun getProximity(): Float

    /** Get latest pressure sensor reading in hPa. */
    fun getPressure(): Float

    /** Get latest ambient temperature sensor reading in °C. */
    fun getAmbientTemp(): Float

    /** Get latest relative humidity sensor reading in %. */
    fun getHumidity(): Float

    /** Clear WebView cache and WebStorage data. */
    fun clearCache()
}
