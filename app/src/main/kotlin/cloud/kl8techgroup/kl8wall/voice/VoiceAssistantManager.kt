package cloud.kl8techgroup.kl8wall.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.media.ToneGenerator
import android.media.AudioManager
import cloud.kl8techgroup.kl8wall.KL8WallApplication
import cloud.kl8techgroup.kl8wall.system.SslUtil
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * States of the Voice Assistant.
 */
enum class VoiceState {
    IDLE,
    LISTENING_FOR_WAKEWORD,
    LISTENING_FOR_COMMAND,
    PROCESSING_COMMAND,
    SPEAKING_RESPONSE
}

/**
 * Manages the local wake-word detection and local Home Assistant Assist voice pipeline.
 * Runs 100% locally on Android using native SpeechRecognizer and local HTTP API request.
 */
class VoiceAssistantManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "VoiceAssistantManager"
        private const val WAKEWORD_TIMEOUT_MS = 6000L
        private const val COMMAND_TIMEOUT_MS = 6000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _userSpokenText = MutableStateFlow("")
    val userSpokenText: StateFlow<String> = _userSpokenText.asStateFlow()

    private val _assistantResponseText = MutableStateFlow("")
    val assistantResponseText: StateFlow<String> = _assistantResponseText.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val toneGenerator = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
    } catch (e: Exception) {
        null
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isMuted = false
    private var isWakeWordMode = true

    private var isStarted = false
    private var isListening = false
    private var timeoutJob: Job? = null

    /**
     * Start the voice assistant wake-word listening loop.
     */
    fun start() {
        if (isStarted) return
        isStarted = true
        Log.i(TAG, "Starting VoiceAssistantManager...")
        startWakeWordListening()
    }

    /**
     * Stop the voice assistant listening loop and release resources.
     */
    fun stop() {
        if (!isStarted) return
        isStarted = false
        Log.i(TAG, "Stopping VoiceAssistantManager...")
        cancelTimeout()
        scope.launch(Dispatchers.Main) {
            destroyRecognizer()
            _state.value = VoiceState.IDLE
        }
    }

    private fun muteSystemSounds() {
        if (isMuted) return
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to mute STREAM_SYSTEM (DND policy?)", e)
        } catch (e: Exception) {
            Log.w(TAG, "Error muting STREAM_SYSTEM", e)
        }
        
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to mute STREAM_NOTIFICATION (DND policy?)", e)
        } catch (e: Exception) {
            Log.w(TAG, "Error muting STREAM_NOTIFICATION", e)
        }

        try {
            if (!audioManager.isMusicActive) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error muting STREAM_MUSIC", e)
        }
        isMuted = true
    }

    private fun unmuteSystemSounds() {
        if (!isMuted) return
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error unmuting STREAM_SYSTEM", e)
        }
        
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error unmuting STREAM_NOTIFICATION", e)
        }

        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error unmuting STREAM_MUSIC", e)
        }
        isMuted = false
    }

    private fun startWakeWordListening() {
        if (!isStarted) return
        scope.launch(Dispatchers.Main) {
            cancelTimeout()
            _state.value = VoiceState.LISTENING_FOR_WAKEWORD
            _userSpokenText.value = ""
            _assistantResponseText.value = ""
            
            isWakeWordMode = true
            initRecognizer()
            
            val intent = createSpeechIntent()
            
            try {
                muteSystemSounds()
                isListening = true
                speechRecognizer?.cancel() // Clear previous binder state
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "SpeechRecognizer started listening for wake word...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening for wake word", e)
                unmuteSystemSounds()
                restartWakeWordListeningDelayed()
            }
        }
    }

    private fun startCommandListening() {
        scope.launch(Dispatchers.Main) {
            cancelTimeout()
            _state.value = VoiceState.LISTENING_FOR_COMMAND
            _userSpokenText.value = ""
            _assistantResponseText.value = ""

            // Wake screen so the overlay is visible
            wakeScreen()

            // Play clean double beep tone acknowledgment (unmuted so user hears it)
            unmuteSystemSounds()
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 120)

            delay(200) // Short delay to let the beep finish playing before opening mic

            isWakeWordMode = false
            initRecognizer()

            val intent = createSpeechIntent()

            try {
                muteSystemSounds()
                isListening = true
                speechRecognizer?.cancel() // Clear previous binder state
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "SpeechRecognizer started listening for command...")

                // Schedule timeout in case user doesn't say anything
                scheduleTimeout(COMMAND_TIMEOUT_MS) {
                    Log.i(TAG, "Command listening timed out.")
                    unmuteSystemSounds()
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 150)
                    startWakeWordListening()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening for command", e)
                unmuteSystemSounds()
                startWakeWordListening()
            }
        }
    }

    private fun initRecognizer() {
        if (speechRecognizer != null) return
        
        Log.i(TAG, "Initializing persistent SpeechRecognizer...")
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Recognizer ready for speech (isWakeWordMode = $isWakeWordMode)")
                unmuteSystemSounds() // Unmute system sounds after system start-listening beep passes
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "User started speaking")
                cancelTimeout()
                unmuteSystemSounds()
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                unmuteSystemSounds()
            }

            override fun onError(error: Int) {
                val errorMsg = getErrorMessage(error)
                Log.w(TAG, "Speech recognizer error: $errorMsg (code $error)")
                isListening = false
                unmuteSystemSounds()
                
                if (isWakeWordMode) {
                    restartWakeWordListeningDelayed()
                } else {
                    startWakeWordListening()
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                unmuteSystemSounds()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                Log.i(TAG, "Speech recognizer final results: $text")

                if (isWakeWordMode) {
                    handleWakeWordResult(text, isFinal = true)
                } else {
                    handleCommandResult(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    if (isWakeWordMode) {
                        Log.d(TAG, "Speech recognizer partial results: $text")
                        handleWakeWordResult(text, isFinal = false)
                    } else {
                        _userSpokenText.value = text
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer = recognizer
    }

    private fun destroyRecognizer() {
        unmuteSystemSounds()
        speechRecognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying SpeechRecognizer", e)
            }
        }
        speechRecognizer = null
        isListening = false
    }

    private fun handleWakeWordResult(text: String, isFinal: Boolean) {
        val configuredWakeWord = settingsRepository.voiceWakeWord.value.lowercase(Locale.getDefault()).trim()
        val detected = text.lowercase(Locale.getDefault())

        if (configuredWakeWord.isNotEmpty() && detected.contains(configuredWakeWord)) {
            Log.i(TAG, "Wake word matched: '$configuredWakeWord' detected in '$detected' (isFinal = $isFinal)")
            speechRecognizer?.cancel()
            isListening = false
            startCommandListening()
        } else if (isFinal) {
            Log.i(TAG, "Final wake word check finished (no match), restarting loop...")
            restartWakeWordListeningDelayed()
        }
    }

    private fun handleCommandResult(text: String) {
        if (text.isBlank()) {
            startWakeWordListening()
            return
        }

        _userSpokenText.value = text
        _state.value = VoiceState.PROCESSING_COMMAND

        scope.launch(Dispatchers.IO) {
            val responseText = processVoiceCommandWithHa(text)
            
            withContext(Dispatchers.Main) {
                _assistantResponseText.value = responseText
                _state.value = VoiceState.SPEAKING_RESPONSE
                
                val app = context.applicationContext as? KL8WallApplication
                app?.ttsController?.speak(responseText)

                // Wait for TTS/display overlay to show, then return to wake-word listening
                delay(4000)
                startWakeWordListening()
            }
        }
    }

    private fun restartWakeWordListeningDelayed() {
        scope.launch(Dispatchers.Main) {
            delay(1000)
            startWakeWordListening()
        }
    }

    private fun createSpeechIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server-side error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input timeout"
            else -> "Unknown error"
        }
    }

    private fun scheduleTimeout(delayMs: Long, onTimeout: () -> Unit) {
        cancelTimeout()
        timeoutJob = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            onTimeout()
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun wakeScreen() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (powerManager != null) {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                    "KL8Wall::VoiceWakeLock"
                )
                wakeLock.acquire(5000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen for voice assistant", e)
        }
    }

    private suspend fun processVoiceCommandWithHa(command: String): String = withContext(Dispatchers.IO) {
        var startUrl = settingsRepository.startUrl.value
        if (startUrl.isBlank()) {
            return@withContext "I cannot process commands because the Home Assistant start URL is not configured."
        }
        
        if (!startUrl.contains("://")) {
            startUrl = "http://$startUrl"
        }

        val baseUrl = try {
            val uri = URI(startUrl)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return@withContext "Failed to resolve Home Assistant address."
            val port = uri.port
            val portSuffix = if (port != -1) ":$port" else ""
            "$scheme://$host$portSuffix"
        } catch (e: Exception) {
            return@withContext "Home Assistant URL is malformed."
        }

        val token = settingsRepository.getHaToken()
        if (token.isBlank()) {
            return@withContext "Please configure your Home Assistant access token in Settings first."
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/api/conversation/process")
            connection = url.openConnection() as HttpURLConnection
            if (connection is javax.net.ssl.HttpsURLConnection) {
                connection.sslSocketFactory = SslUtil.tlsSocketFactory
            }
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

            val jsonBody = JSONObject().apply {
                put("text", command)
            }

            connection.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { writer ->
                    writer.write(jsonBody.toString())
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val speechObj = json.optJSONObject("response")?.optJSONObject("speech")?.optJSONObject("plain")
                val responseText = speechObj?.optString("speech", "") ?: ""
                
                if (responseText.isNotBlank()) {
                    return@withContext responseText
                }
                return@withContext "Command executed."
            } else {
                val errorMsg = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
                Log.e(TAG, "HA conversation API failed (HTTP $responseCode): $errorMsg")
                return@withContext "Home Assistant returned an error code $responseCode."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to Home Assistant conversation API", e)
            return@withContext "Could not connect to Home Assistant. Please check your network."
        } finally {
            connection?.disconnect()
        }
    }
}
