package com.oursprivacy.android.opmetrics;

import com.oursprivacy.android.util.OPLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.UUID;

/**
 * Composes the inner {@code data[]} elements of the ingest envelope:
 * {@code {event, visitor_id, distinct_id, eventProperties, userProperties, defaultProperties}}.
 *
 * The user-property merge applies on BOTH track and identify, including the
 * empty-consent omission rule.
 */
final class Track {

    /** State the composer needs at call time. */
    static final class Context {
        final String visitorId;
        final JSONObject defaultEventProperties;
        final JSONObject defaultUserCustomProperties;
        final JSONObject defaultUserConsentProperties;
        final JSONObject attributionDefaultProperties;
        final JSONObject baseDefaultProperties;
        final MobileSession.MobileSnapshot mobileSnapshot;

        Context(String visitorId,
                JSONObject defaultEventProperties,
                JSONObject defaultUserCustomProperties,
                JSONObject defaultUserConsentProperties,
                JSONObject attributionDefaultProperties,
                JSONObject baseDefaultProperties,
                MobileSession.MobileSnapshot mobileSnapshot) {
            this.visitorId = visitorId;
            this.defaultEventProperties = defaultEventProperties != null ? defaultEventProperties : new JSONObject();
            this.defaultUserCustomProperties = defaultUserCustomProperties != null ? defaultUserCustomProperties : new JSONObject();
            this.defaultUserConsentProperties = defaultUserConsentProperties != null ? defaultUserConsentProperties : new JSONObject();
            this.attributionDefaultProperties = attributionDefaultProperties != null ? attributionDefaultProperties : new JSONObject();
            this.baseDefaultProperties = baseDefaultProperties != null ? baseDefaultProperties : new JSONObject();
            this.mobileSnapshot = mobileSnapshot;
        }
    }

    /** Fixed-ID mobile facts use captured facts and SDK metadata only. */
    static JSONObject composeTrackEvent(String eventName,
                                        JSONObject eventProperties,
                                        JSONObject userProperties,
                                        Context ctx) throws JSONException {
        return composeTrackEvent(eventName, eventProperties, userProperties, ctx, null);
    }

    static JSONObject composeTrackEvent(String eventName,
                                        JSONObject eventProperties,
                                        JSONObject userProperties,
                                        Context ctx,
                                        String fixedDistinctId) throws JSONException {
        JSONObject mergedEvent = new JSONObject();
        if (fixedDistinctId == null) mergeOnto(mergedEvent, ctx.defaultEventProperties);
        if (eventProperties != null) {
            mergeOnto(mergedEvent, eventProperties);
        }
        if (fixedDistinctId != null) {
            mergedEvent = retainKeys(mergedEvent, CANONICAL_EVENT_PROPERTIES);
        }

        // $distinct_id override lets callers pin a per-event distinct_id (replay stitching).
        final String distinctId;
        final Object override = mergedEvent.opt("$distinct_id");
        if (fixedDistinctId != null) {
            distinctId = fixedDistinctId;
            mergedEvent.remove("$distinct_id");
        } else if (override instanceof String && !((String) override).isEmpty()) {
            distinctId = (String) override;
            mergedEvent.remove("$distinct_id");
        } else {
            distinctId = UUID.randomUUID().toString();
        }

        final JSONObject mergedUser = fixedDistinctId == null
                ? mergeUserProperties(userProperties, ctx.defaultUserCustomProperties,
                        ctx.defaultUserConsentProperties)
                : null;

        final JSONObject defaults = mergeOnto(new JSONObject(), ctx.baseDefaultProperties);
        if (fixedDistinctId == null) mergeOnto(defaults, ctx.attributionDefaultProperties);
        if (ctx.mobileSnapshot != null) {
            clearMobileMetadata(defaults);
            clearMobileMetadata(mergedEvent);
            mergeOnto(defaults, ctx.mobileSnapshot.defaultProperties());
            JSONObject metadata = ctx.mobileSnapshot.eventProperties();
            JSONObject candidate = mergeOnto(shallowCopy(mergedEvent), metadata);
            if (fitsEventPropertyBudget(candidate)) {
                mergedEvent = candidate;
            } else {
                warnMobileMetadataOmitted();
            }
        }

        final JSONObject item = new JSONObject();
        item.put("event", eventName == null ? "op_event" : eventName);
        item.put("visitor_id", ctx.visitorId);
        item.put("distinct_id", distinctId);
        item.put("eventProperties", mergedEvent.length() == 0 ? JSONObject.NULL : mergedEvent);
        item.put("userProperties", mergedUser == null ? JSONObject.NULL : mergedUser);
        item.put("defaultProperties", defaults);
        return item;
    }

