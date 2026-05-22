package cloud.kl8techgroup.kl8wall.server

import cloud.kl8techgroup.kl8wall.BuildConfig
import cloud.kl8techgroup.kl8wall.KL8WallApplication
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Embedded HTTP server for remote device control.
 *
 * Binds exclusively to the WiFi interface IP (never 0.0.0.0). All
 * endpoints require Bearer token authentication via the Authorization
 * header. Provides status queries and device commands consumable by
 * Home Assistant automations or the companion admin UI.
 *
 * Endpoints:
 * - `GET  /api/status`       — device state (screen, URL, lock)
 * - `GET  /api/config`       — non-sensitive configuration
 * - `GET  /api/brightness`   — current brightness + permission flag
 * - `POST /api/brightness`   — set brightness (`{"brightness":0-100}`)
 * - `POST /api/screen/on`    — wake the screen
 * - `POST /api/screen/off`   — sleep the screen
 * - `POST /api/navigate`     — load a URL (`{"url":"..."}`)
 * - `POST /api/reload`       — reload the current page
 * - `POST /api/tts`          — speak text (`{"text":"..."}`)
 * - `POST /api/tts/stop`     — stop TTS playback
 */
class KL8WallHttpServer(
    hostname: String?,
    port: Int,
    private val bearerTokenProvider: () -> String,
    private val deviceControllerProvider: () -> DeviceController?,
    private val settingsRepository: SettingsRepository
) : NanoHTTPD(hostname, port) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Start the server as a daemon thread (won't block app shutdown). */
    fun startDaemon() {
        start(SOCKET_READ_TIMEOUT, true)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return corsWrapped(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, ""))
        }

        if (!verifyBearer(session)) {
            return corsWrapped(errorResponse(Response.Status.UNAUTHORIZED, "Invalid or missing bearer token"))
        }

        return corsWrapped(route(session))
    }

    private fun route(session: IHTTPSession): Response = when ("${session.method} ${session.uri}") {
        "GET /api/status" -> handleStatus()
        "GET /api/config" -> handleConfig()
        "GET /api/brightness" -> withController { handleBrightness(it) }
        "POST /api/brightness" -> withController { handleSetBrightness(it, session) }
        "POST /api/screen/on" -> withController { handleScreenOn(it) }
        "POST /api/screen/off" -> withController { handleScreenOff(it) }
        "POST /api/navigate" -> withController { handleNavigate(it, session) }
        "POST /api/reload" -> withController { handleReload(it) }
        "POST /api/tts" -> withController { handleTts(it, session) }
        "POST /api/tts/stop" -> withController { handleTtsStop(it) }
        "GET /api/screenshot" -> handleScreenshot()
        "POST /api/update/check" -> handleCheckUpdate()
        "POST /api/update/install" -> handleInstallUpdate()
        "GET /api/update/status" -> handleUpdateStatus()
        else -> errorResponse(Response.Status.NOT_FOUND, "Not found")
    }

    private inline fun withController(action: (DeviceController) -> Response): Response {
        val controller = deviceControllerProvider()
            ?: return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "Device not ready")
        return action(controller)
    }

    private fun handleStatus(): Response = withController { controller ->
        val mqtt = KL8WallApplication.instance.mqttManager
        val status = StatusResponse(
            screenOn = controller.isScreenOn(),
            currentUrl = controller.getCurrentUrl(),
            lockState = controller.getLockState(),
            version = BuildConfig.VERSION_NAME,
            mqttConnected = mqtt?.isConnected(),
            mqttError = mqtt?.lastError?.value
        )
        jsonResponse(Response.Status.OK, json.encodeToString(StatusResponse.serializer(), status))
    }

    private fun handleConfig(): Response {
        val config = ConfigResponse(
            port = settingsRepository.httpPort.value,
            hotCorner = settingsRepository.hotCorner.value.name,
            pinEnabled = settingsRepository.isPinSet.value,
            mediaGesture = settingsRepository.mediaPlaybackRequiresGesture.value
        )
        return jsonResponse(Response.Status.OK, json.encodeToString(ConfigResponse.serializer(), config))
    }

    private fun handleScreenOn(controller: DeviceController): Response {
        controller.screenOn()
        return successResponse()
    }

    private fun handleScreenOff(controller: DeviceController): Response {
        controller.screenOff()
        return successResponse()
    }

    private fun handleReload(controller: DeviceController): Response {
        controller.reload()
        return successResponse()
    }

    private fun handleTtsStop(controller: DeviceController): Response {
        controller.stopSpeaking()
        return successResponse()
    }

    private fun handleBrightness(controller: DeviceController): Response {
        val response = BrightnessResponse(
            brightness = controller.getBrightness(),
            canWrite = controller.canWriteSettings()
        )
        return jsonResponse(Response.Status.OK, json.encodeToString(BrightnessResponse.serializer(), response))
    }

    private fun handleSetBrightness(controller: DeviceController, session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            json.decodeFromString(BrightnessRequest.serializer(), body)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body, expected {\"brightness\": 0-100}")
        }
        if (!controller.setBrightness(request.brightness)) {
            return errorResponse(Response.Status.FORBIDDEN, "WRITE_SETTINGS permission not granted")
        }
        return successResponse()
    }

    private fun handleNavigate(controller: DeviceController, session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            json.decodeFromString(NavigateRequest.serializer(), body)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body, expected {\"url\": \"...\"}")
        }
        if (request.url.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "URL must not be blank")
        }
        controller.navigate(request.url)
        return successResponse()
    }

    private fun handleTts(controller: DeviceController, session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            json.decodeFromString(TtsRequest.serializer(), body)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body, expected {\"text\": \"...\"}")
        }
        if (request.text.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Text must not be blank")
        }
        controller.speak(request.text)
        return successResponse()
    }

    private fun handleScreenshot(): Response {
        val app = KL8WallApplication.instance
        val bytes = runBlocking {
            app.captureCurrentScreen()
        }
        if (bytes == null) {
            return errorResponse(Response.Status.INTERNAL_ERROR, "Failed to capture screen or app not in foreground")
        }
        val response = newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        return response
    }

    private fun verifyBearer(session: IHTTPSession): Boolean {
        val authHeader = session.headers["authorization"] ?: return false
        return authHeader == "Bearer ${bearerTokenProvider()}"
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun successResponse(): Response =
        jsonResponse(Response.Status.OK, json.encodeToString(SuccessResponse.serializer(), SuccessResponse()))

    private fun errorResponse(status: Response.Status, message: String): Response =
        jsonResponse(status, json.encodeToString(ErrorResponse.serializer(), ErrorResponse(message)))

    private fun jsonResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, JSON_MIME, body)

    private fun corsWrapped(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        return response
    }

    private fun handleCheckUpdate(): Response {
        val app = KL8WallApplication.instance
        app.otaManager.let { ota ->
            app.serverScope.launch {
                ota.checkForUpdates(false)
            }
        }
        return jsonResponse(Response.Status.OK, "{\"success\":true}")
    }

    private fun handleInstallUpdate(): Response {
        val app = KL8WallApplication.instance
        app.otaManager.let { ota ->
            app.serverScope.launch {
                ota.triggerUpdate()
            }
        }
        return jsonResponse(Response.Status.OK, "{\"success\":true}")
    }

    private fun handleUpdateStatus(): Response {
        val ota = KL8WallApplication.instance.otaManager
        val statusObj = JSONObject().apply {
            put("update_available", ota.updateAvailable.value)
            put("current_version_name", ota.currentVersionName)
            put("current_version_code", ota.currentVersionCode)
            put("latest_version_name", ota.latestVersion.value)
            put("latest_version_code", ota.latestVersionCode.value)
            put("is_updating", ota.isUpdating.value)
            put("error", ota.updateError.value ?: JSONObject.NULL)
        }
        return jsonResponse(Response.Status.OK, statusObj.toString())
    }

    companion object {
        private const val JSON_MIME = "application/json"
    }
}
