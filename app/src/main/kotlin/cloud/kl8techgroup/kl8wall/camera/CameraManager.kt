package cloud.kl8techgroup.kl8wall.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.FaceDetector
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import cloud.kl8techgroup.kl8wall.mqtt.MqttManager
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraManager(
    private val context: Context,
    @field:Volatile private var lifecycleOwner: androidx.lifecycle.LifecycleOwner?,
    private val settingsRepository: SettingsRepository,
    private val mqttManager: MqttManager
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var periodicJob: Job? = null
    private var streamingJob: Job? = null

    private var isBound = false
    private var activeImageCapture: ImageCapture? = null
    private var activeCameraProvider: ProcessCameraProvider? = null

    var isStreamingEnabled = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    startStreaming()
                } else {
                    stopStreaming()
                }
            }
        }

    // Callback to notify face detection/presence
    var onFaceDetected: ((Boolean) -> Unit)? = null
    
    // Store the latest captured photo for native ESPHome requests
    var latestPhotoBytes: ByteArray? = null

    fun start() {
        Log.d(TAG, "Starting CameraManager...")
        
        // Listen to camera snapshot commands from MQTT
        mqttManager.onSnapshotTrigger = {
            takeSnapshot()
        }

        // Monitor settings for changes to periodic capture
        scope.launch {
            settingsRepository.presenceSensorEnabled.collectLatest { enabled ->
                if (enabled) {
                    settingsRepository.cameraIntervalMinutes.collectLatest { interval ->
                        startPeriodicCapture(interval)
                    }
                } else {
                    stopPeriodicCapture()
                }
            }
        }

        // Listen to low power mode flow to throttle camera usage
        scope.launch {
            val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication
            // Wait for presenceSensorManager to be initialized
            while (app?.presenceSensorManager == null && isActive) {
                delay(100)
            }
            app?.presenceSensorManager?.isLowPowerMode?.collect { isLowPower ->
                if (isLowPower) {
                    Log.i(TAG, "Low power mode activated. Suspending camera streaming and periodic capture.")
                    stopPeriodicCapture()
                    stopStreaming()
                } else {
                    Log.i(TAG, "Low power mode deactivated. Resuming camera streaming and periodic capture if needed.")
                    if (isStreamingEnabled) {
                        startStreaming()
                    }
                    if (settingsRepository.presenceSensorEnabled.value) {
                        startPeriodicCapture(settingsRepository.cameraIntervalMinutes.value)
                    }
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping CameraManager...")
        stopPeriodicCapture()
        stopStreaming()
        scope.cancel()
        cameraExecutor.shutdown()
    }

    fun updateLifecycleOwner(newOwner: androidx.lifecycle.LifecycleOwner) {
        if (this.lifecycleOwner != newOwner) {
            Log.i(TAG, "Updating CameraManager lifecycleOwner reference")
            this.lifecycleOwner = newOwner
            if (isBound) {
                unbindCamera()
            }
        }
    }

    fun clearLifecycleOwner() {
        Log.i(TAG, "Clearing CameraManager lifecycleOwner")
        this.lifecycleOwner = null
        unbindCamera()
    }

    private fun isLowPowerMode(): Boolean {
        val app = context.applicationContext as? cloud.kl8techgroup.kl8wall.KL8WallApplication
        return app?.presenceSensorManager?.isLowPowerMode?.value ?: false
    }

    private fun startStreaming() {
        stopStreaming()
        if (isLowPowerMode()) {
            Log.i(TAG, "Low power mode is active. Deferring camera streaming.")
            return
        }
        Log.i(TAG, "Starting front camera live streaming")
        streamingJob = scope.launch {
            try {
                ensureCameraBound()
                while (isActive) {
                    captureFrame()
                    delay(1000L) // 1 frame per second
                }
            } finally {
                checkCameraRelease()
            }
        }
    }

    private fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        Log.i(TAG, "Stopped front camera live streaming")
        checkCameraRelease()
    }

    private fun startPeriodicCapture(intervalMinutes: Int) {
        stopPeriodicCapture()
        if (intervalMinutes <= 0) return
        if (isLowPowerMode()) {
            Log.i(TAG, "Low power mode is active. Deferring periodic capture.")
            return
        }

        Log.i(TAG, "Starting periodic front camera snapshot every $intervalMinutes minutes")
        periodicJob = scope.launch {
            // Wait 1 minute before first snapshot to allow system to settle
            delay(60 * 1000)
            try {
                ensureCameraBound()
                while (isActive) {
                    captureFrame()
                    delay(intervalMinutes * 60 * 1000L)
                }
            } finally {
                checkCameraRelease()
            }
        }
    }

    private fun stopPeriodicCapture() {
        periodicJob?.cancel()
        periodicJob = null
        checkCameraRelease()
    }

    private suspend fun ensureCameraBound(): ImageCapture? = withContext(Dispatchers.Main) {
        val owner = lifecycleOwner ?: run {
            Log.w(TAG, "Cannot bind camera: lifecycleOwner is null (activity backgrounded/destroyed)")
            return@withContext null
        }
        if (activeImageCapture != null && isBound) {
            return@withContext activeImageCapture
        }
        val provider = activeCameraProvider ?: suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val p = future.get()
                    activeCameraProvider = p
                    continuation.resume(p)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }

        try {
            provider.unbindAll()
            @Suppress("DEPRECATION")
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(android.util.Size(1024, 768))
                .build()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            provider.bindToLifecycle(owner, cameraSelector, imageCapture)
            activeImageCapture = imageCapture
            isBound = true
            imageCapture
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use case", e)
            null
        }
    }

    private fun unbindCamera() {
        ContextCompat.getMainExecutor(context).execute {
            try {
                activeCameraProvider?.unbindAll()
                activeImageCapture = null
                isBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera", e)
            }
        }
    }

    private suspend fun captureFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        val imageCapture = activeImageCapture
        if (imageCapture == null || !isBound) {
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    Log.i(TAG, "Frame captured successfully")
                    processImageProxy(imageProxy)
                    continuation.resume(Unit)
                }

                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                    Log.e(TAG, "Frame capture failed", exception)
                    continuation.resume(Unit)
                }
            }
        )
    }

    private fun checkCameraRelease() {
        if (!isStreamingEnabled && periodicJob == null) {
            unbindCamera()
        }
    }

    fun takeSnapshot() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted. Cannot take snapshot.")
            return
        }
        scope.launch {
            val bound = ensureCameraBound() != null
            if (bound) {
                captureFrame()
                checkCameraRelease()
            }
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            // Extract raw bytes (default output format of ImageCapture is JPEG)
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            imageProxy.close()

            // Decode, scale down, and compress to prevent MQTT connection reset
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                val scaledBitmap = scaleBitmap(bitmap, 1024, 768)
                val outStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outStream)
                val compressedBytes = outStream.toByteArray()
                latestPhotoBytes = compressedBytes
                Log.i(TAG, "Image processed: originalSize=${bytes.size} bytes, newSize=${compressedBytes.size} bytes")
                
                mqttManager.publishCameraImage(compressedBytes)

                val faces = detectFaces(scaledBitmap)
                if (faces > 0) {
                    Log.i(TAG, "Face detected! Notifying presence.")
                    onFaceDetected?.invoke(true)
                }

                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
                bitmap.recycle()
            } else {
                Log.w(TAG, "Failed to decode captured JPEG bytes. Publishing raw bytes.")
                mqttManager.publishCameraImage(bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing captured image", e)
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxW && height <= maxH) return bitmap
        
        val ratio = width.toFloat() / height.toFloat()
        var newWidth = maxW
        var newHeight = (maxW / ratio).toInt()
        
        if (newHeight > maxH) {
            newHeight = maxH
            newWidth = (maxH * ratio).toInt()
        }
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun detectFaces(bitmap: Bitmap): Int {
        return try {
            // FaceDetector requires RGB_565 format
            val rgb565 = bitmap.copy(Bitmap.Config.RGB_565, true)
            
            // Scaledown image for fast processing
            val maxDimension = 320
            val width = rgb565.width
            val height = rgb565.height
            val scale = maxDimension.toFloat() / Math.max(width, height)
            
            val scaledWidth = (width * scale).toInt().let { if (it % 2 == 0) it else it - 1 }
            val scaledHeight = (height * scale).toInt()
            
            val scaledBitmap = Bitmap.createScaledBitmap(rgb565, scaledWidth, scaledHeight, true)
            val formatBitmap = scaledBitmap.copy(Bitmap.Config.RGB_565, true)
            
            val maxFaces = 5
            val facesArray = arrayOfNulls<FaceDetector.Face>(maxFaces)
            val detector = FaceDetector(formatBitmap.width, formatBitmap.height, maxFaces)
            
            detector.findFaces(formatBitmap, facesArray)
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed", e)
            0
        }
    }
}
