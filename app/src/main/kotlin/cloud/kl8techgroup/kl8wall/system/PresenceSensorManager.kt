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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private var ambientTempSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private var humiditySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)

    private var lastLux: Float? = null
    private var smoothedLux: Float? = null
    private var lastSetBrightness: Int? = null

    var isPresent = false
        private set
    private var timeoutJob: Job? = null

    private val _isLowPowerMode = MutableStateFlow(false)
    val isLowPowerMode: StateFlow<Boolean> = _isLowPowerMode.asStateFlow()
    
    // Exposed sensor readings
    var latestLux = 0.0f
        private set
    var latestProximity = 0.0f
        private set
    var latestPressure = 0.0f
        private set
    var latestAmbientTemp = 0.0f
        private set
    var latestHumidity = 0.0f
        private set

    // Config values
    private var isEnabled = false
    private var timeoutSeconds = 30

    fun start() {
        Log.d(TAG, "Starting PresenceSensorManager...")
        // Always register sensors so they can be read by clients
        registerSensors()
        
        // Listen to configuration changes for presence-specific behavior
        scope.launch {
            settingsRepository.presenceSensorEnabled.collectLatest { enabled ->
                isEnabled = enabled
                if (!enabled) {
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

        scope.launch {
            settingsRepository.lowPowerModeEnabled.collectLatest {
                evaluateLowPowerMode()
            }
        }

        scope.launch {
            settingsRepository.autoBrightnessEnabled.collectLatest { enabled ->
                resetBrightnessSmoothing()
                if (enabled) {
                    lastLux?.let { lux ->
                        evaluateAutoBrightness(lux)
                    }
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
        Log.i(TAG, "Registering hardware sensors")
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        ambientTempSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        humiditySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterSensors() {
        Log.i(TAG, "Unregistering hardware sensors")
        sensorManager.unregisterListener(this)
        lastLux = null
    }

    fun evaluateLowPowerMode() {
        val enabled = settingsRepository.lowPowerModeEnabled.value
        val shouldLowPower = enabled && !isPresent && latestLux < 2.0f
        if (_isLowPowerMode.value != shouldLowPower) {
            _isLowPowerMode.value = shouldLowPower
            Log.i(TAG, "Low Power Mode changed: $shouldLowPower (enabled=$enabled, isPresent=$isPresent, lux=$latestLux)")
        }
    }

    fun mapLuxToBrightness(lux: Float, minPercent: Int): Int {
        val clampedLux = lux.coerceIn(1.0f, 500.0f)
        val logLux = kotlin.math.log10(clampedLux.toDouble())
        val logMax = kotlin.math.log10(500.0)
        val percent = minPercent + (logLux / logMax * (100 - minPercent))
        return percent.toInt().coerceIn(minPercent, 100)
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
        
        // Reset brightness smoothing so that it immediately snaps to the new correct brightness
        resetBrightnessSmoothing()

        // Notify HA via MQTT
        mqttManager.publishPresenceState(present)

        // Notify ESPHome API
        (context as? cloud.kl8techgroup.kl8wall.KL8WallApplication)?.bluetoothProxyServer?.broadcastPresenceState(present)

        // Wake screen on presence, or turn off on absence
        withContext(Dispatchers.Main) {
            if (present) {
                if (!deviceController.isScreenOn()) {
                    Log.i(TAG, "Waking up screen due to presence detection")
                    deviceController.screenOn()
                }
            } else {
                if (!settingsRepository.screenAlwaysOn.value && deviceController.isScreenOn()) {
                    Log.i(TAG, "Turning off screen due to presence timeout")
                    deviceController.screenOff()
                }
            }
        }

        evaluateLowPowerMode()
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
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val distance = event.values[0]
                latestProximity = distance
                val maxRange = event.sensor.maximumRange
                Log.d(TAG, "Proximity change: distance=$distance, maxRange=$maxRange")
                
                if (isEnabled) {
                    val isNear = distance < maxRange && distance < 10.0f // within 10cm
                    if (isNear) {
                        triggerPresence("proximity")
                    }
                }
                evaluateLowPowerMode()
            }
            
            Sensor.TYPE_LIGHT -> {
                val currentLux = event.values[0]
                latestLux = currentLux
                val last = lastLux
                Log.d(TAG, "Light sensor change: currentLux=$currentLux")
                
                if (isEnabled && last != null) {
                    val delta = abs(currentLux - last)
                    if (delta >= LIGHT_THRESHOLD) {
                        triggerPresence("ambient_light")
                    }
                }
                lastLux = currentLux

                // Add auto-brightness and low-power evaluation
                if (settingsRepository.autoBrightnessEnabled.value && !_isLowPowerMode.value) {
                    evaluateAutoBrightness(currentLux)
                }
                evaluateLowPowerMode()
            }

            Sensor.TYPE_PRESSURE -> {
                latestPressure = event.values[0]
            }

            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                latestAmbientTemp = event.values[0]
            }

            Sensor.TYPE_RELATIVE_HUMIDITY -> {
                latestHumidity = event.values[0]
            }
        }
    }

    private fun evaluateAutoBrightness(currentLux: Float) {
        val lastSmoothed = smoothedLux
        val nextSmoothed = if (lastSmoothed == null) {
            currentLux
        } else if (abs(currentLux - lastSmoothed) > 50.0f) {
            // Immediate snap for dramatic lighting changes (e.g. lights turned on/off)
            currentLux
        } else {
            // EMA filter with alpha = 0.08
            (0.08f * currentLux) + (0.92f * lastSmoothed)
        }
        smoothedLux = nextSmoothed

        val minPct = settingsRepository.minBrightnessPercent.value
        val targetBrightness = mapLuxToBrightness(nextSmoothed, minPct)

        val lastSet = lastSetBrightness
        // Hysteresis: only update if lastSet is null or if targetBrightness differs by at least 3% (3 out of 100)
        if (lastSet == null || abs(targetBrightness - lastSet) >= 3) {
            lastSetBrightness = targetBrightness
            scope.launch(Dispatchers.Main) {
                deviceController.setBrightness(targetBrightness)
            }
        }
    }

    fun resetBrightnessSmoothing() {
        smoothedLux = null
        lastSetBrightness = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