    /** Identify carries SDK event metadata and the merged visitor profile. */
    static JSONObject composeIdentifyEvent(JSONObject userProperties, Context ctx) throws JSONException {
        final JSONObject mergedUser = mergeUserProperties(
                userProperties,
                ctx.defaultUserCustomProperties,
                ctx.defaultUserConsentProperties);

        final JSONObject defaults = mergeOnto(new JSONObject(), ctx.baseDefaultProperties);
        mergeOnto(defaults, ctx.attributionDefaultProperties);
        final JSONObject eventProperties = new JSONObject();
        if (ctx.mobileSnapshot != null) {
            clearMobileMetadata(defaults);
            mergeOnto(defaults, ctx.mobileSnapshot.defaultProperties());
            mergeOnto(eventProperties, ctx.mobileSnapshot.eventProperties());
        }

        final JSONObject item = new JSONObject();
        item.put("event", "$identify");
        item.put("visitor_id", ctx.visitorId);
        item.put("distinct_id", UUID.randomUUID().toString());
        item.put("eventProperties", eventProperties.length() == 0 ? JSONObject.NULL : eventProperties);
        item.put("userProperties", mergedUser == null ? JSONObject.NULL : mergedUser);
        item.put("defaultProperties", defaults);
        return item;
    }

    private static void clearMobileMetadata(JSONObject properties) {
        for (String key : MOBILE_METADATA) properties.remove(key);
        properties.remove("mobile_session_started_at");
        properties.remove("mobile_platform");
    }

    static JSONObject migrateQueuedEvent(JSONObject event) throws JSONException {
        JSONObject migrated = new JSONObject(event.toString());
        JSONObject defaults = migrated.optJSONObject("defaultProperties");
        if (defaults == null) return normalizeQueuedPropertyCapacity(migrated);
        String sid = defaults.optString("sid");
        boolean legacyMetadata = defaults.has("mobile_session_started_at")
                || defaults.has("mobile_platform");
        for (String key : MOBILE_METADATA) legacyMetadata |= defaults.has(key);
        if (!legacyMetadata && (!defaults.has("sid") || sid.matches("[0-9]{13}"))) {
            return normalizeQueuedPropertyCapacity(migrated);
        }
        if (!sid.matches("[0-9]{13}")) {
            String startedAt = defaults.optString("mobile_session_started_at");
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            format.setLenient(false);
            java.text.ParsePosition position = new java.text.ParsePosition(0);
            java.util.Date parsed = format.parse(startedAt, position);
            if (parsed == null || position.getIndex() != startedAt.length()
                    || !MobileSession.utc(parsed.getTime()).equals(startedAt)
                    || !Long.toString(parsed.getTime()).matches("[0-9]{13}")) {
                defaults.remove("sid");
            } else {
                defaults.put("sid", Long.toString(parsed.getTime()));
            }
        }
        if (!legacyMetadata) return normalizeQueuedPropertyCapacity(migrated);
        JSONObject properties = migrated.optJSONObject("eventProperties");
        if (properties == null) properties = new JSONObject();
        clearMobileMetadata(properties);
        for (String key : MOBILE_METADATA) {
            if (defaults.has(key)) properties.put(key, defaults.opt(key));
        }
        clearMobileMetadata(defaults);
        migrated.put("eventProperties", properties);
        if (migrated.optString("event").startsWith("$mobile_")) {
            migrated.put("userProperties", JSONObject.NULL);
            migrated.put("defaultProperties", retainKeys(defaults, SDK_DEFAULT_PROPERTIES));
            migrated.put("eventProperties", retainKeys(properties, CANONICAL_EVENT_PROPERTIES));
        }
        return normalizeQueuedPropertyCapacity(migrated);
    }

    private static JSONObject normalizeQueuedPropertyCapacity(JSONObject event)
            throws JSONException {
        JSONObject properties = event.optJSONObject("eventProperties");
        if (properties == null || fitsEventPropertyBudget(properties)
                || !properties.has("mobile_contract_version")) return event;
        JSONObject ordinary = shallowCopy(properties);
        for (String key : MOBILE_METADATA) ordinary.remove(key);
        if (fitsEventPropertyBudget(ordinary)) {
            event.put("eventProperties", ordinary);
            warnMobileMetadataOmitted();
        }
        return event;
    }

    private static void warnMobileMetadataOmitted() {
        OPLog.w("OursPrivacy.Track",
                "Mobile metadata omitted because event properties would exceed the ingest limits");
    }

