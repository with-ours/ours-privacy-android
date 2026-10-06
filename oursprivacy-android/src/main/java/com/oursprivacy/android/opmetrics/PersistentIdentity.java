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
@SuppressLint({"CommitPrefEdits", "ApplySharedPref"})
/* package */ final class PersistentIdentity {
    interface WallClock {
        long wallMillis();
    }

    private static final String KEY_VISITOR_ID = "visitor_id";
    private static final String KEY_IS_MANUALLY_SET_ID = "is_manually_set_id";
    private static final String KEY_OPT_OUT = "opt_out";
    private static final String KEY_DEFAULT_EVENT_PROPERTIES = "default_event_properties";
    private static final String KEY_DEFAULT_USER_CUSTOM_PROPERTIES = "default_user_custom_properties";
    private static final String KEY_DEFAULT_USER_CONSENT_PROPERTIES = "default_user_consent_properties";
    private static final String KEY_ATTRIBUTION_DEFAULT_PROPERTIES = "attribution_default_properties";
    private static final String KEY_EVENT_QUEUE = "event_queue";
    private static final String KEY_EVENT_QUEUE_GENERATION = "event_queue_generation";
    private static final String KEY_MOBILE_SESSION_PREFIX = "mobile_session_";
    private static final String KEY_INDEXED_INGEST_PREFIX = "indexed_ingest_";

    private final Future<SharedPreferences> mPrefsLoader;
    private final WallClock mWallClock;

    private boolean mLoaded = false;
    private String mVisitorId;
    private boolean mIsManuallySetId;
    private Boolean mOptOut;

    private JSONObject mDefaultEventProperties = new JSONObject();
    private JSONObject mDefaultUserCustomProperties = new JSONObject();
    private JSONObject mDefaultUserConsentProperties = new JSONObject();
    private JSONObject mAttributionDefaultProperties = new JSONObject();

    private JSONArray mEventQueue = new JSONArray();
    private String mQueueGeneration = UUID.randomUUID().toString();

    PersistentIdentity(Future<SharedPreferences> prefsLoader) {
        this(prefsLoader, System::currentTimeMillis);
    }

    PersistentIdentity(Future<SharedPreferences> prefsLoader, WallClock wallClock) {
        mPrefsLoader = prefsLoader;
        mWallClock = wallClock;
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

    static final class QueueSnapshot {
        final JSONArray events;
        final String generation;

        QueueSnapshot(JSONArray events, String generation) {
            this.events = events;
            this.generation = generation;
        }
    }

    synchronized QueueSnapshot getQueueSnapshotForFlush() {
        ensureLoaded();
        try {
            return new QueueSnapshot(new JSONArray(mEventQueue.toString()), mQueueGeneration);
        } catch (JSONException e) {
            throw new IllegalStateException("Queued events are not valid JSON", e);
        }
    }

    synchronized boolean hasIndexedIngestMode(String token) {
        ensureLoaded();
        SharedPreferences prefs = preferences();
        return prefs != null && prefs.getBoolean(indexedIngestKey(token), false);
    }

    synchronized boolean acknowledgeBatch(String token, QueueSnapshot sent,
                                          int count, boolean indexed) {
        ensureLoaded();
        if (count <= 0 || count > mEventQueue.length()
                || count > sent.events.length()
                || !sent.generation.equals(mQueueGeneration)) return false;
        final SharedPreferences prefs = preferences();
        if (prefs == null) return false;
        if (!sent.generation.equals(prefs.getString(KEY_EVENT_QUEUE_GENERATION, null))
                || !matchesHead(mEventQueue, sent.events, count)
                || !matchesHead(readJsonArray(prefs, KEY_EVENT_QUEUE), sent.events, count)) {
            return false;
        }
        final String indexedKey = indexedIngestKey(token);
        final String previousQueue = mEventQueue.toString();
        final boolean wasIndexed = prefs.getBoolean(indexedKey, false);
        final String nextGeneration = UUID.randomUUID().toString();
        final JSONArray next = new JSONArray();
        for (int i = count; i < mEventQueue.length(); i++) {
            next.put(mEventQueue.opt(i));
        }
        final SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_EVENT_QUEUE, next.toString());
        editor.putString(KEY_EVENT_QUEUE_GENERATION, nextGeneration);
        if (indexed) editor.putBoolean(indexedKey, true);
        if (!editor.commit()) {
            final SharedPreferences.Editor rollback = prefs.edit();
            rollback.putString(KEY_EVENT_QUEUE, previousQueue);
            rollback.putString(KEY_EVENT_QUEUE_GENERATION, mQueueGeneration);
            if (wasIndexed) rollback.putBoolean(indexedKey, true);
            else rollback.remove(indexedKey);
            rollback.commit();
            return false;
        }
        mEventQueue = next;
        mQueueGeneration = nextGeneration;
        return true;
    }

    private static boolean matchesHead(JSONArray current, JSONArray sent, int count) {
        if (current.length() < count || sent.length() < count) return false;
        for (int i = 0; i < count; i++) {
            if (!String.valueOf(current.opt(i)).equals(String.valueOf(sent.opt(i)))) {
                return false;
            }
        }
        return true;
    }

    synchronized int getQueueSize() {
        ensureLoaded();
        return mEventQueue.length();
    }

    synchronized void clearQueue() {
        ensureLoaded();
        mEventQueue = new JSONArray();
        mQueueGeneration = UUID.randomUUID().toString();
        persistQueue();
    }

    // ---------- lifecycle ----------

    /** Wipes everything except the opt-out flag; regenerates a fresh visitor_id. */
    synchronized void reset() {
        resetKeepingMobileSession(null);
    }

    synchronized void resetPreservingMobileSession(String token) {
        resetKeepingMobileSession(mobileKey(token));
    }

    private void resetKeepingMobileSession(String retainedKey) {
        ensureLoaded();
        mVisitorId = UUID.randomUUID().toString();
        mIsManuallySetId = false;
        mDefaultEventProperties = new JSONObject();
        mDefaultUserCustomProperties = new JSONObject();
        mDefaultUserConsentProperties = new JSONObject();
        mAttributionDefaultProperties = new JSONObject();
        mEventQueue = new JSONArray();
        mQueueGeneration = UUID.randomUUID().toString();
        final SharedPreferences.Editor editor = editor();
        if (editor != null) {
            editor.putString(KEY_VISITOR_ID, mVisitorId);
            editor.putBoolean(KEY_IS_MANUALLY_SET_ID, false);
            editor.remove(KEY_DEFAULT_EVENT_PROPERTIES);
            editor.remove(KEY_DEFAULT_USER_CUSTOM_PROPERTIES);
            editor.remove(KEY_DEFAULT_USER_CONSENT_PROPERTIES);
            editor.remove(KEY_ATTRIBUTION_DEFAULT_PROPERTIES);
            editor.remove(KEY_EVENT_QUEUE);
            editor.putString(KEY_EVENT_QUEUE_GENERATION, mQueueGeneration);
            clearMobileSessions(editor, retainedKey);
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
        mQueueGeneration = UUID.randomUUID().toString();
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
            editor.putString(KEY_EVENT_QUEUE_GENERATION, mQueueGeneration);
            clearMobileSessions(editor, null);
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
        JSONArray pendingFacts = new JSONArray();
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
        JSONArray pending = stored.optJSONArray("pending_facts");
        if (pending != null) state.pendingFacts = copyArray(pending);
        return state;
    }

    synchronized void saveMobileState(String token, MobileState state) {
        ensureLoaded();
        final SharedPreferences.Editor editor = editor();
        if (editor == null) throw new IllegalStateException("SharedPreferences unavailable");
        if (!editor.putString(mobileKey(token), encodeMobileState(state)).commit()) {
            throw new IllegalStateException("Failed to persist mobile session");
        }
    }

    synchronized boolean enqueueMobileFact(String token, String factId, JSONObject event) {
        ensureLoaded();
        if (getOptOut()) return false;
        MobileState state = getMobileState(token);
        int index = -1;
        JSONObject pending = null;
        for (int i = 0; i < state.pendingFacts.length(); i++) {
            JSONObject candidate = state.pendingFacts.optJSONObject(i);
            if (candidate != null && factId.equals(candidate.optString("id"))) {
                index = i;
                pending = candidate;
                break;
            }
        }
        if (pending == null) return false;
        if (index != 0 || pending.optLong("occurred_at") - mWallClock.wallMillis()
                > MobileSession.MAX_FUTURE_MS) {
            return false;
        }
        final String previousState = encodeMobileState(state);
        final String previousQueue = mEventQueue.toString();
        JSONObject defaults = event == null ? null : event.optJSONObject("defaultProperties");
        if (!pending.optString("event").equals(event.optString("event"))
                || !factId.equals(event.optString("distinct_id"))
                || !pending.optString("visitor_id").equals(event.optString("visitor_id"))
                || defaults == null
                || !pending.optString("sid").equals(defaults.optString("sid"))
                || !MobileSession.utc(pending.optLong("started_at")).equals(
                        defaults.optString("mobile_session_started_at"))
                || !MobileSession.utc(pending.optLong("occurred_at")).equals(
                        defaults.optString("mobile_occurred_at"))) {
            throw new IllegalArgumentException("Queued event does not match pending mobile fact");
        }
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < state.pendingFacts.length(); i++) {
            if (i != index) remaining.put(state.pendingFacts.opt(i));
        }
        state.pendingFacts = remaining;
        if ("$mobile_first_open".equals(pending.optString("event"))) {
            state.firstOpenAccepted = true;
        }
        final JSONArray nextQueue = copyArray(mEventQueue);
        final JSONObject eventCopy;
        try {
            eventCopy = new JSONObject(event.toString());
        } catch (JSONException e) {
            throw new IllegalArgumentException("Queued event is not valid JSON", e);
        }
        nextQueue.put(eventCopy);
        final SharedPreferences.Editor editor = editor();
        if (editor == null) throw new IllegalStateException("SharedPreferences unavailable");
        editor.putString(mobileKey(token), encodeMobileState(state));
        editor.putString(KEY_EVENT_QUEUE, nextQueue.toString());
        editor.putString(KEY_EVENT_QUEUE_GENERATION, mQueueGeneration);
        if (!editor.commit()) {
            // A failed disk commit can leave the in-memory preferences advanced, so restore retry state.
            SharedPreferences.Editor rollback = editor();
            if (rollback != null) {
                rollback.putString(mobileKey(token), previousState);
                rollback.putString(KEY_EVENT_QUEUE, previousQueue);
                rollback.commit();
            }
            throw new IllegalStateException("Failed to queue mobile fact");
        }
        mEventQueue = nextQueue;
        return true;
    }

    private static String encodeMobileState(MobileState state) {
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
            stored.put("pending_facts", state.pendingFacts);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return stored.toString();
    }

    private static String mobileKey(String token) {
        return KEY_MOBILE_SESSION_PREFIX + UUID.nameUUIDFromBytes(
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static String indexedIngestKey(String token) {
        return KEY_INDEXED_INGEST_PREFIX + UUID.nameUUIDFromBytes(
                token.getBytes(StandardCharsets.UTF_8));
    }

    private void clearMobileSessions(SharedPreferences.Editor editor, String retainedKey) {
        SharedPreferences prefs = preferences();
        if (prefs == null) return;
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith(KEY_MOBILE_SESSION_PREFIX) || key.equals(retainedKey)) continue;
            JSONObject stored = readJsonObject(prefs, key);
            stored.remove("sid");
            stored.remove("started_at");
            stored.remove("last_active");
            stored.remove("accumulated_ms");
            stored.remove("pending_ms");
            stored.remove("session_start_emitted");
            stored.remove("pending_facts");
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
        mQueueGeneration = prefs.getString(KEY_EVENT_QUEUE_GENERATION, null);
        if (mQueueGeneration == null) {
            mQueueGeneration = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_EVENT_QUEUE_GENERATION, mQueueGeneration).apply();
        }

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
            editor.putString(KEY_EVENT_QUEUE_GENERATION, mQueueGeneration);
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
