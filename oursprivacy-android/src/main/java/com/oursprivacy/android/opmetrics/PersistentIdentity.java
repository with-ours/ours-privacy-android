package com.oursprivacy.android.opmetrics;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;

import com.oursprivacy.android.util.OPLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Single source of truth for persisted SDK state:
 * <ul>
 *   <li>{@code visitor_id} (UUID per install) + {@code is_manually_set_id} flag
 *   <li>opt-out flag
 *   <li>The four default-property bags: event, user-custom, user-consent, attribution
 *   <li>In-memory event queue, snapshotted to a single SharedPreferences JSON blob on each mutation
 * </ul>
 */
@SuppressLint("CommitPrefEdits")
/* package */ final class PersistentIdentity {

    private static final String KEY_VISITOR_ID = "visitor_id";
    private static final String KEY_IS_MANUALLY_SET_ID = "is_manually_set_id";
    private static final String KEY_OPT_OUT = "opt_out";
    private static final String KEY_DEFAULT_EVENT_PROPERTIES = "default_event_properties";
    private static final String KEY_DEFAULT_USER_CUSTOM_PROPERTIES = "default_user_custom_properties";
    private static final String KEY_DEFAULT_USER_CONSENT_PROPERTIES = "default_user_consent_properties";
    private static final String KEY_ATTRIBUTION_DEFAULT_PROPERTIES = "attribution_default_properties";
    private static final String KEY_EVENT_QUEUE = "event_queue";
    private static final String KEY_MOBILE_SESSION_PREFIX = "mobile_session_";

    private final Future<SharedPreferences> mPrefsLoader;

    private boolean mLoaded = false;
    private String mVisitorId;
    private boolean mIsManuallySetId;
    private Boolean mOptOut;

    private JSONObject mDefaultEventProperties = new JSONObject();
    private JSONObject mDefaultUserCustomProperties = new JSONObject();
    private JSONObject mDefaultUserConsentProperties = new JSONObject();
    private JSONObject mAttributionDefaultProperties = new JSONObject();

    private JSONArray mEventQueue = new JSONArray();

    PersistentIdentity(Future<SharedPreferences> prefsLoader) {
        mPrefsLoader = prefsLoader;
    }

    // ---------- visitor_id ----------

    synchronized String getVisitorId() {
        ensureLoaded();
        return mVisitorId;
    }

    synchronized void setVisitorId(String visitorId, boolean manuallySet) {
        ensureLoaded();
        mVisitorId = visitorId;
        mIsManuallySetId = manuallySet;
        final SharedPreferences.Editor editor = editor();
        if (editor != null) {
            editor.putString(KEY_VISITOR_ID, mVisitorId);
            editor.putBoolean(KEY_IS_MANUALLY_SET_ID, mIsManuallySetId);
            editor.apply();
        }
    }

    synchronized boolean isManuallySetId() {
        ensureLoaded();
        return mIsManuallySetId;
    }

    // ---------- opt-out ----------

    synchronized boolean getOptOut() {
        ensureLoaded();
        return mOptOut != null && mOptOut;
    }

    synchronized boolean hasOptOutFlag() {
        ensureLoaded();
        return mOptOut != null;
    }

    synchronized void setOptOut(boolean optOut) {
        ensureLoaded();
        mOptOut = optOut;
        final SharedPreferences.Editor editor = editor();
        if (editor != null) {
            editor.putBoolean(KEY_OPT_OUT, optOut);
            editor.apply();
        }
    }

    // ---------- default-property bags ----------

    synchronized JSONObject getDefaultEventProperties() {
        ensureLoaded();
        return copy(mDefaultEventProperties);
    }

    synchronized JSONObject getDefaultUserCustomProperties() {
        ensureLoaded();
        return copy(mDefaultUserCustomProperties);
    }

    synchronized JSONObject getDefaultUserConsentProperties() {
        ensureLoaded();
        return copy(mDefaultUserConsentProperties);
    }

    synchronized JSONObject getAttributionDefaultProperties() {
        ensureLoaded();
        return copy(mAttributionDefaultProperties);
    }

    synchronized void updateDefaultEventProperties(JSONObject merge) {
        ensureLoaded();
        mergeOnto(mDefaultEventProperties, merge);
        persist(KEY_DEFAULT_EVENT_PROPERTIES, mDefaultEventProperties);
    }

    synchronized void updateDefaultUserCustomProperties(JSONObject merge) {
        ensureLoaded();
        mergeOnto(mDefaultUserCustomProperties, merge);
        persist(KEY_DEFAULT_USER_CUSTOM_PROPERTIES, mDefaultUserCustomProperties);
    }

    synchronized void updateDefaultUserConsentProperties(JSONObject merge) {
        ensureLoaded();
        mergeOnto(mDefaultUserConsentProperties, merge);
        persist(KEY_DEFAULT_USER_CONSENT_PROPERTIES, mDefaultUserConsentProperties);
    }

    /** Deep-link attribution replaces (not merges) prior attribution defaults. */
    synchronized void replaceAttributionDefaultProperties(JSONObject replacement) {
        ensureLoaded();
        mAttributionDefaultProperties = replacement == null ? new JSONObject() : replacement;
        persist(KEY_ATTRIBUTION_DEFAULT_PROPERTIES, mAttributionDefaultProperties);
    }

    // ---------- event queue ----------

    synchronized void enqueueEvent(JSONObject event) {
        ensureLoaded();
        mEventQueue.put(event);
        persistQueue();
    }

    /** Returns a snapshot of the current queue. The persisted copy is not mutated. */
    synchronized JSONArray getQueueSnapshot() {
        ensureLoaded();
        return copyArray(mEventQueue);
    }

    /** Drops the first {@code count} items from the queue (used post-flush). */
    synchronized void dropFromQueue(int count) {
        ensureLoaded();
        if (count <= 0) return;
        if (count >= mEventQueue.length()) {
            mEventQueue = new JSONArray();
        } else {
            final JSONArray next = new JSONArray();
            for (int i = count; i < mEventQueue.length(); i++) {
                next.put(mEventQueue.opt(i));
            }
            mEventQueue = next;
        }
        persistQueue();
    }

    synchronized int getQueueSize() {
        ensureLoaded();
        return mEventQueue.length();
    }

    synchronized void clearQueue() {
        ensureLoaded();
        mEventQueue = new JSONArray();
        persistQueue();
    }

    // ---------- lifecycle ----------

    /** Wipes everything except the opt-out flag; regenerates a fresh visitor_id. */
    synchronized void reset() {
        ensureLoaded();
        mVisitorId = UUID.randomUUID().toString();
        mIsManuallySetId = false;
        mDefaultEventProperties = new JSONObject();
        mDefaultUserCustomProperties = new JSONObject();
        mDefaultUserConsentProperties = new JSONObject();
        mAttributionDefaultProperties = new JSONObject();
        mEventQueue = new JSONArray();
        final SharedPreferences.Editor editor = editor();
        if (editor != null) {
            editor.putString(KEY_VISITOR_ID, mVisitorId);
            editor.putBoolean(KEY_IS_MANUALLY_SET_ID, false);
            editor.remove(KEY_DEFAULT_EVENT_PROPERTIES);
            editor.remove(KEY_DEFAULT_USER_CUSTOM_PROPERTIES);
            editor.remove(KEY_DEFAULT_USER_CONSENT_PROPERTIES);
            editor.remove(KEY_ATTRIBUTION_DEFAULT_PROPERTIES);
            editor.remove(KEY_EVENT_QUEUE);
            clearMobileSessions(editor);
            editor.apply();
        }
    }

    /**
     * Opt-out path: rotates {@code visitor_id}, clears the four default bags,
     * empties the event queue, and persists the opt-out flag. The visitor is
     * effectively forgotten on the next event lifecycle.
     */
    synchronized void optOutAndClear() {
        ensureLoaded();
        mVisitorId = UUID.randomUUID().toString();
        mIsManuallySetId = false;
        mDefaultEventProperties = new JSONObject();
        mDefaultUserCustomProperties = new JSONObject();
        mDefaultUserConsentProperties = new JSONObject();
        mAttributionDefaultProperties = new JSONObject();
        mEventQueue = new JSONArray();
        mOptOut = true;
        final SharedPreferences.Editor editor = editor();
        if (editor != null) {
            editor.putString(KEY_VISITOR_ID, mVisitorId);
            editor.putBoolean(KEY_IS_MANUALLY_SET_ID, false);
            editor.remove(KEY_DEFAULT_EVENT_PROPERTIES);
            editor.remove(KEY_DEFAULT_USER_CUSTOM_PROPERTIES);
            editor.remove(KEY_DEFAULT_USER_CONSENT_PROPERTIES);
            editor.remove(KEY_ATTRIBUTION_DEFAULT_PROPERTIES);
            editor.remove(KEY_EVENT_QUEUE);
            clearMobileSessions(editor);
            editor.putBoolean(KEY_OPT_OUT, true);
            editor.apply();
        }
    }

    static final class MobileState {
        String sid;
        long startedAt;
        long lastActive;
        long accumulatedMs;
        long pendingMs;
        boolean sessionStartEmitted;
        boolean firstOpenAccepted;
        boolean appObserved;
        String appVersion;
        String appBuild;
    }

    synchronized MobileState getMobileState(String token) {
        ensureLoaded();
        SharedPreferences prefs = preferences();
        MobileState state = new MobileState();
        if (prefs == null) return state;
        JSONObject stored = readJsonObject(prefs, mobileKey(token));
        state.sid = stored.optString("sid", null);
        state.startedAt = stored.optLong("started_at");
        state.lastActive = stored.optLong("last_active");
        state.accumulatedMs = stored.optLong("accumulated_ms");
        state.pendingMs = stored.optLong("pending_ms");
        state.sessionStartEmitted = stored.optBoolean("session_start_emitted");
        state.firstOpenAccepted = stored.optBoolean("first_open_accepted");
        state.appObserved = stored.optBoolean("app_observed");
        state.appVersion = stored.optString("app_version", null);
        state.appBuild = stored.optString("app_build", null);
        return state;
    }

    synchronized void saveMobileState(String token, MobileState state) {
        ensureLoaded();
        final SharedPreferences.Editor editor = editor();
        if (editor == null) throw new IllegalStateException("SharedPreferences unavailable");
        JSONObject stored = new JSONObject();
        try {
            if (state.sid != null) stored.put("sid", state.sid);
            stored.put("started_at", state.startedAt);
            stored.put("last_active", state.lastActive);
            stored.put("accumulated_ms", state.accumulatedMs);
            stored.put("pending_ms", state.pendingMs);
            stored.put("session_start_emitted", state.sessionStartEmitted);
            stored.put("first_open_accepted", state.firstOpenAccepted);
            stored.put("app_observed", state.appObserved);
            if (state.appVersion != null) stored.put("app_version", state.appVersion);
            if (state.appBuild != null) stored.put("app_build", state.appBuild);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        if (!editor.putString(mobileKey(token), stored.toString()).commit()) {
            throw new IllegalStateException("Failed to persist mobile session");
        }
    }

    synchronized void acceptMobileFirstOpen(String token) {
        MobileState state = getMobileState(token);
        state.firstOpenAccepted = true;
        saveMobileState(token, state);
    }

    private static String mobileKey(String token) {
        return KEY_MOBILE_SESSION_PREFIX + UUID.nameUUIDFromBytes(
                token.getBytes(StandardCharsets.UTF_8));
    }

    private void clearMobileSessions(SharedPreferences.Editor editor) {
        SharedPreferences prefs = preferences();
        if (prefs == null) return;
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith(KEY_MOBILE_SESSION_PREFIX)) continue;
            JSONObject stored = readJsonObject(prefs, key);
            stored.remove("sid");
            stored.remove("started_at");
            stored.remove("last_active");
            stored.remove("accumulated_ms");
            stored.remove("pending_ms");
            stored.remove("session_start_emitted");
            editor.putString(key, stored.toString());
        }
    }

    // ---------- internals ----------

    private void ensureLoaded() {
        if (mLoaded) return;
        SharedPreferences prefs = null;
        try {
            prefs = mPrefsLoader.get();
        } catch (ExecutionException e) {
            OPLog.e(LOGTAG, "Failed to load SharedPreferences", e.getCause());
        } catch (InterruptedException e) {
            OPLog.e(LOGTAG, "Interrupted loading SharedPreferences", e);
        }
        if (prefs == null) {
            mLoaded = true;
            mVisitorId = UUID.randomUUID().toString();
            return;
        }

        mVisitorId = prefs.getString(KEY_VISITOR_ID, null);
        mIsManuallySetId = prefs.getBoolean(KEY_IS_MANUALLY_SET_ID, false);
        if (prefs.contains(KEY_OPT_OUT)) {
            mOptOut = prefs.getBoolean(KEY_OPT_OUT, false);
        }
        mDefaultEventProperties = readJsonObject(prefs, KEY_DEFAULT_EVENT_PROPERTIES);
        mDefaultUserCustomProperties = readJsonObject(prefs, KEY_DEFAULT_USER_CUSTOM_PROPERTIES);
        mDefaultUserConsentProperties = readJsonObject(prefs, KEY_DEFAULT_USER_CONSENT_PROPERTIES);
        mAttributionDefaultProperties = readJsonObject(prefs, KEY_ATTRIBUTION_DEFAULT_PROPERTIES);
        mEventQueue = readJsonArray(prefs, KEY_EVENT_QUEUE);

        if (mVisitorId == null) {
            mVisitorId = UUID.randomUUID().toString();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_VISITOR_ID, mVisitorId);
            editor.apply();
        }

        mLoaded = true;
    }

    private SharedPreferences.Editor editor() {
        SharedPreferences prefs = preferences();
        return prefs == null ? null : prefs.edit();
    }

    private SharedPreferences preferences() {
        try {
            return mPrefsLoader.get();
        } catch (ExecutionException e) {
            OPLog.e(LOGTAG, "Can't get SharedPreferences", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            OPLog.e(LOGTAG, "Interrupted getting SharedPreferences", e);
        }
        return null;
    }

    private void persist(String key, JSONObject value) {
        final SharedPreferences.Editor editor = editor();
        if (editor != null) {
            editor.putString(key, value.toString());
            editor.apply();
        }
    }

    private void persistQueue() {
        final SharedPreferences.Editor editor = editor();
        if (editor != null) {
            editor.putString(KEY_EVENT_QUEUE, mEventQueue.toString());
            editor.apply();
        }
    }

    private static JSONObject readJsonObject(SharedPreferences prefs, String key) {
        final String raw = prefs.getString(key, null);
        if (raw == null) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            OPLog.w(LOGTAG, "Stored " + key + " is not valid JSON; resetting", e);
            return new JSONObject();
        }
    }

    private static JSONArray readJsonArray(SharedPreferences prefs, String key) {
        final String raw = prefs.getString(key, null);
        if (raw == null) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            OPLog.w(LOGTAG, "Stored " + key + " is not valid JSON; resetting", e);
            return new JSONArray();
        }
    }

    private static void mergeOnto(JSONObject target, JSONObject source) {
        if (source == null) return;
        final java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            final String k = keys.next();
            try {
                target.put(k, source.opt(k));
            } catch (JSONException ignored) {}
        }
    }

    private static JSONObject copy(JSONObject src) {
        final JSONObject out = new JSONObject();
        mergeOnto(out, src);
        return out;
    }

    private static JSONArray copyArray(JSONArray src) {
        final JSONArray out = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            out.put(src.opt(i));
        }
        return out;
    }

    private static final String LOGTAG = "OursPrivacy.Persist";
}
