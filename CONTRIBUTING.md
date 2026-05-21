# Contributing to KL8Wall

Thanks for your interest in contributing to KL8Wall. This document covers the guidelines for submitting changes.

## Development Setup

1. Clone the repository
2. Open in Android Studio (Ladybug or later) or build from the command line
3. JDK 17 and Android SDK 35 are required

## Code Standards

- **Kotlin only.** No Java files in the source tree.
- All compiler warnings are errors (`allWarningsAsErrors = true`).
- No `!!` non-null assertions. No `Any` types except at framework boundaries.
- All public functions must have KDoc.
- No `TODO`, `FIXME`, or `XXX` comments in committed code. Use GitHub Issues instead.
- Maximum line length is 120 characters.

## Linting

Every PR must pass both linters with zero issues:

```bash
./gradlew ktlintCheck detekt
```

- **ktlint** enforces the `android_studio` code style
- **detekt** enforces complexity, naming, and style rules (see `config/detekt/detekt.yml`)

## Testing

Run unit tests before submitting:

```bash
./gradlew testDebugUnitTest
```

## Architecture Rules

- Single Activity architecture. No fragments.
- Jetpack Compose for settings UI. WebView stays AndroidView-wrapped.
- No dependency injection frameworks (Hilt, Dagger, Koin). Manual DI via `KL8WallApplication`.
- No networking libraries (Retrofit, OkHttp). NanoHTTPD for the embedded server.
- No Google Play Services dependencies.
- No third-party analytics, crash reporting, or telemetry of any kind.
- Secrets encrypted at rest via EncryptedSharedPreferences. Never log tokens.

## Pull Requests

1. Fork the repository and create a feature branch
2. Keep commits focused and atomic
3. Ensure `ktlintCheck`, `detekt`, and `testDebugUnitTest` all pass
4. Write a clear PR description explaining the change and motivation
5. One feature or fix per PR

## Security

If you discover a security vulnerability, please report it privately rather than opening a public issue. Contact the maintainers at the email listed in the repository.

## License

By contributing, you agree that your contributions will be licensed under the GNU General Public License v3.0.
