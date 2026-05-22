package cloud.kl8techgroup.kl8wall.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /api/status response — current device state. */
@Serializable
data class StatusResponse(
    @SerialName("screen_on") val screenOn: Boolean,
    @SerialName("current_url") val currentUrl: String,
    @SerialName("lock_state") val lockState: String,
    val version: String,
    @SerialName("mqtt_connected") val mqttConnected: Boolean? = null,
    @SerialName("mqtt_error") val mqttError: String? = null,
    @SerialName("passcode_locked") val passcodeLocked: Boolean
)

/** GET /api/config response — non-sensitive configuration. */
@Serializable
data class ConfigResponse(
    val port: Int,
    @SerialName("hot_corner") val hotCorner: String,
    @SerialName("pin_enabled") val pinEnabled: Boolean,
    @SerialName("media_gesture") val mediaGesture: Boolean
)

/** POST /api/navigate request body. */
@Serializable
data class NavigateRequest(val url: String)

/** GET /api/brightness response. */
@Serializable
data class BrightnessResponse(
    val brightness: Int,
    @SerialName("can_write") val canWrite: Boolean
)

/** POST /api/brightness request body. */
@Serializable
data class BrightnessRequest(val brightness: Int)

/** POST /api/tts request body. */
@Serializable
data class TtsRequest(val text: String)

/** POST /api/cast request body. */
@Serializable
data class CastRequest(val url: String)

/** POST /api/cast/control request body. */
@Serializable
data class CastControlRequest(
    val command: String,
    val position: Int? = null
)

/** POST /api/cast/volume request body. */
@Serializable
data class VolumeRequest(val volume: Int)

/** GET /api/cast/status response. */
@Serializable
data class CastStatusResponse(
    val url: String?,
    @SerialName("playback_state") val playbackState: String,
    val duration: Int,
    val position: Int,
    val volume: Int
)

/** Generic success response for commands. */
@Serializable
data class SuccessResponse(val success: Boolean = true)

/** Error response with human-readable message. */
@Serializable
data class ErrorResponse(val error: String)
