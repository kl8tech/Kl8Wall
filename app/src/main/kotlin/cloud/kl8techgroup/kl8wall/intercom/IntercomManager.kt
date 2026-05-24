package cloud.kl8techgroup.kl8wall.intercom

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.RingtoneManager
import android.media.Ringtone
import android.net.Uri
import android.media.ToneGenerator
import android.util.Log
import cloud.kl8techgroup.kl8wall.KL8WallApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * States of the phone-style Intercom session.
 */
enum class IntercomCallState {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    ACTIVE_CALL
}

/**
 * Handles recording from the microphone and streaming to MQTT (TX),
 * as well as receiving audio from MQTT and playing it back (RX).
 *
 * Implements a phone-style calling session with signaling, ringtones,
 * and hardware echo cancellation (AEC).
 */
class IntercomManager(
    private val context: Context,
    private val publishAudioChunk: (String, ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "IntercomManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 1280 // ~40ms at 16kHz 16-bit Mono (32000 bytes/sec)
    }

    private val _callState = MutableStateFlow(IntercomCallState.IDLE)
    val callState: StateFlow<IntercomCallState> = _callState.asStateFlow()

    private val _peerDevice = MutableStateFlow("")
    val peerDevice: StateFlow<String> = _peerDevice.asStateFlow()

    private val isRecording = AtomicBoolean(false)
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)

    var onStateChanged: ((isRecording: Boolean) -> Unit)? = null

    val isRecordingActive: Boolean
        get() = isRecording.get()

    private var rxTimeoutJob: Job? = null
    private val RX_TIMEOUT_MS = 1000L

    private val toneGenerator = try {
        ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
    } catch (e: Exception) {
        null
    }

    private var ringtone: Ringtone? = null
    private var ringbackJob: Job? = null
    private var callTimeoutJob: Job? = null

    private fun publishSignaling(target: String, topicSuffix: String, payload: JSONObject) {
        val app = context.applicationContext as? KL8WallApplication
        val mqtt = app?.mqttManager
        if (mqtt != null && mqtt.isConnected()) {
            val topic = "kl8wall/$target/call/$topicSuffix"
            mqtt.publishExternally(topic, payload.toString(), false)
        }
    }

    fun initiateCall(targetDevice: String) {
        if (_callState.value != IntercomCallState.IDLE) {
            Log.w(TAG, "Cannot initiate call, current state: ${_callState.value}")
            return
        }
        val app = context.applicationContext as? KL8WallApplication
        val thisDevice = app?.settingsRepository?.deviceName?.value ?: ""
        if (thisDevice.isBlank()) {
            Log.e(TAG, "Cannot initiate call: local device name is blank")
            return
        }

        Log.i(TAG, "Initiating call from $thisDevice to target=$targetDevice")
        _callState.value = IntercomCallState.OUTGOING_RINGING
        _peerDevice.value = targetDevice

        // Publish Invite
        val payload = JSONObject().apply {
            put("caller", thisDevice)
        }
        publishSignaling(targetDevice, "invite", payload)

        // Play ringback tone
        startRingback()

        // Start 30s timeout
        startCallTimeout {
            Log.i(TAG, "Outgoing call invite timed out. Cancelling.")
            cancelCallSignaling(targetDevice)
            resetToIdle()
        }
    }

    fun receiveInvite(callerDevice: String) {
        if (_callState.value != IntercomCallState.IDLE) {
            Log.i(TAG, "Busy: received invite from $callerDevice but state is ${_callState.value}")
            // Publish Busy/Decline
            val thisDevice = (context.applicationContext as? KL8WallApplication)?.settingsRepository?.deviceName?.value ?: ""
            val payload = JSONObject().apply {
                put("responder", thisDevice)
            }
            publishSignaling(callerDevice, "decline", payload)
            return
        }

        Log.i(TAG, "Received call invite from $callerDevice")
        _callState.value = IntercomCallState.INCOMING_RINGING
        _peerDevice.value = callerDevice

        // Start Ringtone
        startRingtone()

        // Start 30s timeout
        startCallTimeout {
            Log.i(TAG, "Incoming call invite timed out. Declining.")
            declineCall()
        }
    }

    fun acceptCall() {
        val target = _peerDevice.value
        if (_callState.value != IntercomCallState.INCOMING_RINGING || target.isEmpty()) {
            Log.w(TAG, "Cannot accept call: invalid state ${_callState.value}")
            return
        }

        Log.i(TAG, "Accepting call from $target")
        stopRingtone()
        cancelCallTimeout()

        // Publish Accept
        val thisDevice = (context.applicationContext as? KL8WallApplication)?.settingsRepository?.deviceName?.value ?: ""
        val payload = JSONObject().apply {
            put("responder", thisDevice)
        }
        publishSignaling(target, "accept", payload)

        _callState.value = IntercomCallState.ACTIVE_CALL

        // Start local recording and playback
        startAudioSession()
    }

    fun declineCall() {
        val target = _peerDevice.value
        if (_callState.value != IntercomCallState.INCOMING_RINGING || target.isEmpty()) {
            Log.w(TAG, "Cannot decline call: invalid state ${_callState.value}")
            return
        }

        Log.i(TAG, "Declining call from $target")
        stopRingtone()
        cancelCallTimeout()

        // Publish Decline
        val thisDevice = (context.applicationContext as? KL8WallApplication)?.settingsRepository?.deviceName?.value ?: ""
        val payload = JSONObject().apply {
            put("responder", thisDevice)
        }
        publishSignaling(target, "decline", payload)

        resetToIdle()
    }

    fun receiveAccept(responder: String) {
        if (_callState.value != IntercomCallState.OUTGOING_RINGING || _peerDevice.value != responder) {
            Log.w(TAG, "Received accept from $responder but state is ${_callState.value}")
            return
        }

        Log.i(TAG, "Call accepted by $responder")
        stopRingback()
        cancelCallTimeout()

        _callState.value = IntercomCallState.ACTIVE_CALL

        // Start local recording and playback
        startAudioSession()
    }

    fun receiveDecline(responder: String) {
        if ((_callState.value != IntercomCallState.OUTGOING_RINGING && _callState.value != IntercomCallState.ACTIVE_CALL) || _peerDevice.value != responder) {
            Log.w(TAG, "Received decline from $responder but state is ${_callState.value}")
            return
        }

        Log.i(TAG, "Call declined/ended by $responder")
        stopRingback()
        cancelCallTimeout()
        stopAudioSession()

        // Play busy signal
        toneGenerator?.startTone(ToneGenerator.TONE_SUP_BUSY, 800)

        resetToIdle()
    }

    fun hangUp() {
        val target = _peerDevice.value
        val state = _callState.value
        if (state == IntercomCallState.IDLE) return

        Log.i(TAG, "Hanging up active call with $target (state = $state)")
        stopRingtone()
        stopRingback()
        cancelCallTimeout()
        stopAudioSession()

        if (target.isNotEmpty()) {
            val thisDevice = (context.applicationContext as? KL8WallApplication)?.settingsRepository?.deviceName?.value ?: ""
            val payload = JSONObject().apply {
                put("sender", thisDevice)
            }
            publishSignaling(target, "hangup", payload)
        }

        resetToIdle()
    }

    fun receiveHangup(sender: String) {
        if (_peerDevice.value != sender) {
            Log.w(TAG, "Received hangup from $sender but current peer is ${_peerDevice.value}")
            return
        }

        Log.i(TAG, "Call hung up by remote sender $sender")
        stopRingtone()
        stopRingback()
        cancelCallTimeout()
        stopAudioSession()

        resetToIdle()
    }

    private fun startCallTimeout(onTimeout: () -> Unit) {
        cancelCallTimeout()
        callTimeoutJob = scope.launch {
            delay(30000) // 30 seconds
            withContext(Dispatchers.Main) {
                onTimeout()
            }
        }
    }

    private fun cancelCallTimeout() {
        callTimeoutJob?.cancel()
        callTimeoutJob = null
    }

    private fun startRingtone() {
        scope.launch(Dispatchers.Main) {
            try {
                if (ringtone == null) {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ringtone = RingtoneManager.getRingtone(context, uri)
                }
                ringtone?.play()
            } catch (e: Exception) {
                Log.e(TAG, "Error playing ringtone", e)
            }
        }
    }

    private fun stopRingtone() {
        scope.launch(Dispatchers.Main) {
            try {
                ringtone?.stop()
            } catch (_: Exception) {}
            ringtone = null
        }
    }

    private fun startRingback() {
        stopRingback()
        ringbackJob = scope.launch {
            while (isActive) {
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                    delay(1500)
                    toneGenerator?.stopTone()
                } catch (e: Exception) {
                    Log.w(TAG, "Error playing ringback tone", e)
                }
                delay(3000)
            }
        }
    }

    private fun stopRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
    }

    private fun resetToIdle() {
        _callState.value = IntercomCallState.IDLE
        _peerDevice.value = ""
    }

    private fun cancelCallSignaling(targetDevice: String) {
        val thisDevice = (context.applicationContext as? KL8WallApplication)?.settingsRepository?.deviceName?.value ?: ""
        val payload = JSONObject().apply {
            put("sender", thisDevice)
        }
        publishSignaling(targetDevice, "hangup", payload)
    }

    private fun startAudioSession() {
        val target = _peerDevice.value
        if (target.isNotEmpty()) {
            startRecording(target)
        }
    }

    private fun stopAudioSession() {
        stopRecording()
        stopPlayback()
    }

    /**
     * Starts recording the native microphone and sending chunks to [targetDevice].
     */
    @SuppressLint("MissingPermission")
    fun startRecording(targetDevice: String) {
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "Already recording. Ignoring start command.")
            return
        }

        Log.i(TAG, "Starting intercom recording target=$targetDevice")
        
        // Stop any active playback if not in bidirectional active call
        if (_callState.value != IntercomCallState.ACTIVE_CALL) {
            stopPlayback()
        }

        onStateChanged?.invoke(true)

        recordJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
            val recBufSize = Math.max(minBuf, CHUNK_SIZE * 2)

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AUDIO_FORMAT,
                    recBufSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioRecord", e)
                isRecording.set(false)
                onStateChanged?.invoke(false)
                return@launch
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord state is not initialized")
                audioRecord.release()
                isRecording.set(false)
                onStateChanged?.invoke(false)
                return@launch
            }

            // Enable hardware echo cancellation and noise suppression if available
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    val aec = AcousticEchoCanceler.create(audioRecord.audioSessionId)
                    aec?.enabled = true
                    Log.i(TAG, "AcousticEchoCanceler enabled on session ${audioRecord.audioSessionId}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable AcousticEchoCanceler", e)
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                try {
                    val ns = NoiseSuppressor.create(audioRecord.audioSessionId)
                    ns?.enabled = true
                    Log.i(TAG, "NoiseSuppressor enabled on session ${audioRecord.audioSessionId}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable NoiseSuppressor", e)
                }
            }

            try {
                audioRecord.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                audioRecord.release()
                isRecording.set(false)
                onStateChanged?.invoke(false)
                return@launch
            }

            val buffer = ByteArray(CHUNK_SIZE)
            while (isActive && isRecording.get()) {
                val read = audioRecord.read(buffer, 0, CHUNK_SIZE)
                if (read > 0) {
                    val payload = if (read == CHUNK_SIZE) buffer else buffer.copyOf(read)
                    publishAudioChunk(targetDevice, payload)
                } else if (read < 0) {
                    Log.e(TAG, "Error reading audio data: $read")
                    break
                }
            }

            try {
                audioRecord.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audioRecord", e)
            } finally {
                audioRecord.release()
            }
            Log.i(TAG, "Stopped intercom recording")
            isRecording.set(false)
            onStateChanged?.invoke(false)
        }
    }

    /**
     * Stops the active microphone recording.
     */
    fun stopRecording() {
        if (!isRecording.getAndSet(false)) return
        Log.i(TAG, "Stopping intercom recording...")
        recordJob?.cancel()
        recordJob = null
        onStateChanged?.invoke(false)
    }

    /**
     * Handles incoming binary audio data from another tablet.
     */
    fun handleIncomingAudio(audioBytes: ByteArray) {
        // Discard audio if we are recording in walkie-talkie mode (not in active phone call)
        if (isRecording.get() && _callState.value != IntercomCallState.ACTIVE_CALL) {
            return
        }

        // Lazy initialize audio track
        var track = audioTrack
        if (track == null) {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
            val trackBufSize = Math.max(minBuf, CHUNK_SIZE * 4)

            track = try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // Voice communication mode is optimized for calling (applies built-in hardware AEC)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .setEncoding(AUDIO_FORMAT)
                    .build()

                AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(trackBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioTrack", e)
                return
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack state is not initialized")
                track.release()
                return
            }

            audioTrack = track
        }

        if (!isPlaying.getAndSet(true)) {
            Log.i(TAG, "Starting audio track playback")
            try {
                track.play()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioTrack play", e)
                isPlaying.set(false)
                return
            }
        }

        try {
            track.write(audioBytes, 0, audioBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to AudioTrack", e)
        }

        // Reschedule automatic timeout to stop playback when streaming stops
        rxTimeoutJob?.cancel()
        rxTimeoutJob = scope.launch {
            delay(RX_TIMEOUT_MS)
            Log.i(TAG, "Audio stream timed out. Stopping playback.")
            stopPlayback()
        }
    }

    /**
     * Stops any current playback and releases the AudioTrack resources.
     */
    fun stopPlayback() {
        rxTimeoutJob?.cancel()
        rxTimeoutJob = null
        if (!isPlaying.getAndSet(false)) return
        Log.i(TAG, "Stopping audio track playback...")
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                track.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioTrack", e)
            } finally {
                track.release()
            }
        }
    }
}
