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

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val settingsRepository: SettingsRepository,
    private val mqttManager: MqttManager
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var periodicJob: Job? = null

    // Callback to notify face detection/presence
    var onFaceDetected: ((Boolean) -> Unit)? = null

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
    }

    fun stop() {
        Log.d(TAG, "Stopping CameraManager...")
        stopPeriodicCapture()
        scope.cancel()
        cameraExecutor.shutdown()
    }

    private fun startPeriodicCapture(intervalMinutes: Int) {
        stopPeriodicCapture()
        if (intervalMinutes <= 0) return

        Log.i(TAG, "Starting periodic front camera snapshot every $intervalMinutes minutes")
        periodicJob = scope.launch {
            // Wait 1 minute before first snapshot to allow system to settle
            delay(60 * 1000)
            while (isActive) {
                takeSnapshot()
                delay(intervalMinutes * 60 * 1000L)
            }
        }
    }

    private fun stopPeriodicCapture() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun takeSnapshot() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted. Cannot take snapshot.")
            return
        }

        cameraExecutor.execute {
            Log.d(TAG, "Initiating CameraX capture...")
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                    // Bind use cases to lifecycle (Main thread required for binding)
                    ContextCompat.getMainExecutor(context).execute {
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                            // Take picture
                            imageCapture.takePicture(
                                cameraExecutor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                        Log.i(TAG, "Image captured successfully")
                                        processImageProxy(imageProxy)
                                        
                                        // Immediately unbind to free camera hardware
                                        ContextCompat.getMainExecutor(context).execute {
                                            try {
                                                cameraProvider.unbindAll()
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error unbinding camera after capture", e)
                                            }
                                        }
                                    }

                                    override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                                        Log.e(TAG, "Image capture failed", exception)
                                        // Unbind on error too
                                        ContextCompat.getMainExecutor(context).execute {
                                            try {
                                                cameraProvider.unbindAll()
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error unbinding camera after error", e)
                                            }
                                        }
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to bind or capture image", e)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "ProcessCameraProvider initialization failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
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
