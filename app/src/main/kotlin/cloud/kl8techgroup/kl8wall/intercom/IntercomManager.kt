package cloud.kl8techgroup.kl8wall.intercom

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles recording from the microphone and streaming to MQTT (TX),
 * as well as receiving audio from MQTT and playing it back (RX).
 *
 * Implements a half-duplex walkie-talkie mode (TX suppresses RX) to avoid
 * painful acoustic feedback loops.
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

    private val isRecording = AtomicBoolean(false)
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)

    var onStateChanged: ((isRecording: Boolean) -> Unit)? = null

    val isRecordingActive: Boolean
        get() = isRecording.get()

    private var rxTimeoutJob: Job? = null
    private val RX_TIMEOUT_MS = 1000L

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
        
        // Stop any active playback to prevent feedback
        stopPlayback()

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
        // If we are actively talking (recording), discard incoming audio to prevent feedback screech
        if (isRecording.get()) {
            return
        }

        // Lazy initialize audio track
        var track = audioTrack
        if (track == null) {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
            val trackBufSize = Math.max(minBuf, CHUNK_SIZE * 4)

            track = try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
            kotlinx.coroutines.delay(RX_TIMEOUT_MS)
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
