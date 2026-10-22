# Ours Privacy Android SDK

[![Maven Central](https://img.shields.io/maven-central/v/com.oursprivacy/oursprivacy-android)](https://central.sonatype.com/artifact/com.oursprivacy/oursprivacy-android)
[![Apache License](https://img.shields.io/github/license/with-ours/ours-privacy-android)](https://oursprivacy.com)
[![Documentation](https://img.shields.io/badge/Documentation-blue)](https://docs.oursprivacy.com/docs/android-sdk)

Privacy-first analytics for Android.

- [Maven Central](https://central.sonatype.com/artifact/com.oursprivacy/oursprivacy-android)
- [GitHub](https://github.com/with-ours/ours-privacy-android)
- [Docs](https://docs.oursprivacy.com/docs/android-sdk)

---

## Table of Contents

- [Quick Start](#quick-start)
- [Complete Example](#complete-example)
- [API Reference](#api-reference)
  - [Initialization](#initialization)
  - [Core Tracking](#core-tracking)
  - [Mobile Screens and Lifecycle](#mobile-screens-and-lifecycle)
  - [Default Properties](#default-properties)
  - [Configuration](#configuration)
  - [Identity](#identity)
  - [Deep Link Attribution](#deep-link-attribution)
  - [Privacy Controls](#privacy-controls)
- [Payload Structure](#payload-structure)
- [FAQ](#faq)
- [Development](#development)
- [Support](#support)

---

## Quick Start

### 1. Install

Add to your app's `build.gradle` dependencies (use the latest version from [Maven Central](https://central.sonatype.com/artifact/com.oursprivacy/oursprivacy-android)):

```gradle
implementation "com.oursprivacy:oursprivacy-android:<latest>"
```

Make sure `mavenCentral()` is listed in your repositories block.

Add permissions to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />

<!-- Optional: lets the SDK avoid POSTs while offline -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**Version 3.0.0 upgrade:** Set your app's `minSdk` to at least 23 (Android 6.0) and `compileSdk` to at least 36. Version 2.0.0 supports API 21. **Current source and builds require Java 17 and `compileSdk` 37 or later** because of the current AndroidX dependencies.

### 2. Initialize

```java
import com.oursprivacy.android.opmetrics.OursPrivacyAPI;
import com.oursprivacy.android.opmetrics.OursPrivacyInitOptions;

OursPrivacyAPI op = new OursPrivacyAPI(context);
op.initialize(
    "YOUR_API_TOKEN",
    OursPrivacyInitOptions.builder()
        .trackAutomaticEvents(true)
        .build()
);
```

The SDK connects to `https://cdn.oursprivacy.com` by default — no endpoint configuration needed. Every public method is a no-op (with a warning log) until `initialize` has been called.

Hold a single instance for the lifetime of your app — typically on a custom `Application` subclass or in a DI container.
`trackAutomaticEvents` defaults to `false`; pass `true` to collect lifecycle events. Explicit `trackScreen()` calls work with either setting.
**Upgrading to 3.1.0:** crash capture now requires explicit `.trackAutomaticCrashes(true)`, even when lifecycle tracking is enabled. It defaults to `false`; opt in to continue sending legacy `$ae_crashed` exception telemetry.

### 3. Track Events

```java
op.track("Button Pressed");

JSONObject props = new JSONObject();
props.put("value", 49.99);
props.put("currency", "USD");
op.track("Purchase", props);
```

### 4. Identify Users

After login, link events to a user via `OursPrivacyUserProperties`:

```java
import com.oursprivacy.android.opmetrics.OursPrivacyUserProperties;

op.identify(
    OursPrivacyUserProperties.builder()
        .externalId("user-123")
        .email("user@example.com")
        .firstName("Jane")
        .build()
);
```

### 5. Flush

Events are batched and sent every 10 seconds by default. To send immediately:

```java
op.flush();
```

---

## Complete Example

```kotlin
class MyApplication : Application() {
    lateinit var op: OursPrivacyAPI

    override fun onCreate() {
        super.onCreate()
        op = OursPrivacyAPI(this)
        op.initialize(
            "YOUR_API_TOKEN",
            OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true)
                .build()
        )
    }

    fun trackPurchase() {
        op.track("Purchase", JSONObject(mapOf("value" to 49.99, "currency" to "USD")))
    }

    fun onScheduleDestinationShown() {
        op.trackScreen("Schedule")
    }
}
```

---

## API Reference

### Initialization

#### `OursPrivacyAPI(Context)`

Constructor — takes only the application context. Call {@code initialize} exactly once before any other method.

#### `void initialize(String token, OursPrivacyInitOptions options)`

Applies your project token and bootstrap options. Must be called exactly once. Pass `null` for `options` to accept all defaults. Every other public method is a no-op (and logs a warning) until `initialize` has run.

`OursPrivacyInitOptions` is a builder POJO:

| Field | Notes |
| --- | --- |
| `trackAutomaticEvents` | Emit the canonical `$mobile_*` lifecycle facts and legacy `$app_open`, `$ae_first_open`, `$ae_session`, and `$ae_updated`. Default false. Does not collect screen names. |
| `trackAutomaticCrashes` | Capture uncaught exceptions as legacy `$ae_crashed` events with `$ae_crashed_reason`. Default false; independent of `trackAutomaticEvents`. |
| `serverURL` | Override the ingest base URL. |
| `visitorId` | Pre-set a `visitor_id`. Sets `is_manually_set_id: true`. |
| `initialURL` | Parsed as a deep link on init (UTM + click IDs). Respects opt-out. |
| `defaultEventProperties` | Merged into manual `track()` calls; canonical `$mobile_*` facts omit caller defaults. |
| `defaultUserCustomProperties` | Merged into `userProperties.custom_properties` on every track + identify. |
| `defaultUserConsentProperties` | Merged into `userProperties.consent`. Subject to the consent-omission guard documented in [Default Properties](#default-properties). |
| `optedOutByDefault` | If true and no prior opt-out decision is persisted, opts the user out on first launch. |
| `onIngestRejected` | Optional listener for rejected `/ingest` items. Default off. Receives only `distinct_id` and a stable error code. |

### Core Tracking

#### `void track(String eventName)`
#### `void track(String eventName, JSONObject eventProperties)`
#### `void track(String eventName, JSONObject eventProperties, OursPrivacyUserProperties userProperties)`

Fires an event. `eventProperties` end up on the wire under `eventProperties`; `userProperties` get merged with the store-level default user-property bags and end up under `userProperties`.
Tracking, screen, identity, and default-property changes are ordered on a background worker. Storage failures are logged and the affected operation is dropped; they do not throw into the host app. Invalid explicit screen labels still throw `IllegalArgumentException`.

Bootstrap migration must persist before upload workers start. Storage failures during `initialize()` are logged and disable that SDK instance without throwing into the host app. Tracking and consent calls become no-ops, and `hasOptedOutTracking()` returns true. Create a new SDK instance after storage recovers, or retry on the next launch. Legacy installation evidence and queued events remain available for that retry.

Names beginning with `$mobile_` are reserved for SDK telemetry and are ignored by manual `track()` calls. Use `trackScreen()` for a screen view; other manual event names, including healthcare and legacy `$ae_*` names, remain available.

#### `void trackScreen(String name)`

Queues a `$mobile_screen_view` with `eventProperties.screen_name`. Use a stable developer-chosen destination label of 1–80 characters, starting with an ASCII letter and then containing only ASCII letters, digits, spaces, `_`, or `-`, with no trailing space. Empty names and labels outside those character and length rules throw `IllegalArgumentException`. Integrations must never pass parameterized labels: an alphanumeric value such as `Patient 123` passes character validation but is not a stable screen name. A repeated callback for the active screen does not queue another view.

#### `void identify(OursPrivacyUserProperties userProperties)`

Fires a `$identify` event. The same merge as `track()` applies. Caller stitches to external systems via `externalId` on the typed properties — there is no separate id argument.

#### `void flush()`

Forces a flush of the event queue. Batches contain at most 50 events sharing the visitor ID and identity mode captured at enqueue.

Set a rejection listener at initialization if you need to inspect events that the server rejects:

```java
OursPrivacyInitOptions options = OursPrivacyInitOptions.builder()
    .onIngestRejected((distinctId, code) -> {
        // Record the event ID and code in your own diagnostics.
    })
    .build();
```

The listener runs on the SDK worker after the batch is durably removed from the queue. It receives only the rejected event's `distinct_id` and rejection code; event properties, patient fields, and payloads are never passed to it. Valid indexed responses acknowledge accepted and rejected items together and ignore unknown fields; `visitor_id` is optional. For compatibility, an empty successful HTTP body or a JSON object without indexed fields acknowledges the batch. Explicit `success:false` rejects the batch with `ingest_rejected`, unless `retryable:true` is present. HTTP 4xx rejects with `http_STATUS`, except 403, 408, 425, and 429, which retain events for retry.

Nonempty malformed bodies, nonobject JSON, and invalid indexed responses retry for the same queued head. On the fifth malformed response, the submitted batch is durably rejected with `invalid_acknowledgment`; the attempt count resets when the worker restarts. Offline, network, and server failures retain events for later retry. Rejection callbacks stop when consent is revoked.

#### `void reset()`

Clears the four default-property bags and rotates `visitor_id`. Preserves the opt-out flag, queued events, and pending lifecycle facts with their captured identity. Use `optOutTracking()` to purge queued data.

### Mobile Screens and Lifecycle

Call `trackScreen()` from your navigation destination callback, using fixed labels instead of route arguments or visible content:

```java
navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
    if (destination.getId() == R.id.scheduleFragment) {
        op.trackScreen("Schedule");
    } else if (destination.getId() == R.id.visitDetailsFragment) {
        op.trackScreen("Visit Details");
    }
});
```

For Compose or custom navigation, call the same method when the stable destination changes. Activity transitions alone do not identify those destinations, and this SDK version does not automatically collect screen views. Do not put patient identifiers or other PHI in screen labels.

With `trackAutomaticEvents(true)`, the SDK emits these lifecycle facts:

| Event | When | Event properties |
| --- | --- | --- |
| `$mobile_first_open` | First eligible tracked foreground open for this install and token | None |
| `$mobile_app_open` | Each foreground entry | None |
| `$mobile_session_start` | First tracked foreground entry in a session | None |
| `$mobile_session_engagement` | Positive foreground-time checkpoint, screen change, or background | `engagement_duration_ms`; `screen_name` when a tracked screen was active |
| `$mobile_session_end` | Best effort when a session expires or is explicitly ended | None |
| `$mobile_app_update` | First tracked open after a known version or build changes; newly available or temporarily missing metadata alone does not trigger an update | `previous_app_version`, `previous_app_build` when known |
| `$mobile_screen_view` | Explicit `trackScreen(name)` call | `screen_name` |

Sessions expire after 30 minutes of inactivity. An expired session ends only if its session-start fact was emitted, using its last activity time. Background manual tracking refreshes inactivity. Re-report the current screen on foreground resume when the navigation callback does not run, because backgrounding clears the active screen. Engagement durations are integer milliseconds and a screen change assigns the preceding positive delta to the previous screen. Manual `track()` and `trackScreen()` still work when automatic tracking is off. Full `optOutTracking()` suppresses all of them and rotates `visitor_id`; a later opt-in starts a new session under that visitor.

Every newly tracked event carries `defaultProperties.sid`, the session start in epoch milliseconds as a decimal string (normally 13 digits). SDK-owned `eventProperties` include `mobile_contract_version: 1`, `mobile_occurred_at` (UTC ISO-8601 with milliseconds), and `app_version` / `app_build` when available. SDK session metadata overrides caller properties. App version/build metadata overrides caller values when known; caller app metadata is retained when the SDK cannot determine it. Existing OS, device, and SDK `version` defaults retain their meanings; `mobile_session_started_at`, `mobile_platform`, and top-level `time` are omitted. Canonical `$mobile_*` facts omit caller event properties, attribution, and user properties; manual events retain caller data.

Rapid session rotations advance the start timestamp by at least one millisecond to keep IDs distinct, including after a process restart or a small clock rollback. A clock rollback of more than five minutes starts a session at the current wall clock.

Event properties allow up to 200 keys and must serialize to fewer than 1,048,576 UTF-16 code units. Up to 196 ordinary caller keys leave room for all four common SDK fields within the key limit. If SDK metadata exceeds either budget, valid caller data is preserved without those fields. Such events upload without contributing to Mobile Analytics; queued metadata overflows use the same fallback.

When upgrading an existing install, legacy launch or visitor identity evidence suppresses a new first-open and seeds observed app metadata. A known previous build can produce an app-update on that first upgraded open. Stored UUID sessions rotate without repeating first-open. Pending facts and queued events retain their captured IDs and timestamps; queued UUID session IDs migrate from a valid stored session-start timestamp. Legacy rows with an invalid or missing start upload without `sid`; activity sessions are unavailable for those rows, and queue processing continues. Future-dated pending facts recover to the current clock without losing their event IDs or blocking later events.

During migration, the enabled lifecycle path also emits legacy `$app_open`, `$ae_first_open`, `$ae_session`, and `$ae_updated`. Count one stream: canonical `$mobile_*` events for Mobile Analytics, or legacy events for existing reports. Counting both double-counts overlapping lifecycle activity; legacy events have different meanings and no equivalent screen coverage.

**Crash capture migration:** Earlier versions installed an exception handler by default and could send `$ae_crashed` when automatic events were enabled. To continue receiving the same legacy event and reason field, explicitly pass `.trackAutomaticCrashes(true)` during initialization. This works with `trackAutomaticEvents(false)`; lifecycle tracking alone no longer enables crash capture. Full `optOutTracking()` suppresses crash events, and the `com.oursprivacy.android.Config.DisableExceptionHandler` manifest flag prevents handler registration even with crash opt-in. Mobile Analytics does not consume crash telemetry.

### Default Properties

The SDK maintains three caller-controlled bags merged into manual tracks and identify events:

- **`updateDefaultEventProperties(JSONObject)`** → merged into `eventProperties` on every `track()`.
- **`updateDefaultUserCustomProperties(JSONObject)`** → merged into `userProperties.custom_properties`.
- **`updateDefaultUserConsentProperties(JSONObject)`** → merged into `userProperties.consent`. When neither the defaults nor the per-call data carry any consent keys, `consent` is omitted from the wire entirely — emitting an empty `consent: {}` can clobber consent that was previously written by another path.

### Configuration

| Method | Default | Notes |
| --- | --- | --- |
| `setServerURL(String)` | `https://cdn.oursprivacy.com` | Proxy support via `setServerURL(String, ProxyServerInteractor)`. |
| `setFlushBatchSize(int)` | 50 | Clamped to `[1, 50]`. |
| `setFlushOnBackground(boolean)` | true | Flush when the app moves to background. |
| `setLoggingEnabled(boolean)` | false | Verbose worker logging. |

### Identity

#### `String getVisitorId()`

Returns the in-memory `visitor_id` (UUID).

#### `void setVisitorId(String)`

Replaces the auto-generated `visitor_id` and sets `is_manually_set_id: true` on every subsequent envelope. Use this to stitch a visitor between web and app.

### Deep Link Attribution

#### `void trackDeepLink(String url)`

Parses `url` for the six canonical UTM keys, twenty-six click-ID keys, and the `ours_visitor_id` stitch parameter. Fires `$deep_link_opened` without the original URL, replaces the attribution overlay (so stale UTMs don't leak between links), and — if `ours_visitor_id` is present — calls `setVisitorId()`.

Attribution overlays live in `defaultProperties`, not `userProperties`.

**Migration:** `$deep_link_opened` no longer includes `eventProperties.url`. Update queries that read that property to use the allowlisted attribution fields in `defaultProperties`. Supply only PHI-free UTM and click-ID values and an opaque `ours_visitor_id`; use stable, PHI-free labels for `trackScreen()`, never route URLs or patient details.

### Privacy Controls

- **`optOutTracking()`** — clears the in-flight queue, wipes the four default-property bags, rotates `visitor_id`, and persists the opt-out flag. Subsequent `track()` / `trackScreen()` / `identify()` / `flush()` calls are no-ops.
- **`optInTracking()`** — clears the opt-out flag. With automatic events enabled and an Activity already foreground, it queues the first eligible canonical open facts before `$opt_in`; with automatic events off, it sends only `$opt_in`.
- **`hasOptedOutTracking()`** — current persisted opt-out state.

Consent methods wait synchronously for durable storage and transport cancellation. Revocation immediately fences uploads and skips queued work from the prior consent generation, including lifecycle timers. Opt-in uses the Activity's latest pause/resume signal to avoid opening a foreground session while the app is backgrounded.

---

## Payload Structure

Every flush is a single JSON POST to `{serverURL}/ingest` with this shape:

```json
{
  "token": "<project token>",
  "is_manually_set_id": false,
  "data": [
    {
      "event": "Purchase",
      "visitor_id": "1f0c…",
      "distinct_id": "ae8a…",
      "eventProperties": {
        "value": 49.99,
        "currency": "USD",
        "mobile_contract_version": 1,
        "mobile_occurred_at": "2026-10-06T12:00:05.000Z",
        "app_version": "2.5.1",
        "app_build": "42"
      },
      "userProperties": null,
      "defaultProperties": {
        "device_type": "mobile",
        "os_name": "Android",
        "os_version": "14",
        "device_vendor": "Google",
        "device_model": "Pixel 8",
        "screen_width": 1080,
        "screen_height": 2400,
        "version": "<sdk-version>",
        "sid": "1791288000000"
      }
    }
  ]
}
```

- `visitor_id` is stable per install (or per `setVisitorId(…)`).
- `distinct_id` is a fresh UUID per event.
- `userProperties` is `null` when the caller passes no per-call properties and no default user bags are configured.
- `eventProperties` includes SDK mobile metadata, except for manual events where it would exceed the key or serialized size limit.
- Mobile occurrence time lives in `eventProperties.mobile_occurred_at`.
- Unknown fields are dropped server-side.

**Naming**: camelCase at the API surface (`externalId`, `phoneNumber`, `customProperties`); snake_case on the wire (`external_id`, `phone_number`, `custom_properties`).

---

## FAQ

**Where do typed user properties land on the wire?**
Each named field (`email`, `externalId`, `phoneNumber`, `firstName`, `lastName`, `gender`, `dateOfBirth`, `city`, `state`, `zip`, `country`, `companyName`, `jobTitle`, `ip`) becomes a snake_case top-level key under `userProperties`. `customProperties` and `consent` are nested objects under the same parent.

**Why is `consent` sometimes missing from `userProperties`?**
Emitting an empty `consent: {}` can clobber consent flags already persisted on the visitor record. The SDK omits the key entirely when both the per-call and default consent bags are empty.

**How big is a flush?**
At most 50 events per POST. Configure with `setFlushBatchSize(int)` (clamped to `[1, 50]`).

**Do I need to call `flush()` on shutdown?**
It's good practice. The SDK flushes on app background by default (toggle via `setFlushOnBackground(boolean)`), but a manual `flush()` in `onDestroy()` guarantees the queue is drained.

**Can I use this with a proxy?**
Yes. `setServerURL(String, ProxyServerInteractor)` lets you intercept requests for header injection or response observation.

---

## Development

The current source requires Java 17 and Android SDK platform 37. Apps consuming an SDK built from this source must also use `compileSdk` 37 or later because of the updated AndroidX dependencies. The SDK supports API 23 and later; the demo requires API 24 and later because of Navigation 2.10.

**Run JVM tests, lint, and compile the demo:**

```sh
./gradlew test lint :oursprivacydemo:assembleDebug
```

The SDK tests under `src/test/` use Robolectric to verify the ingest envelope without a device. Lint uses a baseline in each module.

**Run the demo app:** copy `local.properties.example` to `local.properties` and add your token, then run the `oursprivacydemo` target. The demo uses the local SDK module by default. Its tracking screen asks for a token when none is configured.

**Verify unsigned local publication metadata:**

```sh
./gradlew :oursprivacy-android:publishToMavenLocal -PRELEASE_SIGNING_ENABLED=false -Dmaven.repo.local="$PWD/build/maven-local"
python3 .github/scripts/verify-publication.py
```

The generated POM and module metadata are in `oursprivacy-android/build/publications/maven/`; local artifacts remain under `build/maven-local/`.

**Test the packaged SDK before release:** Publish this branch to a repository-local Maven directory, then compile the demo against that artifact:

```sh
./gradlew :oursprivacy-android:publishToMavenLocal -PRELEASE_SIGNING_ENABLED=false -Dmaven.repo.local="$PWD/build/maven-local"
./gradlew :oursprivacydemo:assembleDebug -PusePublished=true -PuseLocalPublished=true -Dmaven.repo.local="$PWD/build/maven-local"
```

After a new version reaches Maven Central, use `-PusePublished=true -PpublishedSdkVersion=<released version>` without `-PuseLocalPublished` to verify the released artifact.

---

## Support

- Docs: [docs.oursprivacy.com/docs/android-sdk](https://docs.oursprivacy.com/docs/android-sdk)
- Issues: [github.com/with-ours/ours-privacy-android/issues](https://github.com/with-ours/ours-privacy-android/issues)
