package cloud.kl8techgroup.kl8wall.cast

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CastManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _castUrl = MutableStateFlow<String?>(null)
    val castUrl: StateFlow<String?> = _castUrl.asStateFlow()

    private val _playbackState = MutableStateFlow("IDLE")
    val playbackState: StateFlow<String> = _playbackState.asStateFlow()

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()

    private val _position = MutableStateFlow(0)
    val position: StateFlow<Int> = _position.asStateFlow()

    private val _volume = MutableStateFlow(getCurrentSystemVolumePercent())
    val volume: StateFlow<Int> = _volume.asStateFlow()

    companion object {
        private const val TAG = "CastManager"
    }

    fun cast(url: String) {
        Log.i(TAG, "Casting URL: $url")
        _castUrl.value = url
        _playbackState.value = "PLAYING"
        _position.value = 0
        _duration.value = 0
    }

    fun play() {
        Log.i(TAG, "Play command received")
        if (_castUrl.value != null) {
            _playbackState.value = "PLAYING"
        }
    }

    fun pause() {
        Log.i(TAG, "Pause command received")
        if (_castUrl.value != null) {
            _playbackState.value = "PAUSED"
        }
    }

    fun stop() {
        Log.i(TAG, "Stop command received")
        _castUrl.value = null
        _playbackState.value = "IDLE"
        _position.value = 0
        _duration.value = 0
    }

    fun seek(seconds: Int) {
        Log.i(TAG, "Seek command received: $seconds seconds")
        _position.value = seconds
    }

    fun setVolume(percent: Int) {
        Log.i(TAG, "Setting volume to $percent%")
        val clampedPercent = percent.coerceIn(0, 100)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = (clampedPercent * maxVolume / 100)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
        _volume.value = clampedPercent
    }

    fun updatePlaybackState(state: String) {
        _playbackState.value = state
    }

    fun updateDuration(seconds: Int) {
        _duration.value = seconds
    }

    fun updatePosition(seconds: Int) {
        _position.value = seconds
    }

    fun updateVolume(percent: Int) {
        _volume.value = percent
    }

    private fun getCurrentSystemVolumePercent(): Int {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (current * 100 / max) else 0
    }
}
