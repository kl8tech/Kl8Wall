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
}
