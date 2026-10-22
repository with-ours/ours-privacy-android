# Changelog

## v3.1.0

- Opt into canonical `$mobile_*` lifecycle events with `trackAutomaticEvents(true)`; call `trackScreen(name)` for explicit screen views, including custom navigation.
- **Crash capture migration:** pass `trackAutomaticCrashes(true)` to continue receiving legacy `$ae_crashed`; enabling lifecycle events alone no longer enables exception capture.
- Existing installs migrate without a new first-open; queued events survive identity reset, expired sessions end at last activity, and clock recovery keeps the queue moving.
- Tracking and lifecycle persistence run on a background worker; storage failures log and drop the affected operation.
- Successful proxy responses acknowledge batches; permanent ingest failures report sanitized rejection codes, and malformed indexed acknowledgments have a bounded retry count.
- `$mobile_*` event names are reserved for SDK telemetry and ignored by manual `track()` calls.
- For Mobile Analytics, migrate counts from legacy `$ae_*` and `$app_open` to canonical `$mobile_*` events; legacy lifecycle events continue during migration.

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
