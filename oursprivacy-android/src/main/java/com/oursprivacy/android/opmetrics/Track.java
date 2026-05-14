package com.oursprivacy.android.opmetrics;

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

        Context(String visitorId,
                JSONObject defaultEventProperties,
                JSONObject defaultUserCustomProperties,
                JSONObject defaultUserConsentProperties,
                JSONObject attributionDefaultProperties,
                JSONObject baseDefaultProperties) {
            this.visitorId = visitorId;
            this.defaultEventProperties = defaultEventProperties != null ? defaultEventProperties : new JSONObject();
            this.defaultUserCustomProperties = defaultUserCustomProperties != null ? defaultUserCustomProperties : new JSONObject();
            this.defaultUserConsentProperties = defaultUserConsentProperties != null ? defaultUserConsentProperties : new JSONObject();
            this.attributionDefaultProperties = attributionDefaultProperties != null ? attributionDefaultProperties : new JSONObject();
            this.baseDefaultProperties = baseDefaultProperties != null ? baseDefaultProperties : new JSONObject();
        }
    }

    /**
     * Builds the inner data[] element for a {@code track()} call.
     *
     * {@code eventProperties} is {@code {...defaultEventProperties, ...callerEventProperties}}
     * (per-call wins). {@code userProperties} runs through {@link #mergeUserProperties} —
     * default custom/consent bags merge with per-call, with the empty-consent omission rule.
     * {@code defaultProperties} is the OS/device snapshot overlaid with the attribution bag.
     */
    static JSONObject composeTrackEvent(String eventName,
                                        JSONObject eventProperties,
                                        JSONObject userProperties,
                                        Context ctx) throws JSONException {
        final JSONObject mergedEvent = mergeOnto(new JSONObject(), ctx.defaultEventProperties);
        if (eventProperties != null) {
            mergeOnto(mergedEvent, eventProperties);
        }

        // $distinct_id override lets callers pin a per-event distinct_id (replay stitching).
        final String distinctId;
        final Object override = mergedEvent.opt("$distinct_id");
        if (override instanceof String && !((String) override).isEmpty()) {
            distinctId = (String) override;
            mergedEvent.remove("$distinct_id");
        } else {
            distinctId = UUID.randomUUID().toString();
        }

        final JSONObject mergedUser = mergeUserProperties(
                userProperties,
                ctx.defaultUserCustomProperties,
                ctx.defaultUserConsentProperties);

        final JSONObject defaults = mergeOnto(new JSONObject(), ctx.baseDefaultProperties);
        mergeOnto(defaults, ctx.attributionDefaultProperties);

        final JSONObject item = new JSONObject();
        item.put("event", eventName == null ? "op_event" : eventName);
        item.put("visitor_id", ctx.visitorId);
        item.put("distinct_id", distinctId);
        item.put("eventProperties", mergedEvent.length() == 0 ? JSONObject.NULL : mergedEvent);
        item.put("userProperties", mergedUser == null ? JSONObject.NULL : mergedUser);
        item.put("defaultProperties", defaults);
        return item;
    }

    /**
     * Builds the inner data[] element for an {@code identify()} call. Event name
     * is {@code "$identify"}, {@code eventProperties} is null, {@code userProperties}
     * carries the merged visitor profile (same empty-consent omission rule applies).
     */
    static JSONObject composeIdentifyEvent(JSONObject userProperties, Context ctx) throws JSONException {
        final JSONObject mergedUser = mergeUserProperties(
                userProperties,
                ctx.defaultUserCustomProperties,
                ctx.defaultUserConsentProperties);

        final JSONObject defaults = mergeOnto(new JSONObject(), ctx.baseDefaultProperties);
        mergeOnto(defaults, ctx.attributionDefaultProperties);

        final JSONObject item = new JSONObject();
        item.put("event", "$identify");
        item.put("visitor_id", ctx.visitorId);
        item.put("distinct_id", UUID.randomUUID().toString());
        item.put("eventProperties", JSONObject.NULL);
        item.put("userProperties", mergedUser == null ? JSONObject.NULL : mergedUser);
        item.put("defaultProperties", defaults);
        return item;
    }

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
            // Fast path — no store-level defaults. Pass per-call through unchanged.
            if (!havePerCall) return null;
            return shallowCopy(perCall);
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
