# KL8Wall

A free and open-source Android kiosk app for wall-mounted tablets running Home Assistant dashboards. Built by [KL8TechGroup](https://kl8techgroup.cloud).

KL8Wall turns any Android tablet (API 26+) into a dedicated HA dashboard panel with an embedded REST API for remote control, making it ideal for smart home wall mounts.

## Features

- **Hardened WebView** - locked-down browser with navigation allow-listing, auto-injected HA auth tokens, and disabled file/geolocation access
- **Kiosk mode** - Device Owner lock task (via ADB) with screen pinning fallback
- **Embedded HTTP server** - REST API bound to the WiFi interface with Bearer token auth, auto-discovered via mDNS (`_kl8wall._tcp.local.`)
- **Screen control** - wake/sleep endpoints for automation-driven screen management
- **Brightness control** - GET/SET brightness via the API (requires WRITE_SETTINGS permission)
- **Text-to-speech** - speak announcements through the tablet speaker via the API
- **PIN-protected settings** - optional Argon2id-hashed PIN with exponential backoff lockout
- **Hot corner access** - configurable 3-second long-press corner to open settings
- **Encrypted storage** - all secrets (HA token, bearer token, PIN hash) encrypted at rest via EncryptedSharedPreferences
- **No telemetry** - zero analytics, crash reporting, or tracking of any kind
- **No Google Play Services** - works on de-Googled Android (LineageOS, GrapheneOS, etc.)

## API Endpoints

All endpoints require `Authorization: Bearer <token>` header. The token is auto-generated on first launch and visible in Settings.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/status` | Device state (screen, URL, lock state, version) |
| `GET` | `/api/config` | Non-sensitive configuration |
| `GET` | `/api/brightness` | Current brightness (0-100) and permission flag |
| `POST` | `/api/brightness` | Set brightness `{"brightness": 0-100}` |
| `POST` | `/api/screen/on` | Wake the screen |
| `POST` | `/api/screen/off` | Sleep the screen |
| `POST` | `/api/navigate` | Load a URL `{"url": "..."}` |
| `POST` | `/api/reload` | Reload the current page |
| `POST` | `/api/tts` | Speak text `{"text": "..."}` |
| `POST` | `/api/tts/stop` | Stop TTS playback |

## Quick Start

1. Install the APK on your tablet
2. On first launch, enter your Home Assistant URL and long-lived access token
3. The embedded server starts automatically on port 8127
4. Find your bearer token in Settings (hot corner long-press, bottom-right by default)

### Device Owner Setup (recommended)

For full kiosk lock, set the app as Device Owner via ADB before first launch:

```bash
adb shell dpm set-device-owner cloud.kl8techgroup.kl8wall/.kiosk.KioskDeviceAdminReceiver
```

Without Device Owner, the app falls back to screen pinning.

### Home Assistant Integration

```yaml
rest_command:
  kl8wall_screen_on:
    url: "http://TABLET_IP:8127/api/screen/on"
    method: POST
    headers:
      Authorization: "Bearer YOUR_TOKEN"

  kl8wall_navigate:
    url: "http://TABLET_IP:8127/api/navigate"
    method: POST
    headers:
      Authorization: "Bearer YOUR_TOKEN"
    payload: '{"url": "{{ url }}"}'
    content_type: "application/json"

  kl8wall_tts:
    url: "http://TABLET_IP:8127/api/tts"
    method: POST
    headers:
      Authorization: "Bearer YOUR_TOKEN"
    payload: '{"text": "{{ message }}"}'
    content_type: "application/json"
```

## Building from Source

Requires JDK 17 and Android SDK 35.

```bash
./gradlew assembleDebug
```

The debug APK is output to `app/build/outputs/apk/debug/`.

### Code Quality

```bash
./gradlew ktlintCheck detekt testDebugUnitTest
```

## Tech Stack

- Kotlin (no Java)
- Jetpack Compose (Material 3) for settings UI
- AndroidView-wrapped WebView for the dashboard
- NanoHTTPD embedded HTTP server
- JmDNS for mDNS service advertisement
- kotlinx.serialization for JSON
- EncryptedSharedPreferences (AES-256-GCM)
- Argon2id PIN hashing via argon2kt

## Constraints

- Min SDK 26, Target SDK 35
- No third-party analytics, crash reporting, or telemetry
- No Google Play Services dependencies
- No Hilt/Dagger, Retrofit/OkHttp, Room, or WorkManager
- HTTP server binds to WiFi interface only (never 0.0.0.0)

## License

KL8Wall is licensed under the [GNU General Public License v3.0](LICENSE).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.
