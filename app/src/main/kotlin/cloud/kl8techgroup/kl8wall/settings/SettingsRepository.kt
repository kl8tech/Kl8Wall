package cloud.kl8techgroup.kl8wall.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Encrypted persistence layer for all KL8Wall configuration.
 *
 * Wraps EncryptedSharedPreferences to store sensitive values (HA token,
 * HTTP bearer token, PIN hash) encrypted at rest. Exposes all settings
 * as [StateFlow] for reactive Compose observation.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    private val _deviceName = MutableStateFlow(
        prefs.getString(KEY_DEVICE_NAME, "")?.takeIf { it.isNotEmpty() }
            ?: sanitizeDeviceName(android.os.Build.MODEL)
    )
    private val _mqttEnabled = MutableStateFlow(prefs.getBoolean(KEY_MQTT_ENABLED, false))
    private val _mqttBroker = MutableStateFlow(prefs.getString(KEY_MQTT_BROKER, "") ?: "")
    private val _mqttPort = MutableStateFlow(prefs.getInt(KEY_MQTT_PORT, 1883))
    private val _mqttUsername = MutableStateFlow(prefs.getString(KEY_MQTT_USERNAME, "") ?: "")
    private val _mqttPassword = MutableStateFlow(prefs.getString(KEY_MQTT_PASSWORD, "") ?: "")
    private val _bluetoothProxyEnabled = MutableStateFlow(prefs.getBoolean(KEY_BLUETOOTH_PROXY_ENABLED, false))
    private val _presenceSensorEnabled = MutableStateFlow(prefs.getBoolean(KEY_PRESENCE_SENSOR_ENABLED, false))
    private val _presenceTimeoutSeconds = MutableStateFlow(prefs.getInt(KEY_PRESENCE_TIMEOUT_SECONDS, 30))
    private val _cameraIntervalMinutes = MutableStateFlow(prefs.getInt(KEY_CAMERA_INTERVAL_MINUTES, 60))

    private val _startUrl = MutableStateFlow(prefs.getString(KEY_START_URL, "") ?: "")
    private val _haTokenSet = MutableStateFlow(prefs.getString(KEY_HA_TOKEN, "")?.isNotEmpty() == true)
    private val _httpPort = MutableStateFlow(prefs.getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT))
    private val _httpBearerToken = MutableStateFlow(
        prefs.getString(KEY_HTTP_BEARER_TOKEN, "") ?: ""
    )
    private val _allowedHosts = MutableStateFlow(loadAllowedHosts())
    private val _hotCorner = MutableStateFlow(
        HotCorner.fromString(prefs.getString(KEY_HOT_CORNER, HotCorner.BOTTOM_RIGHT.name) ?: "")
    )
    private val _pinHash = MutableStateFlow(prefs.getString(KEY_PIN_HASH, "") ?: "")
    private val _pinSalt = MutableStateFlow(prefs.getString(KEY_PIN_SALT, "") ?: "")
    private val _isFirstRun = MutableStateFlow(prefs.getBoolean(KEY_FIRST_RUN, true))
    private val _mediaPlaybackRequiresGesture = MutableStateFlow(
        prefs.getBoolean(KEY_MEDIA_GESTURE, false)
    )
    private val _screenAlwaysOn = MutableStateFlow(prefs.getBoolean(KEY_SCREEN_ALWAYS_ON, true))
    private val _ignoreSslErrors = MutableStateFlow(prefs.getBoolean(KEY_IGNORE_SSL_ERRORS, true))
    private val _micShimEnabled = MutableStateFlow(prefs.getBoolean(KEY_MIC_SHIM_ENABLED, true))
    private val _autoWakeOnPower = MutableStateFlow(prefs.getBoolean(KEY_AUTO_WAKE_ON_POWER, true))
    private val _mdnsEnabled = MutableStateFlow(prefs.getBoolean(KEY_MDNS_ENABLED, true))
    private val _sensorIntervalSeconds = MutableStateFlow(prefs.getInt(KEY_SENSOR_INTERVAL_SECONDS, 30))
    private val _autoBrightnessEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_BRIGHTNESS_ENABLED, true))
    private val _lowPowerModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOW_POWER_MODE_ENABLED, true))
    private val _minBrightnessPercent = MutableStateFlow(prefs.getInt(KEY_MIN_BRIGHTNESS_PERCENT, 10))
    private val _manualBrightnessPercent = MutableStateFlow(prefs.getInt(KEY_MANUAL_BRIGHTNESS_PERCENT, 70))
    private val _batterySaverEnabled = MutableStateFlow(prefs.getBoolean(KEY_BATTERY_SAVER_ENABLED, false))
    private val _batterySaverEntityId = MutableStateFlow(prefs.getString(KEY_BATTERY_SAVER_ENTITY_ID, "switch.tablet_charger") ?: "switch.tablet_charger")
    private val _batterySaverMin = MutableStateFlow(prefs.getInt(KEY_BATTERY_SAVER_MIN, 20))
    private val _batterySaverMax = MutableStateFlow(prefs.getInt(KEY_BATTERY_SAVER_MAX, 80))
    private val _intercomTarget = MutableStateFlow(prefs.getString(KEY_INTERCOM_TARGET, "living_room") ?: "living_room")

    /** Unique device name for MQTT topics and mDNS hostnames. */
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    /** Whether MQTT reporting and controls are enabled. */
    val mqttEnabled: StateFlow<Boolean> = _mqttEnabled.asStateFlow()

    /** MQTT broker host/IP. */
    val mqttBroker: StateFlow<String> = _mqttBroker.asStateFlow()

    /** MQTT broker port. */
    val mqttPort: StateFlow<Int> = _mqttPort.asStateFlow()

    /** MQTT broker username. */
    val mqttUsername: StateFlow<String> = _mqttUsername.asStateFlow()

    /** MQTT broker password (encrypted at rest). */
    val mqttPassword: StateFlow<String> = _mqttPassword.asStateFlow()

    /** Whether the ESPHome Bluetooth proxy server is enabled. */
    val bluetoothProxyEnabled: StateFlow<Boolean> = _bluetoothProxyEnabled.asStateFlow()

    /** Whether presence sensing is enabled. */
    val presenceSensorEnabled: StateFlow<Boolean> = _presenceSensorEnabled.asStateFlow()

    /** Presence sensing inactivity timeout in seconds. */
    val presenceTimeoutSeconds: StateFlow<Int> = _presenceTimeoutSeconds.asStateFlow()

    /** Photo capturing interval in minutes. */
    val cameraIntervalMinutes: StateFlow<Int> = _cameraIntervalMinutes.asStateFlow()

    /** Home Assistant dashboard URL. */
    val startUrl: StateFlow<String> = _startUrl.asStateFlow()

    /** Whether an HA token has been stored (never exposes the actual token via Flow). */
    val haTokenSet: StateFlow<Boolean> = _haTokenSet.asStateFlow()

    /** HTTP server port (default 8127). */
    val httpPort: StateFlow<Int> = _httpPort.asStateFlow()

    /** Bearer token for the embedded HTTP server. */
    val httpBearerToken: StateFlow<String> = _httpBearerToken.asStateFlow()

    /** Hosts the WebView is allowed to navigate to. */
    val allowedHosts: StateFlow<Set<String>> = _allowedHosts.asStateFlow()

    /** Which screen corner triggers settings access. */
    val hotCorner: StateFlow<HotCorner> = _hotCorner.asStateFlow()

    private val _isPinSet = MutableStateFlow(_pinHash.value.isNotEmpty())

    /** Whether a PIN is configured. */
    val isPinSet: StateFlow<Boolean> = _isPinSet.asStateFlow()

    /** Whether this is the first launch (setup wizard needed). */
    val isFirstRun: StateFlow<Boolean> = _isFirstRun.asStateFlow()

    /** Whether media playback requires a user gesture. */
    val mediaPlaybackRequiresGesture: StateFlow<Boolean> = _mediaPlaybackRequiresGesture.asStateFlow()

    /** Whether screen is kept always on. */
    val screenAlwaysOn: StateFlow<Boolean> = _screenAlwaysOn.asStateFlow()

    /** Whether SSL validation errors are bypassed in WebView. */
    val ignoreSslErrors: StateFlow<Boolean> = _ignoreSslErrors.asStateFlow()

    /** Whether microphone access JavaScript shim is injected in WebView. */
    val micShimEnabled: StateFlow<Boolean> = _micShimEnabled.asStateFlow()

    /** Whether screen wakes up on charger connection events. */
    val autoWakeOnPower: StateFlow<Boolean> = _autoWakeOnPower.asStateFlow()

    /** Whether mDNS service advertisement is enabled. */
    val mdnsEnabled: StateFlow<Boolean> = _mdnsEnabled.asStateFlow()

    /** Publishing interval in seconds for device sensors. */
    val sensorIntervalSeconds: StateFlow<Int> = _sensorIntervalSeconds.asStateFlow()

    /** Whether display brightness is dynamically scaled by ambient light. */
    val autoBrightnessEnabled: StateFlow<Boolean> = _autoBrightnessEnabled.asStateFlow()

    /** Whether low power kiosk sleep (dim display + pause webview) is enabled. */
    val lowPowerModeEnabled: StateFlow<Boolean> = _lowPowerModeEnabled.asStateFlow()

    /** Minimum brightness floor when in low-light auto-brightness. */
    val minBrightnessPercent: StateFlow<Int> = _minBrightnessPercent.asStateFlow()

    /** Static manual brightness percentage when auto-brightness is disabled. */
    val manualBrightnessPercent: StateFlow<Int> = _manualBrightnessPercent.asStateFlow()

    /** Whether battery saver smart charging is enabled. */
    val batterySaverEnabled: StateFlow<Boolean> = _batterySaverEnabled.asStateFlow()

    /** The Home Assistant switch entity ID that controls power to this charger. */
    val batterySaverEntityId: StateFlow<String> = _batterySaverEntityId.asStateFlow()

    /** Minimum battery level before charger turns back ON. */
    val batterySaverMin: StateFlow<Int> = _batterySaverMin.asStateFlow()

    /** Maximum battery level before charger turns OFF. */
    val batterySaverMax: StateFlow<Int> = _batterySaverMax.asStateFlow()

    /** Default target device name for intercom. */
    val intercomTarget: StateFlow<String> = _intercomTarget.asStateFlow()

    init {
        if (_httpBearerToken.value.isEmpty()) {
            generateAndStoreHttpBearerToken()
        }
    }

    fun setDeviceName(name: String) {
        val sanitized = sanitizeDeviceName(name)
        prefs.edit().putString(KEY_DEVICE_NAME, sanitized).apply()
        _deviceName.value = sanitized
    }

    fun setMqttEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MQTT_ENABLED, enabled).apply()
        _mqttEnabled.value = enabled
    }

    fun setMqttBroker(broker: String) {
        prefs.edit().putString(KEY_MQTT_BROKER, broker).apply()
        _mqttBroker.value = broker
    }

    fun setMqttPort(port: Int) {
        prefs.edit().putInt(KEY_MQTT_PORT, port).apply()
        _mqttPort.value = port
    }

    fun setMqttUsername(username: String) {
        prefs.edit().putString(KEY_MQTT_USERNAME, username).apply()
        _mqttUsername.value = username
    }

    fun setMqttPassword(password: String) {
        prefs.edit().putString(KEY_MQTT_PASSWORD, password).apply()
        _mqttPassword.value = password
    }

    fun setBluetoothProxyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLUETOOTH_PROXY_ENABLED, enabled).apply()
        _bluetoothProxyEnabled.value = enabled
    }

    fun setPresenceSensorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRESENCE_SENSOR_ENABLED, enabled).apply()
        _presenceSensorEnabled.value = enabled
    }

    fun setPresenceTimeoutSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_PRESENCE_TIMEOUT_SECONDS, seconds).apply()
        _presenceTimeoutSeconds.value = seconds
    }

    fun setCameraIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_CAMERA_INTERVAL_MINUTES, minutes).apply()
        _cameraIntervalMinutes.value = minutes
    }

    private fun sanitizeDeviceName(model: String): String =
        model.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')

    /** Save the Home Assistant dashboard URL and auto-seed allowed hosts from its hostname. */
    fun setStartUrl(url: String) {
        prefs.edit().putString(KEY_START_URL, url).apply()
        _startUrl.value = url
        autoSeedAllowedHost(url)
    }

    /**
     * Store the HA long-lived access token (encrypted at rest).
     * The token is never exposed via StateFlow — only a boolean flag.
     */
    fun setHaToken(token: String) {
        prefs.edit().putString(KEY_HA_TOKEN, token).apply()
        _haTokenSet.value = token.isNotEmpty()
    }

    /** Retrieve the HA token for injection into WebView requests. */
    fun getHaToken(): String = prefs.getString(KEY_HA_TOKEN, "") ?: ""

    /** Update the HTTP server port. */
    fun setHttpPort(port: Int) {
        prefs.edit().putInt(KEY_HTTP_PORT, port).apply()
        _httpPort.value = port
    }

    /** Rotate the HTTP bearer token and return the new value. */
    fun rotateHttpBearerToken(): String {
        generateAndStoreHttpBearerToken()
        return _httpBearerToken.value
    }

    /** Set the list of allowed hosts for WebView navigation. */
    fun setAllowedHosts(hosts: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED_HOSTS, hosts).apply()
        _allowedHosts.value = hosts
    }

    /** Add a single host to the allowed list. */
    fun addAllowedHost(host: String) {
        val updated = _allowedHosts.value + host.lowercase()
        setAllowedHosts(updated)
    }

    /** Set which screen corner opens settings. */
    fun setHotCorner(corner: HotCorner) {
        prefs.edit().putString(KEY_HOT_CORNER, corner.name).apply()
        _hotCorner.value = corner
    }

    /** Store a PIN hash and salt. Pass empty strings to clear the PIN. */
    fun setPinHash(hash: String, salt: String) {
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, salt)
            .apply()
        _pinHash.value = hash
        _pinSalt.value = salt
        _isPinSet.value = hash.isNotEmpty()
    }

    /** Retrieve the stored PIN hash for verification. */
    fun getPinHash(): String = _pinHash.value

    /** Retrieve the stored PIN salt for verification. */
    fun getPinSalt(): String = _pinSalt.value

    /** Mark first-run setup as complete. */
    fun completeFirstRun() {
        prefs.edit().putBoolean(KEY_FIRST_RUN, false).apply()
        _isFirstRun.value = false
    }

    /** Toggle media playback gesture requirement. */
    fun setMediaPlaybackRequiresGesture(required: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_GESTURE, required).apply()
        _mediaPlaybackRequiresGesture.value = required
    }

    fun setScreenAlwaysOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_ALWAYS_ON, enabled).apply()
        _screenAlwaysOn.value = enabled
    }

    fun setIgnoreSslErrors(ignore: Boolean) {
        prefs.edit().putBoolean(KEY_IGNORE_SSL_ERRORS, ignore).apply()
        _ignoreSslErrors.value = ignore
    }

    fun setMicShimEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MIC_SHIM_ENABLED, enabled).apply()
        _micShimEnabled.value = enabled
    }

    fun setAutoWakeOnPower(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_WAKE_ON_POWER, enabled).apply()
        _autoWakeOnPower.value = enabled
    }

    fun setMdnsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MDNS_ENABLED, enabled).apply()
        _mdnsEnabled.value = enabled
    }

    fun setSensorIntervalSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_SENSOR_INTERVAL_SECONDS, seconds).apply()
        _sensorIntervalSeconds.value = seconds
    }

    fun setAutoBrightnessEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BRIGHTNESS_ENABLED, enabled).apply()
        _autoBrightnessEnabled.value = enabled
    }

    fun setLowPowerModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOW_POWER_MODE_ENABLED, enabled).apply()
        _lowPowerModeEnabled.value = enabled
    }

    fun setMinBrightnessPercent(percent: Int) {
        prefs.edit().putInt(KEY_MIN_BRIGHTNESS_PERCENT, percent).apply()
        _minBrightnessPercent.value = percent
    }

    fun setManualBrightnessPercent(percent: Int) {
        prefs.edit().putInt(KEY_MANUAL_BRIGHTNESS_PERCENT, percent).apply()
        _manualBrightnessPercent.value = percent
    }

    fun setBatterySaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BATTERY_SAVER_ENABLED, enabled).apply()
        _batterySaverEnabled.value = enabled
    }

    fun setBatterySaverEntityId(entityId: String) {
        prefs.edit().putString(KEY_BATTERY_SAVER_ENTITY_ID, entityId).apply()
        _batterySaverEntityId.value = entityId
    }

    fun setBatterySaverMin(min: Int) {
        prefs.edit().putInt(KEY_BATTERY_SAVER_MIN, min).apply()
        _batterySaverMin.value = min
    }

    fun setBatterySaverMax(max: Int) {
        prefs.edit().putInt(KEY_BATTERY_SAVER_MAX, max).apply()
        _batterySaverMax.value = max
    }

    fun setIntercomTarget(target: String) {
        prefs.edit().putString(KEY_INTERCOM_TARGET, target).apply()
        _intercomTarget.value = target
    }

    private fun generateAndStoreHttpBearerToken() {
        val bytes = ByteArray(TOKEN_BYTE_LENGTH)
        SecureRandom().nextBytes(bytes)
        val token = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        prefs.edit().putString(KEY_HTTP_BEARER_TOKEN, token).apply()
        _httpBearerToken.value = token
    }

    private fun autoSeedAllowedHost(url: String) {
        try {
            val host = java.net.URI(url).host?.lowercase() ?: return
            if (host.isNotEmpty()) {
                addAllowedHost(host)
            }
        } catch (_: Exception) {
            // Invalid URL — skip auto-seeding
        }
    }

    private fun loadAllowedHosts(): Set<String> = prefs.getStringSet(KEY_ALLOWED_HOSTS, emptySet()) ?: emptySet()

    companion object {
        private const val PREFS_FILE = "kl8wall_secure_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_MQTT_ENABLED = "mqtt_enabled"
        private const val KEY_MQTT_BROKER = "mqtt_broker"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_BLUETOOTH_PROXY_ENABLED = "bluetooth_proxy_enabled"
        private const val KEY_PRESENCE_SENSOR_ENABLED = "presence_sensor_enabled"
        private const val KEY_PRESENCE_TIMEOUT_SECONDS = "presence_timeout_seconds"
        private const val KEY_CAMERA_INTERVAL_MINUTES = "camera_interval_minutes"

        private const val KEY_START_URL = "start_url"
        private const val KEY_HA_TOKEN = "ha_token"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_HTTP_BEARER_TOKEN = "http_bearer_token"
        private const val KEY_ALLOWED_HOSTS = "allowed_hosts"
        private const val KEY_HOT_CORNER = "hot_corner"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_FIRST_RUN = "first_run"
        private const val KEY_MEDIA_GESTURE = "media_playback_requires_gesture"
        private const val KEY_SCREEN_ALWAYS_ON = "screen_always_on"
        private const val KEY_IGNORE_SSL_ERRORS = "ignore_ssl_errors"
        private const val KEY_MIC_SHIM_ENABLED = "mic_shim_enabled"
        private const val KEY_AUTO_WAKE_ON_POWER = "auto_wake_on_power"
        private const val KEY_MDNS_ENABLED = "mdns_enabled"
        private const val KEY_SENSOR_INTERVAL_SECONDS = "sensor_interval_seconds"
        private const val KEY_AUTO_BRIGHTNESS_ENABLED = "auto_brightness_enabled"
        private const val KEY_LOW_POWER_MODE_ENABLED = "low_power_mode_enabled"
        private const val KEY_MIN_BRIGHTNESS_PERCENT = "min_brightness_percent"
        private const val KEY_MANUAL_BRIGHTNESS_PERCENT = "manual_brightness_percent"
        private const val KEY_BATTERY_SAVER_ENABLED = "battery_saver_enabled"
        private const val KEY_BATTERY_SAVER_ENTITY_ID = "battery_saver_entity_id"
        private const val KEY_BATTERY_SAVER_MIN = "battery_saver_min"
        private const val KEY_BATTERY_SAVER_MAX = "battery_saver_max"
        private const val KEY_INTERCOM_TARGET = "intercom_target"
        private const val DEFAULT_HTTP_PORT = 8127
        private const val TOKEN_BYTE_LENGTH = 32

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val spec = KeyGenParameterSpec.Builder(
                MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(MasterKey.DEFAULT_AES_GCM_MASTER_KEY_SIZE)
                .build()

            val masterKey = MasterKey.Builder(context)
                .setKeyGenParameterSpec(spec)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}

/** Screen corner positions for the settings access hot corner. */
enum class HotCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT;

    /** Human-readable label (e.g. "Bottom right"). */
    val displayName: String
        get() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    companion object {
        /** Parse from stored string, defaulting to [BOTTOM_RIGHT]. */
        fun fromString(value: String): HotCorner = entries.find { it.name == value } ?: BOTTOM_RIGHT
    }
}
