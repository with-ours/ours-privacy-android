# Ours Privacy Android SDK

[![Maven Central](https://img.shields.io/maven-central/v/com.oursprivacy.android/oursprivacy-android)](https://central.sonatype.com/artifact/com.oursprivacy.android/oursprivacy-android)
[![Apache License](https://img.shields.io/github/license/with-ours/ours-privacy-android)](https://oursprivacy.com)
[![Documentation](https://img.shields.io/badge/Documentation-blue)](https://docs.oursprivacy.com/docs/android-sdk)

Privacy-first analytics for Android.

- [Maven Central](https://central.sonatype.com/artifact/com.oursprivacy.android/oursprivacy-android)
- [GitHub](https://github.com/with-ours/ours-privacy-android)
- [Docs](https://docs.oursprivacy.com/docs/android-sdk)

---

## Table of Contents

- [Quick Start](#quick-start)
- [Complete Example](#complete-example)
- [API Reference](#api-reference)
  - [Initialization](#initialization)
  - [Core Tracking](#core-tracking)
  - [Configuration](#configuration)
  - [Privacy Controls](#privacy-controls)
- [Payload Structure](#payload-structure)
- [Migration from 1.x](#migration-from-1x)
- [FAQ](#faq)
- [Support](#support)

---

## Quick Start

### 1. Install

Add to your app's `build.gradle` dependencies:

```gradle
implementation "com.oursprivacy.android:oursprivacy-android:2.0.0"
```

Make sure `mavenCentral()` is listed in your repositories block.

Add permissions to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />

<!-- Optional: lets the SDK batch intelligently based on connectivity -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 2. Initialize

```java
import com.oursprivacy.android.opmetrics.OursPrivacyAPI;

OursPrivacyAPI op = OursPrivacyAPI.getInstance(context, "YOUR_API_TOKEN", true);
```

The SDK connects to `https://cdn.oursprivacy.com` by default — no endpoint configuration needed.

Hold a single `OursPrivacyAPI` instance for the lifetime of your app — typically in `Application.onCreate()`.

### 3. Track Events

```java
op.track("Button Pressed");
op.track("Purchase", new JSONObject().put("value", 49.99).put("currency", "USD"));
```

### 4. Identify Users

After login, link events to a user:

```java
HashMap<String, Object> userProperties = new HashMap<>();
userProperties.put("email", "user@example.com");
userProperties.put("external_id", "user-123");

op.identify("user-123", userProperties);
```

### 5. Flush

Events are batched and sent every 60 seconds by default. To send immediately:

```java
op.flush();
```

---

## Complete Example

```java
import android.app.Application;
import com.oursprivacy.android.opmetrics.OursPrivacyAPI;

public class MyApp extends Application {
    private OursPrivacyAPI op;

    @Override
    public void onCreate() {
        super.onCreate();
        op = OursPrivacyAPI.getInstance(this, "YOUR_API_TOKEN", true);
    }

    public OursPrivacyAPI getAnalytics() {
        return op;
    }
}

// In an Activity:
OursPrivacyAPI op = ((MyApp) getApplication()).getAnalytics();
op.track("Screen Viewed", new JSONObject().put("screen", "Home"));
```

---

## API Reference

### Initialization

#### `OursPrivacyAPI.getInstance(context, token, trackAutomaticEvents)`

Returns the SDK instance. Creates it on first call; returns the same instance on subsequent calls. Call this in `Application.onCreate()` and hold the reference.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `context` | `Context` | Yes | Application context |
| `token` | `String` | Yes | Your project token |
| `trackAutomaticEvents` | `boolean` | Yes | Record session and lifecycle events automatically |

```java
OursPrivacyAPI op = OursPrivacyAPI.getInstance(context, "YOUR_API_TOKEN", true);
```

---

### Core Tracking

#### `op.track(eventName)`
#### `op.track(eventName, properties)`

Track an event with optional properties.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `eventName` | `String` | Yes | Name of the event |
| `properties` | `JSONObject` | No | Key/value pairs to attach to the event |

**Returns:** `void`

```java
op.track("Page View");
op.track("Purchase", new JSONObject().put("value", 49.99).put("currency", "USD"));
```

You can also pass a `Map<String, Object>` instead of `JSONObject`:

```java
Map<String, Object> props = new HashMap<>();
props.put("value", 49.99);
op.trackMap("Purchase", props);
```

---

#### `op.identify(distinctId, userProperties)`

Associate all future `track()` calls with the given user identity. Call this after a user logs in.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `distinctId` | `String` | Yes | Your system's user ID |
| `userProperties` | `HashMap<String, Object>` | No | User attributes to attach |

**Returns:** `void`

```java
HashMap<String, Object> props = new HashMap<>();
props.put("email", "jane@example.com");
props.put("external_id", "db-user-456");
props.put("first_name", "Jane");

op.identify("db-user-456", props);
```

---

#### `op.flush()`

Push all queued events to the server immediately. Useful before the app goes to background or a user logs out.

**Returns:** `void`

```java
op.flush();
```

---

#### `op.reset()`

Clear the current user identity. Generates a new random visitor ID. Call this when a user logs out.

**Returns:** `void`

```java
op.reset();
```

---

### Configuration

#### `op.setServerURL(serverURL)`

Override the ingest URL after initialization. Useful for routing through a proxy or pointing at a local capture server.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `serverURL` | `String` | Yes | Base URL for API requests |

**Returns:** `void`

```java
op.setServerURL("https://your-proxy.example.com");
```

---

#### `op.setFlushBatchSize(n)`

Set the maximum number of events sent in a single network request. Maximum value is 50; values above 50 are clamped.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `n` | `int` | Yes | Number of events per batch (max 50) |

**Returns:** `void`

```java
op.setFlushBatchSize(25);
```

---

#### `op.setEnableLogging(enabled)`

Enable or disable debug logging. Disabled by default.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `enabled` | `boolean` | Yes | Whether to enable SDK logging |

**Returns:** `void`

```java
op.setEnableLogging(true);
```

You can also enable debug logging via `AndroidManifest.xml` without a code change:

```xml
<application>
    <meta-data
        android:name="com.oursprivacy.android.Config.EnableDebugLogging"
        android:value="true" />
</application>
```

---

### Privacy Controls

#### `op.optOutTracking()`

Stop all tracking immediately. Queued events that have not been flushed are discarded. Call `flush()` first if you want to preserve them.

**Returns:** `void`

```java
op.flush();
op.optOutTracking();
```

---

#### `op.optInTracking()`

Resume tracking after a previous call to `optOutTracking()`. This also sends an `$opt_in` event to the server.

**Returns:** `void`

```java
op.optInTracking();
```

---

#### `op.hasOptedOutTracking()`

Check whether the current user has opted out of tracking.

**Returns:** `boolean`

```java
if (op.hasOptedOutTracking()) {
    // show consent UI
}
```

---

## Payload Structure

The SDK sends a JSON body to `POST /ingest` on the configured server URL.

```json
{
  "token": "your-project-token",
  "is_manually_set_id": false,
  "data": [
    {
      "event": "Purchase",
      "visitor_id": "550e8400-e29b-41d4-a716-446655440000",
      "distinct_id": "ecff9f0e-d4f8-4d9e-b2f8-8d9b2fcdf7b2",
      "eventProperties": {
        "value": 49.99,
        "currency": "USD"
      },
      "userProperties": {
        "custom_properties": {},
        "consent": {}
      },
      "defaultProperties": {
        "device_type": "mobile",
        "os_name": "Android",
        "os_version": "14",
        "screen_width": 1080,
        "screen_height": 2400,
        "visitor_id": "550e8400-e29b-41d4-a716-446655440000"
      }
    }
  ]
}
```

| Field | Description |
|-------|-------------|
| `token` | Your project token |
| `is_manually_set_id` | `true` when visitor ID was set explicitly |
| `data` | Array of event objects in this batch |
| `event` | Event name. Identify events use `$identify` |
| `visitor_id` | Stable visitor UUID for this install |
| `distinct_id` | Per-event UUID for this occurrence |
| `eventProperties` | Properties from `track()` |
| `userProperties.custom_properties` | Custom user attributes from `identify()` |
| `userProperties.consent` | Consent flags from `identify()` |
| `defaultProperties` | Automatically collected device/SDK metadata |

---

## Migration from 1.x

2.0.0 is a hard break. Key removals:

- **People/Group API removed.** `getPeople()`, group methods, and all `people.*` calls are gone.
- **Super-properties removed.** `registerSuperProperties`, `unregisterSuperProperty`, `clearSuperProperties`, `getSuperProperties` — use `defaultProperties` on `track()` instead.
- **Timed events removed.** `timeEvent`, `eventElapsedTime`, `clearTimedEvent`.
- **Named instances removed.** All `getInstance(context, token, name, ...)` overloads are gone. Use the single `getInstance(context, token, trackAutomaticEvents)`.
- **Endpoint updated.** Default endpoint is now `https://cdn.oursprivacy.com/ingest`.
- **Manifest keys renamed.** `com.oursprivacy.android.MPConfig.*` → `com.oursprivacy.android.Config.*`.

---

## FAQ

**Why aren't my events showing up?**

Events batch and flush every 60 seconds by default. Call `op.flush()` to send immediately. Enable debug logging with `op.setEnableLogging(true)` to see what's happening. Check that `hasOptedOutTracking()` returns `false`.

**Can I run more than one instance in the same app?**

`getInstance` uses your token as the instance key, so calling it with different tokens returns different instances. Use one token per app in most cases.

**What Android versions are supported?**

minSdk 21 (Android 5.0 Lollipop) and above.

---

## Support

- [Documentation](https://docs.oursprivacy.com/docs/android-sdk)
- [GitHub Issues](https://github.com/with-ours/ours-privacy-android/issues)
- Email: support@oursprivacy.com
