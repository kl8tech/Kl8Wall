package cloud.kl8techgroup.kl8wall.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import cloud.kl8techgroup.kl8wall.server.DeviceController
import cloud.kl8techgroup.kl8wall.mqtt.MqttManager
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

class PresenceSensorManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val mqttManager: MqttManager,
    private val deviceController: DeviceController
) : SensorEventListener {

    companion object {
        private const val TAG = "PresenceSensorManager"
        private const val LIGHT_THRESHOLD = 5.0f // lux difference to trigger presence
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var lastLux: Float? = null
    private var isPresent = false
    private var timeoutJob: Job? = null
    
    // Config values
    private var isEnabled = false
    private var timeoutSeconds = 30

    fun start() {
        Log.d(TAG, "Starting PresenceSensorManager...")
        
        // Listen to configuration changes
        scope.launch {
            settingsRepository.presenceSensorEnabled.collectLatest { enabled ->
                isEnabled = enabled
                if (enabled) {
                    registerSensors()
                } else {
                    unregisterSensors()
                    setPresence(false)
                }
            }
        }

        scope.launch {
            settingsRepository.presenceTimeoutSeconds.collectLatest { seconds ->
                timeoutSeconds = seconds
                if (isPresent) {
                    resetTimeout()
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping PresenceSensorManager...")
        unregisterSensors()
        timeoutJob?.cancel()
        scope.cancel()
    }

    private fun registerSensors() {
        Log.i(TAG, "Registering hardware sensors for presence detection")
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterSensors() {
        Log.i(TAG, "Unregistering hardware sensors")
        sensorManager.unregisterListener(this)
        lastLux = null
    }

    /**
     * Trigger presence from external events like screen touch.
     */
    fun onUserInteraction() {
        if (!isEnabled) return
        Log.d(TAG, "User activity (screen touch) detected")
        triggerPresence("touch")
    }

    /**
     * Trigger presence from face detection in camera snapshots.
     */
    fun onFaceDetected() {
        if (!isEnabled) return
        Log.i(TAG, "Face detection triggered presence event")
        triggerPresence("camera_face")
    }

    private fun triggerPresence(source: String) {
        scope.launch {
            Log.d(TAG, "Presence event triggered by: $source")
            setPresence(true)
            resetTimeout()
        }
    }

    private suspend fun setPresence(present: Boolean) {
        if (isPresent == present) return
        isPresent = present
        Log.i(TAG, "Presence state changed: $present")
        
        // Notify HA via MQTT
        mqttManager.publishPresenceState(present)

        // Wake screen on presence, or turn off on absence
        withContext(Dispatchers.Main) {
            if (present) {
                if (!deviceController.isScreenOn()) {
                    Log.i(TAG, "Waking up screen due to presence detection")
                    deviceController.screenOn()
                }
            } else {
                if (deviceController.isScreenOn()) {
                    Log.i(TAG, "Turning off screen due to presence timeout")
                    deviceController.screenOff()
                }
            }
        }
    }

    private fun resetTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutSeconds * 1000L)
            Log.i(TAG, "Presence timeout reached ($timeoutSeconds seconds)")
            setPresence(false)
        }
    }

    // --- SensorEventListener Overrides ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isEnabled || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val distance = event.values[0]
                val maxRange = event.sensor.maximumRange
                Log.d(TAG, "Proximity change: distance=$distance, maxRange=$maxRange")
                
                // Usually proximity sensor returns 0 for close, and maximumRange for far
                val isNear = distance < maxRange && distance < 10.0f // within 10cm
                if (isNear) {
                    triggerPresence("proximity")
                }
            }
            
            Sensor.TYPE_LIGHT -> {
                val currentLux = event.values[0]
                val last = lastLux
                Log.d(TAG, "Light sensor change: currentLux=$currentLux")
                
                if (last != null) {
                    val delta = abs(currentLux - last)
                    if (delta >= LIGHT_THRESHOLD) {
                        triggerPresence("ambient_light")
                    }
                }
                lastLux = currentLux
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
