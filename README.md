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

Add to your app's `build.gradle` dependencies:

```gradle
implementation "com.oursprivacy:oursprivacy-android:2.0.0"
```

Make sure `mavenCentral()` is listed in your repositories block.

Add permissions to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />

<!-- Optional: lets the SDK avoid POSTs while offline -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**Min SDK:** API 21 (Android 5.0).

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
                .defaultEventProperties(mapOf("app_version" to BuildConfig.VERSION_NAME))
                .build()
        )
    }

    fun trackPurchase() {
        op.track("Purchase", JSONObject(mapOf("value" to 49.99, "currency" to "USD")))
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
| `trackAutomaticEvents` | Emit built-in lifecycle events (`$app_open`, `$ae_first_open`, `$ae_session`, `$ae_updated`). Default false. |
| `serverURL` | Override the ingest base URL. |
| `visitorId` | Pre-set a `visitor_id`. Sets `is_manually_set_id: true`. |
| `initialURL` | Parsed as a deep link on init (UTM + click IDs). Respects opt-out. |
| `defaultEventProperties` | Merged into every `track()` call. |
| `defaultUserCustomProperties` | Merged into `userProperties.custom_properties` on every track + identify. |
| `defaultUserConsentProperties` | Merged into `userProperties.consent`. Subject to the consent-omission guard documented in [Default Properties](#default-properties). |
| `optedOutByDefault` | If true and no prior opt-out decision is persisted, opts the user out on first launch. |

### Core Tracking

#### `void track(String eventName)`
#### `void track(String eventName, JSONObject eventProperties)`
#### `void track(String eventName, JSONObject eventProperties, OursPrivacyUserProperties userProperties)`

Fires an event. `eventProperties` end up on the wire under `eventProperties`; `userProperties` get merged with the store-level default user-property bags and end up under `userProperties`.

#### `void identify(OursPrivacyUserProperties userProperties)`

Fires a `$identify` event. The same merge as `track()` applies. Caller stitches to external systems via `externalId` on the typed properties — there is no separate id argument.

#### `void flush()`

Forces a flush of the event queue. The worker drains in batches (default 50, max 50) until the queue is empty.

#### `void reset()`

Clears the event queue, the four default-property bags, and rotates `visitor_id`. Preserves the opt-out flag.

### Default Properties

The SDK maintains three caller-controlled bags merged into every event:

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

Parses `url` for the six canonical UTM keys, twenty-six click-ID keys, and the `ours_visitor_id` stitch parameter. Fires `$deep_link_opened` with the original URL, replaces the attribution overlay (so stale UTMs don't leak between links), and — if `ours_visitor_id` is present — calls `setVisitorId()`.

Attribution overlays live in `defaultProperties`, not `userProperties`.

### Privacy Controls

- **`optOutTracking()`** — clears the in-flight queue, wipes the four default-property bags, and persists the opt-out flag. Subsequent `track()` / `identify()` / `flush()` calls are no-ops.
- **`optInTracking()`** — clears the opt-out flag and fires `$opt_in`.
- **`hasOptedOutTracking()`** — current persisted opt-out state.

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
      "eventProperties": { "value": 49.99, "currency": "USD" },
      "userProperties": null,
      "defaultProperties": {
        "device_type": "mobile",
        "os_name": "Android",
        "os_version": "14",
        "device_vendor": "Google",
        "device_model": "Pixel 8",
        "screen_width": 1080,
        "screen_height": 2400,
        "version": "2.0.0"
      }
    }
  ]
}
```

- `visitor_id` is stable per install (or per `setVisitorId(…)`).
- `distinct_id` is a fresh UUID per event.
- `userProperties` is `null` when the caller passes no per-call properties and no default user bags are configured.
- `eventProperties` is `null` when the merged event-property bag is empty.
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

**Run the JVM tests + compile the demo:**

```sh
./gradlew test :oursprivacydemo:assembleDebug
```

The unit + integration tests under `src/test/` use Robolectric to verify the canonical envelope shape end-to-end — no device or emulator needed.

**Run the demo app:** copy `local.properties.example` → `local.properties` and add your token, then open the project in Android Studio and run the `oursprivacydemo` target. The demo links to the local SDK via `project(":")` so source changes are picked up without publishing.

**Inspect the wire payload:** `tools/payload-recorder/server.py` is a small Python HTTP server that records every POST the SDK sends. Point the demo at it by setting `RECORDER_URL` in `local.properties`.

---

## Support

- Docs: [docs.oursprivacy.com/docs/android-sdk](https://docs.oursprivacy.com/docs/android-sdk)
- Issues: [github.com/with-ours/ours-privacy-android/issues](https://github.com/with-ours/ours-privacy-android/issues)
