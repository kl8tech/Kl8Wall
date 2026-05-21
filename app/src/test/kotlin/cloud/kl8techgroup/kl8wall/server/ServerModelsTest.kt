package cloud.kl8techgroup.kl8wall.server

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun statusResponseUsesSnakeCaseKeys() {
        val response = StatusResponse(
            screenOn = true,
            currentUrl = "http://ha.local:8123",
            lockState = "DEVICE_OWNER",
            version = "1.0.0"
        )
        val serialized = json.encodeToString(StatusResponse.serializer(), response)

        assertTrue(serialized.contains("\"screen_on\":true"))
        assertTrue(serialized.contains("\"current_url\":\"http://ha.local:8123\""))
        assertTrue(serialized.contains("\"lock_state\":\"DEVICE_OWNER\""))
        assertTrue(serialized.contains("\"version\":\"1.0.0\""))
    }

    @Test
    fun configResponseUsesSnakeCaseKeys() {
        val response = ConfigResponse(
            port = 8127,
            hotCorner = "BOTTOM_RIGHT",
            pinEnabled = false,
            mediaGesture = true
        )
        val serialized = json.encodeToString(ConfigResponse.serializer(), response)

        assertTrue(serialized.contains("\"hot_corner\":\"BOTTOM_RIGHT\""))
        assertTrue(serialized.contains("\"pin_enabled\":false"))
        assertTrue(serialized.contains("\"media_gesture\":true"))
        assertTrue(serialized.contains("\"port\":8127"))
    }

    @Test
    fun navigateRequestDeserializesFromJson() {
        val body = """{"url":"http://ha.local:8123/dashboard"}"""
        val request = json.decodeFromString(NavigateRequest.serializer(), body)

        assertEquals("http://ha.local:8123/dashboard", request.url)
    }

    @Test
    fun navigateRequestIgnoresUnknownFields() {
        val body = """{"url":"http://ha.local:8123","extra":"ignored"}"""
        val request = json.decodeFromString(NavigateRequest.serializer(), body)

        assertEquals("http://ha.local:8123", request.url)
    }

    @Test
    fun successResponseDefaultsToTrue() {
        val response = SuccessResponse()
        val serialized = json.encodeToString(SuccessResponse.serializer(), response)

        assertTrue(serialized.contains("\"success\""))
        assertTrue(serialized.contains("true"))
    }

    @Test
    fun errorResponseIncludesMessage() {
        val response = ErrorResponse(error = "Not found")
        val serialized = json.encodeToString(ErrorResponse.serializer(), response)

        assertTrue(serialized.contains("\"error\""))
        assertTrue(serialized.contains("Not found"))
    }

    @Test
    fun statusResponseRoundTrips() {
        val original = StatusResponse(
            screenOn = false,
            currentUrl = "",
            lockState = "UNLOCKED",
            version = "1.0.0"
        )
        val serialized = json.encodeToString(StatusResponse.serializer(), original)
        val deserialized = json.decodeFromString(StatusResponse.serializer(), serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun brightnessResponseUsesSnakeCaseKeys() {
        val response = BrightnessResponse(brightness = 75, canWrite = true)
        val serialized = json.encodeToString(BrightnessResponse.serializer(), response)

        assertTrue(serialized.contains("\"brightness\":75"))
        assertTrue(serialized.contains("\"can_write\":true"))
    }

    @Test
    fun brightnessRequestDeserializesFromJson() {
        val body = """{"brightness":50}"""
        val request = json.decodeFromString(BrightnessRequest.serializer(), body)

        assertEquals(50, request.brightness)
    }

    @Test
    fun ttsRequestDeserializesFromJson() {
        val body = """{"text":"Hello world"}"""
        val request = json.decodeFromString(TtsRequest.serializer(), body)

        assertEquals("Hello world", request.text)
    }

    @Test
    fun brightnessResponseRoundTrips() {
        val original = BrightnessResponse(brightness = 42, canWrite = false)
        val serialized = json.encodeToString(BrightnessResponse.serializer(), original)
        val deserialized = json.decodeFromString(BrightnessResponse.serializer(), serialized)

        assertEquals(original, deserialized)
    }
}
