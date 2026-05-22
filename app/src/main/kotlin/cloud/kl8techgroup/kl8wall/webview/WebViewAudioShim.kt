package cloud.kl8techgroup.kl8wall.webview

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WebViewAudioShim(webView: WebView) {

    private val webViewRef = WeakReference(webView)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null
    
    companion object {
        private const val TAG = "WebViewAudioShim"
        private const val INPUT_SAMPLE_RATE = 16000
    }

    @JavascriptInterface
    fun startRecording(browserSampleRate: Int) {
        Log.i(TAG, "startRecording called with browserSampleRate=$browserSampleRate")
        if (isRecording) {
            stopRecording()
        }
        
        isRecording = true
        recordingThread = Thread {
            recordLoop(browserSampleRate)
        }.apply {
            name = "KL8WallAudioRecordThread"
            start()
        }
    }

    @JavascriptInterface
    fun stopRecording() {
        Log.i(TAG, "stopRecording called")
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop(targetSampleRate: Int) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size for AudioRecord")
            return
        }

        val bufferSize = (INPUT_SAMPLE_RATE * 0.1 * 2).toInt().coerceAtLeast(minBufferSize)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for audio recording", e)
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            audioRecord.release()
            return
        }

        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start AudioRecord recording", e)
            audioRecord.release()
            return
        }

        val readBuffer = ShortArray(1024)
        Log.d(TAG, "Audio recording started successfully")

        while (isRecording && !Thread.currentThread().isInterrupted) {
            val readResult = audioRecord.read(readBuffer, 0, readBuffer.size)
            if (readResult > 0) {
                val pcmData = readBuffer.copyOfRange(0, readResult)
                val resampledData = resample(pcmData, INPUT_SAMPLE_RATE, targetSampleRate)
                
                val byteBuffer = ByteBuffer.allocate(resampledData.size * 2).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    asShortBuffer().put(resampledData)
                }
                
                val base64Data = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
                
                mainHandler.post {
                    val webView = webViewRef.get()
                    if (webView != null && isRecording) {
                        webView.evaluateJavascript("if (window.__kl8wall_on_audio_data) { window.__kl8wall_on_audio_data('$base64Data'); }", null)
                    }
                }
            } else if (readResult < 0) {
                Log.e(TAG, "AudioRecord read error: $readResult")
                break
            }
        }

        try {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        } catch (_: Exception) {}
        
        audioRecord.release()
        Log.d(TAG, "Audio recording stopped and resource released")
    }

    private fun resample(input: ShortArray, inputSampleRate: Int, targetSampleRate: Int): ShortArray {
        if (inputSampleRate == targetSampleRate) return input
        val ratio = inputSampleRate.toDouble() / targetSampleRate.toDouble()
        val outputLength = (input.size / ratio).toInt()
        val output = ShortArray(outputLength)
        for (i in 0 until outputLength) {
            val inputIndex = i * ratio
            val indexFloor = inputIndex.toInt()
            val fraction = inputIndex - indexFloor
            val sample1 = input[indexFloor]
            val sample2 = if (indexFloor + 1 < input.size) input[indexFloor + 1] else sample1
            output[i] = ((1 - fraction) * sample1 + fraction * sample2).toInt().toShort()
        }
        return output
    }
}
