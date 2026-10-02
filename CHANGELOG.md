# Changelog

## v3.0.0

- **Breaking:** raise the minimum Android version from API 21 to API 23.
- **Build requirement:** consuming apps need `compileSdk` 36 or later.
- Compile and target Android API 36.
- Upgrade AGP, Gradle, Kotlin, Compose, and AndroidX dependencies.
- Add demo lint, JVM tests, emulator UI tests, and recorded payload checks in CI.

## v2.0.0

Breaking changes — see README → Migration from 1.x for upgrade instructions.

- Removed People/Group analytics API
- Removed super-properties API
- Removed timed-events API
- Removed multi-instance (named `getInstance`) support
- Updated ingest endpoint
- Renamed AndroidManifest meta-data keys (`MPConfig` → `Config`)

## v1.0 (original)

Initial release.
