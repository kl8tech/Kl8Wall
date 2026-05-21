package cloud.kl8techgroup.kl8wall.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64
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

    init {
        if (_httpBearerToken.value.isEmpty()) {
            generateAndStoreHttpBearerToken()
        }
    }

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

    private fun generateAndStoreHttpBearerToken() {
        val bytes = ByteArray(TOKEN_BYTE_LENGTH)
        SecureRandom().nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