    private static boolean fitsEventPropertyBudget(JSONObject properties) {
        return properties.length() <= MAX_EVENT_PROPERTIES
                && properties.toString().length() < MAX_EVENT_PROPERTIES_JSON_LENGTH;
    }

    private static JSONObject retainKeys(JSONObject source, String... keys) throws JSONException {
        JSONObject result = new JSONObject();
        for (String key : keys) {
            if (source.has(key)) result.put(key, source.opt(key));
        }
        return result;
    }

    private static final int MAX_EVENT_PROPERTIES = 200;
    private static final int MAX_EVENT_PROPERTIES_JSON_LENGTH = 1024 * 1024;
    private static final String[] MOBILE_METADATA = {
            "mobile_contract_version", "mobile_occurred_at", "app_version", "app_build"
    };
    private static final String[] CANONICAL_EVENT_PROPERTIES = {
            "mobile_contract_version", "mobile_occurred_at", "app_version", "app_build",
            "screen_name", "engagement_duration_ms", "previous_app_version", "previous_app_build"
    };
    private static final String[] SDK_DEFAULT_PROPERTIES = {
            "sid", "version", "device_type", "device_vendor", "device_model",
            "os_name", "os_version", "screen_width", "screen_height"
    };

    /**
     * Merges per-call user properties with the store-level default custom and
     * consent bags. Top-level keys come from per-call.
     *
     * <p>{@code custom_properties} is emitted as {@code {...defaultCustom, ...perCallCustom}}
     * when either side has data.
     *
     * <p>{@code consent} follows the same rule, but the key is <b>omitted entirely</b>
     * when neither side has consent data — emitting {@code consent: {}} can overwrite
     * consent flags already persisted on the visitor record.
     *
     * @return merged JSONObject, or {@code null} when nothing should be sent on the wire.
     */
    static JSONObject mergeUserProperties(JSONObject perCall,
                                          JSONObject defaultCustom,
                                          JSONObject defaultConsent) throws JSONException {
        final boolean haveDefaultCustom = defaultCustom != null && defaultCustom.length() > 0;
        final boolean haveDefaultConsent = defaultConsent != null && defaultConsent.length() > 0;
        final boolean havePerCall = perCall != null && perCall.length() > 0;

        if (!haveDefaultCustom && !haveDefaultConsent) {
            if (!havePerCall) return null;
            final JSONObject copied = shallowCopy(perCall);
            final JSONObject consent = copied.optJSONObject("consent");
            if (consent != null && consent.length() == 0) copied.remove("consent");
            return copied.length() == 0 ? null : copied;
        }

        if (!havePerCall && !haveDefaultCustom && !haveDefaultConsent) {
            return null;
        }

        final JSONObject merged = new JSONObject();
        if (havePerCall) {
            final Iterator<String> keys = perCall.keys();
            while (keys.hasNext()) {
                final String k = keys.next();
                if ("custom_properties".equals(k) || "consent".equals(k)) continue;
                merged.put(k, perCall.opt(k));
            }
        }

        final JSONObject perCallCustom = perCall == null ? null : perCall.optJSONObject("custom_properties");
        final JSONObject perCallConsent = perCall == null ? null : perCall.optJSONObject("consent");
        final boolean havePerCallConsent = perCallConsent != null && perCallConsent.length() > 0;

        final JSONObject mergedCustom = new JSONObject();
        if (haveDefaultCustom) mergeOnto(mergedCustom, defaultCustom);
        if (perCallCustom != null) mergeOnto(mergedCustom, perCallCustom);
        if (mergedCustom.length() > 0) {
            merged.put("custom_properties", mergedCustom);
        }

        if (haveDefaultConsent || havePerCallConsent) {
            final JSONObject mergedConsent = new JSONObject();
            if (haveDefaultConsent) mergeOnto(mergedConsent, defaultConsent);
            if (perCallConsent != null) mergeOnto(mergedConsent, perCallConsent);
            merged.put("consent", mergedConsent);
        }

        return merged.length() == 0 ? null : merged;
    }

    static JSONObject mergeOnto(JSONObject target, JSONObject source) throws JSONException {
        if (source == null || target == null) return target;
        final Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            final String k = keys.next();
            target.put(k, source.opt(k));
        }
        return target;
    }

    private static JSONObject shallowCopy(JSONObject src) throws JSONException {
        final JSONObject out = new JSONObject();
        final Iterator<String> keys = src.keys();
        while (keys.hasNext()) {
            final String k = keys.next();
            out.put(k, src.opt(k));
        }
        return out;
    }

    private Track() {}
}
