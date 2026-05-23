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

        if (!verifyRequest(session)) {
            return corsWrapped(errorResponse(Response.Status.UNAUTHORIZED, "Invalid or missing authorization credentials"))
        }

        return corsWrapped(route(session))
    }

    private fun route(session: IHTTPSession): Response = when ("${session.method} ${session.uri}") {
        "GET /api/status" -> handleStatus()
        "GET /api/config" -> handleConfig()
        "GET /api/brightness" -> handleBrightness()
        "POST /api/brightness" -> handleSetBrightness(session)
        "POST /api/screen/on" -> handleScreenOn()
        "POST /api/screen/off" -> handleScreenOff()
        "POST /api/navigate" -> handleNavigate(session)
        "POST /api/reload" -> handleReload()
        "POST /api/tts" -> handleTts(session)
        "POST /api/tts/stop" -> handleTtsStop()
        "GET /api/screenshot" -> handleScreenshot()
        "POST /api/update/check" -> handleCheckUpdate()
        "POST /api/update/install" -> handleInstallUpdate()
        "GET /api/update/status" -> handleUpdateStatus()
        "POST /api/cast" -> handleCast(session)
        "POST /api/cast/control" -> handleCastControl(session)
        "POST /api/cast/volume" -> handleCastVolume(session)
        "GET /api/cast/status" -> handleCastStatus()
        "POST /api/lock" -> handleLock()
        "POST /api/unlock" -> handleUnlock()
        "POST /api/reboot" -> handleReboot()
        "POST /api/peer/relay" -> handlePeerRelay(session)
        "POST /api/peer/command" -> handlePeerCommand(session)
        else -> errorResponse(Response.Status.NOT_FOUND, "Not found")
    }

    private inline fun withController(action: (DeviceController) -> Response): Response {
        val controller = deviceControllerProvider()
            ?: return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "Device not ready")
        return action(controller)
    }

    private fun handleStatus(): Response = withController { controller ->
        val mqtt = KL8WallApplication.instance.mqttManager
        val lockManager = KL8WallApplication.instance.passcodeLockManager
        val status = StatusResponse(
            screenOn = controller.isScreenOn(),
            currentUrl = controller.getCurrentUrl(),
            lockState = controller.getLockState(),
            version = BuildConfig.VERSION_NAME,
            mqttConnected = mqtt?.isConnected(),
            mqttError = mqtt?.lastError?.value,
            passcodeLocked = lockManager?.isLocked?.value ?: false
        )
        jsonResponse(Response.Status.OK, json.encodeToString(StatusResponse.serializer(), status))
    }

    private fun handleCast(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            json.decodeFromString(CastRequest.serializer(), body)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body, expected {\"url\": \"...\"}")
        }
        val castManager = KL8WallApplication.instance.castManager
            ?: return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "Cast manager not ready")
        castManager.cast(request.url)
        return successResponse()
    }

    private fun handleCastControl(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            json.decodeFromString(CastControlRequest.serializer(), body)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body, expected {\"command\": \"...\", \"position\": null}")
        }
        val castManager = KL8WallApplication.instance.castManager
            ?: return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "Cast manager not ready")
        when (request.command.uppercase()) {
            "PLAY" -> castManager.play()
            "PAUSE" -> castManager.pause()
            "STOP" -> castManager.stop()
            "SEEK" -> castManager.seek(request.position ?: 0)
            else -> return errorResponse(Response.Status.BAD_REQUEST, "Unknown cast command: ${request.command}")
        }
        return successResponse()
    }

    private fun handleCastVolume(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            json.decodeFromString(VolumeRequest.serializer(), body)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body, expected {\"volume\": 0-100}")
        }
        val castManager = KL8WallApplication.instance.castManager
            ?: return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "Cast manager not ready")
        castManager.setVolume(request.volume)
        return successResponse()
    }

    private fun handleCastStatus(): Response {
        val castManager = KL8WallApplication.instance.castManager
            ?: return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "Cast manager not ready")
        val status = CastStatusResponse(
            url = castManager.castUrl.value,
            playbackState = castManager.playbackState.value,
            duration = castManager.duration.value,
            position = castManager.position.value,
            volume = castManager.volume.value
        )
        return jsonResponse(Response.Status.OK, json.encodeToString(CastStatusResponse.serializer(), status))
    }

    private fun handleLock(): Response {
        val lockManager = KL8WallApplication.instance.passcodeLockManager
            ?: return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "Passcode lock manager not ready")
        lockManager.lock()
        return successResponse()
    }

    private fun handleUnlock(): Response {
        val lockManager = KL8WallApplication.instance.passcodeLockManager
            ?: return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "Passcode lock manager not ready")
        lockManager.unlock()
        return successResponse()
    }

    private fun handleReboot(): Response {
        val controller = deviceControllerProvider()
        if (controller != null) {
            controller.rebootApp()
        } else {
            KL8WallApplication.instance.rebootApplication()
        }
        return successResponse()
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

    private fun handleScreenOn(): Response {
        val controller = deviceControllerProvider()
        if (controller != null) {
            controller.screenOn()
        } else {
            KL8WallApplication.instance.launchMainActivity()
        }
        return successResponse()
    }

    private fun handleScreenOff(): Response {
        val controller = deviceControllerProvider()
        controller?.screenOff()
        return successResponse()
    }

    private fun handleReload(): Response {
        val controller = deviceControllerProvider()
        if (controller != null) {
            controller.reload()
        } else {
            KL8WallApplication.instance.launchMainActivity()
        }
        return successResponse()
    }

    private fun handleTtsStop(): Response {
        KL8WallApplication.instance.ttsController.stopSpeaking()
        return successResponse()
    }

    private fun handleBrightness(): Response {
        val app = KL8WallApplication.instance
        val response = BrightnessResponse(
            brightness = app.brightnessController.getBrightness(),
            canWrite = app.brightnessController.canWriteSettings()
        )
        return jsonResponse(Response.Status.OK, json.encodeToString(BrightnessResponse.serializer(), response))
    }

    private fun handleSetBrightness(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            json.decodeFromString(BrightnessRequest.serializer(), body)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body, expected {\"brightness\": 0-100}")
        }
        val app = KL8WallApplication.instance
        if (!app.brightnessController.setBrightness(request.brightness)) {
            return errorResponse(Response.Status.FORBIDDEN, "WRITE_SETTINGS permission not granted")
        }
        return successResponse()
    }

    private fun handleNavigate(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            json.decodeFromString(NavigateRequest.serializer(), body)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body, expected {\"url\": \"...\"}")
        }
        if (request.url.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "URL must not be blank")
        }
        val controller = deviceControllerProvider()
        if (controller != null) {
            controller.navigate(request.url)
        } else {
            KL8WallApplication.instance.launchMainActivity(request.url)
        }
        return successResponse()
    }

    private fun handleTts(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            json.decodeFromString(TtsRequest.serializer(), body)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body, expected {\"text\": \"...\"}")
        }
        if (request.text.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Text must not be blank")
        }
        KL8WallApplication.instance.ttsController.speak(request.text)
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

    private fun verifyRequest(session: IHTTPSession): Boolean {
        if (verifyBearer(session)) return true

        val uri = session.uri ?: ""
        if (uri == "/api/peer/relay" || uri == "/api/peer/command") {
            val meshAuthHeader = session.headers["x-kl8wall-mesh-auth"] ?: return false
            val peerManager = KL8WallApplication.instance.peerManager ?: return false
            val expectedAuth = peerManager.getMeshAuthToken()
            return expectedAuth.isNotEmpty() && meshAuthHeader == expectedAuth
        }

        return false
    }

    private fun handlePeerRelay(session: IHTTPSession): Response {
        val body = readBody(session)
        val jsonBody = try {
            JSONObject(body)
        } catch (e: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body")
        }

        val topic = jsonBody.optString("topic", "")
        val payloadStr = jsonBody.optString("payload", "")
        val isBase64 = jsonBody.optBoolean("is_base64", false)
        if (topic.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing topic")
        }

        val payloadBytes = if (isBase64) {
            try {
                android.util.Base64.decode(payloadStr, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                payloadStr.toByteArray(Charsets.UTF_8)
            }
        } else {
            payloadStr.toByteArray(Charsets.UTF_8)
        }

        val app = KL8WallApplication.instance
        val mqtt = app.mqttManager
        if (mqtt == null || !mqtt.isConnected()) {
            return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "MQTT broker not connected on this peer")
        }

        mqtt.publishExternally(topic, payloadBytes, retain = true)

        var resolvedIp = ""
        val brokerHost = settingsRepository.mqttBroker.value.trim()
        if (brokerHost.isNotEmpty()) {
            try {
                resolvedIp = java.net.InetAddress.getByName(brokerHost).hostAddress ?: ""
            } catch (_: Exception) {}
        }

        val responseJson = JSONObject().apply {
            put("status", "relayed")
            put("reconnect_requested", true)
            if (resolvedIp.isNotEmpty()) {
                put("broker_resolved_ip", resolvedIp)
            }
        }

        return jsonResponse(Response.Status.OK, responseJson.toString())
    }

    private fun handlePeerCommand(session: IHTTPSession): Response {
        val body = readBody(session)
        val jsonBody = try {
            JSONObject(body)
        } catch (e: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON body")
        }

        val topic = jsonBody.optString("topic", "")
        val payloadStr = jsonBody.optString("payload", "")
        val isBase64 = jsonBody.optBoolean("is_base64", false)
        if (topic.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing topic")
        }

        val payloadBytes = if (isBase64) {
            try {
                android.util.Base64.decode(payloadStr, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                payloadStr.toByteArray(Charsets.UTF_8)
            }
        } else {
            payloadStr.toByteArray(Charsets.UTF_8)
        }

        val mqtt = KL8WallApplication.instance.mqttManager
        if (mqtt != null) {
            mqtt.handleRelayedCommand(topic, payloadBytes)
            return successResponse()
        } else {
            return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "MQTT service not available")
        }
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
