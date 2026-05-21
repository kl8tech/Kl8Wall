package cloud.kl8techgroup.kl8wall.system

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Wraps the Android [TextToSpeech] engine for the REST API.
 *
 * Initialisation is asynchronous — [speak] silently no-ops until the
 * engine is ready. Call [shutdown] when the controller is no longer
 * needed to release TTS resources.
 */
class TtsController(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            ready = true
        }
    }

    /** Speak [text]. No-ops if the engine is not yet initialised. */
    fun speak(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Stop any in-progress speech. */
    fun stopSpeaking() {
        if (ready) tts.stop()
    }

    /** Release TTS resources. The controller is unusable after this call. */
    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
    }

    companion object {
        private const val UTTERANCE_ID = "kl8wall_tts"
    }
}
