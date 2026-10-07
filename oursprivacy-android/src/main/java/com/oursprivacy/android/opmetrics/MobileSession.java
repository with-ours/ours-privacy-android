package com.oursprivacy.android.opmetrics;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

final class MobileSession {
    static final long SESSION_TIMEOUT_MS = 30L * 60L * 1000L;
    static final long ENGAGEMENT_THRESHOLD_MS = 10L * 1000L;
    static final long MAX_FUTURE_MS = 5L * 60L * 1000L;

    interface Clock {
        long wallMillis();
        long elapsedMillis();
    }

    static final class TimePoint {
        final long wallMillis;
        final long elapsedMillis;
        final String sid;
        final long lastActive;

        TimePoint(long wallMillis, long elapsedMillis) {
            this(wallMillis, elapsedMillis, null, 0);
        }

        TimePoint(long wallMillis, long elapsedMillis, String sid, long lastActive) {
            this.wallMillis = wallMillis;
            this.elapsedMillis = elapsedMillis;
            this.sid = sid;
            this.lastActive = lastActive;
        }
    }

    private static final class CommittedState {
        final String sid;
        final long lastActive;

        CommittedState(String sid, long lastActive) {
            this.sid = sid;
            this.lastActive = lastActive;
        }
    }

    static final class MobileSnapshot {
        private final String sid;
        private final long startedAt;
        private final long occurredAt;
        private final String appVersion;
        private final String appBuild;

        MobileSnapshot(String sid, long startedAt, long occurredAt,
                       String appVersion, String appBuild) {
            this.sid = sid;
            this.startedAt = startedAt;
            this.occurredAt = occurredAt;
            this.appVersion = appVersion;
            this.appBuild = appBuild;
        }

        String sid() {
            return sid;
        }

        long startedAtMillis() {
            return startedAt;
        }

        long occurredAtMillis() {
            return occurredAt;
        }

        JSONObject defaultProperties() {
            JSONObject defaults = new JSONObject();
            put(defaults, "sid", sid);
            put(defaults, "mobile_session_started_at", utc(startedAt));
            put(defaults, "mobile_occurred_at", utc(occurredAt));
            put(defaults, "mobile_platform", "android");
            put(defaults, "mobile_contract_version", 1);
            if (appVersion != null) put(defaults, "app_version", appVersion);
            if (appBuild != null) put(defaults, "app_build", appBuild);
            return defaults;
        }
    }

    static final class MobileFact {
        private final String id;
        private final String visitorId;
        private final String eventName;
        private final MobileSnapshot snapshot;
        private final JSONObject properties;

        MobileFact(String id, String visitorId, String eventName,
                   MobileSnapshot snapshot, JSONObject properties) {
            this.id = id;
            this.visitorId = visitorId;
            this.eventName = eventName;
            this.snapshot = snapshot;
            this.properties = copy(properties);
        }

        String id() {
            return id;
        }

        String visitorId() {
            return visitorId;
        }

        String eventName() {
            return eventName;
        }

        MobileSnapshot snapshot() {
            return snapshot;
        }

        JSONObject eventProperties() {
            return copy(properties);
        }

        JSONObject toJson() {
            JSONObject value = new JSONObject();
            put(value, "id", id);
            put(value, "visitor_id", visitorId);
            put(value, "event", eventName);
            put(value, "sid", snapshot.sid);
            put(value, "started_at", snapshot.startedAt);
            put(value, "occurred_at", snapshot.occurredAt);
            if (snapshot.appVersion != null) put(value, "app_version", snapshot.appVersion);
            if (snapshot.appBuild != null) put(value, "app_build", snapshot.appBuild);
            put(value, "event_properties", properties);
            return value;
        }

        static MobileFact fromJson(JSONObject value) {
            MobileSnapshot snapshot = new MobileSnapshot(value.optString("sid"),
                    value.optLong("started_at"), value.optLong("occurred_at"),
                    value.optString("app_version", null), value.optString("app_build", null));
            return new MobileFact(value.optString("id"), value.optString("visitor_id"),
                    value.optString("event"), snapshot,
                    value.optJSONObject("event_properties"));
        }
    }

    private final PersistentIdentity identity;
    private final String token;
    private final String appVersion;
    private final String appBuild;
    private final Clock clock;
    private boolean disabled;
    private boolean foreground;
    private boolean automatic;
    private long activeSinceElapsed;
    private String activeScreen;
    private TimePoint pendingPause;
    private volatile TimePoint requestedPause;
    private volatile CommittedState committedState;
    private boolean screenChangedDuringPause;

    MobileSession(PersistentIdentity identity, String token, String appVersion, String appBuild) {
        this(identity, token, appVersion, appBuild, new Clock() {
            @Override public long wallMillis() { return System.currentTimeMillis(); }
            @Override public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
        });
    }

    MobileSession(PersistentIdentity identity, String token, String appVersion, String appBuild,
                  Clock clock) {
        if (identity == null || token == null || token.isEmpty() || clock == null) {
            throw new IllegalArgumentException("identity, token, and clock are required");
        }
        this.identity = identity;
        this.token = token;
        this.appVersion = optional(appVersion);
        this.appBuild = optional(appBuild);
        this.clock = clock;
        this.disabled = identity.getOptOut();
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        committedState = new CommittedState(state.sid, state.lastActive);
    }

    synchronized List<MobileFact> foreground(boolean automatic) {
        return foreground(automatic, captureTimePoint());
    }

    synchronized List<MobileFact> foreground(boolean automatic, TimePoint point) {
        if (disabled || foreground) return Collections.emptyList();
        long nowWall = point.wallMillis;
        long nowElapsed = point.elapsedMillis;
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        List<MobileFact> facts = new ArrayList<>();
        reconcileClock(state, nowWall, nowElapsed, facts, false);
        boolean expired = state.sid != null && nowWall - state.lastActive >= SESSION_TIMEOUT_MS;
        if (expired && automatic) {
            facts.add(fact("$mobile_session_end", state, nowWall, null));
        }
        boolean newSession = state.sid == null || expired;
        if (newSession) startNew(state, nowWall);
        state.lastActive = Math.max(state.lastActive, nowWall);
        if (automatic) {
            if (!state.firstOpenAccepted && !hasPending(state, "$mobile_first_open")) {
                facts.add(fact("$mobile_first_open", state, nowWall, null));
            }
            if (state.appObserved
                    && ((state.appVersion != null && appVersion != null
                    && !equal(state.appVersion, appVersion))
                    || (state.appBuild != null && appBuild != null
                    && !equal(state.appBuild, appBuild)))) {
                JSONObject properties = new JSONObject();
                if (state.appVersion != null) {
                    put(properties, "previous_app_version", state.appVersion);
                }
                if (state.appBuild != null) {
                    put(properties, "previous_app_build", state.appBuild);
                }
                facts.add(fact("$mobile_app_update", state, nowWall, properties));
            }
            state.appObserved = true;
            if (appVersion != null) state.appVersion = appVersion;
            if (appBuild != null) state.appBuild = appBuild;
            facts.add(fact("$mobile_app_open", state, nowWall, null));
            if (!state.sessionStartEmitted) {
                facts.add(fact("$mobile_session_start", state, nowWall, null));
                state.sessionStartEmitted = true;
            }
        }
        persistFacts(state, facts);
        foreground = true;
        this.automatic = automatic;
        activeSinceElapsed = nowElapsed;
        return immutable(facts);
    }

    TimePoint captureTimePoint() {
        return new TimePoint(clock.wallMillis(), clock.elapsedMillis());
    }

    void requestPausePoint(TimePoint point) {
        CommittedState committed = committedState;
        requestedPause = new TimePoint(point.wallMillis, point.elapsedMillis,
                committed.sid, committed.lastActive);
    }

    synchronized long nextCheckpointElapsed() {
        long nowElapsed = clock.elapsedMillis();
        if (disabled || !foreground) return nowElapsed + ENGAGEMENT_THRESHOLD_MS;
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        if (state.accumulatedMs < ENGAGEMENT_THRESHOLD_MS) {
            long accumulated = state.accumulatedMs + Math.max(0, nowElapsed - activeSinceElapsed);
            return nowElapsed + Math.max(0, ENGAGEMENT_THRESHOLD_MS - accumulated);
        }
        return nowElapsed + ENGAGEMENT_THRESHOLD_MS;
    }

    synchronized TimePoint capturePausePoint() {
        return capturePausePoint(captureTimePoint());
    }

    synchronized TimePoint capturePausePoint(TimePoint point) {
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        TimePoint requested = requestedPause;
        long lastActive = state.lastActive;
        if (requested != null && requested.elapsedMillis == point.elapsedMillis) {
            lastActive = equal(requested.sid, state.sid) ? requested.lastActive
                    : Math.min(lastActive, point.wallMillis);
        }
        pendingPause = new TimePoint(point.wallMillis, point.elapsedMillis, state.sid,
                lastActive);
        if (requestedPause != null
                && requestedPause.elapsedMillis <= point.elapsedMillis) {
            requestedPause = null;
        }
        return pendingPause;
    }

    synchronized void cancelPendingPause() {
        cancelPendingPause(captureTimePoint());
    }

    synchronized void cancelPendingPause(TimePoint resumedAt) {
        if (foreground && screenChangedDuringPause) {
            activeSinceElapsed = Math.max(activeSinceElapsed, clock.elapsedMillis());
        }
        pendingPause = null;
        if (requestedPause != null
                && requestedPause.elapsedMillis <= resumedAt.elapsedMillis) {
            requestedPause = null;
        }
        screenChangedDuringPause = false;
    }

    synchronized MobileSnapshot snapshotAt(TimePoint point) {
        if (disabled) return null;
        return snapshot(identity.getMobileState(token), point.wallMillis);
    }

    synchronized List<MobileFact> background() {
        return background(captureTimePoint());
    }

    synchronized List<MobileFact> background(TimePoint point) {
        if (disabled || !foreground) return Collections.emptyList();
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        long previousActiveSince = activeSinceElapsed;
        String previousScreen = activeScreen;
        long nowWall = point.wallMillis;
        long nowElapsed = point.elapsedMillis;
        if (point.sid != null && point.sid.equals(state.sid)) {
            // A track during pause debounce cannot move the foreground end or inactivity boundary.
            state.lastActive = point.lastActive;
        }
        List<MobileFact> facts = new ArrayList<>();
        reconcileClock(state, nowWall, nowElapsed, facts, true);
        accrue(state, nowElapsed);
        emitEngagement(state, nowWall, facts);
        state.lastActive = Math.max(state.lastActive, nowWall);
        try {
            persistFacts(state, facts);
        } catch (PersistentIdentity.MobileStatePersistenceException e) {
            activeSinceElapsed = previousActiveSince;
            activeScreen = previousScreen;
            throw e;
        }
        foreground = false;
        activeScreen = null;
        pendingPause = null;
        if (requestedPause != null
                && requestedPause.elapsedMillis <= point.elapsedMillis) {
            requestedPause = null;
        }
        screenChangedDuringPause = false;
        return immutable(facts);
    }

    synchronized List<MobileFact> checkpoint() {
        if (disabled || !foreground) return Collections.emptyList();
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        long previousActiveSince = activeSinceElapsed;
        String previousScreen = activeScreen;
        long nowWall = clock.wallMillis();
        long nowElapsed = clock.elapsedMillis();
        List<MobileFact> facts = new ArrayList<>();
        reconcileClock(state, nowWall, nowElapsed, facts, true);
        accrue(state, nowElapsed);
        if (state.accumulatedMs >= ENGAGEMENT_THRESHOLD_MS) {
            emitEngagement(state, nowWall, facts);
        }
        state.lastActive = Math.max(state.lastActive, nowWall);
        try {
            persistFacts(state, facts);
        } catch (PersistentIdentity.MobileStatePersistenceException e) {
            activeSinceElapsed = previousActiveSince;
            activeScreen = previousScreen;
            throw e;
        }
        return immutable(facts);
    }

    synchronized List<MobileFact> screen(String name) {
        if (name == null || !name.matches("^[A-Za-z][A-Za-z0-9 _-]{0,79}$")
                || name.endsWith(" ")) {
            throw new IllegalArgumentException("name must be a stable screen label");
        }
        if (disabled) return Collections.emptyList();
        long nowWall = clock.wallMillis();
        long nowElapsed = clock.elapsedMillis();
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        long previousActiveSince = activeSinceElapsed;
        String previousScreen = activeScreen;
        boolean previousScreenChangedDuringPause = screenChangedDuringPause;
        List<MobileFact> facts = new ArrayList<>();
        reconcileClock(state, nowWall, nowElapsed, facts, true);
        boolean expired = state.sid != null && !foreground
                && nowWall - state.lastActive >= SESSION_TIMEOUT_MS;
        if (!expired && name.equals(activeScreen)) return Collections.emptyList();
        if (expired && automatic) {
            facts.add(fact("$mobile_session_end", state, nowWall, null));
        }
        if (state.sid == null || expired) {
            startNew(state, nowWall);
            activeScreen = null;
        }
        if (foreground) accrue(state, nowElapsed);
        emitEngagement(state, nowWall, facts);
        activeScreen = name;
        if (effectivePausePoint() != null) screenChangedDuringPause = true;
        state.lastActive = Math.max(state.lastActive, nowWall);
        JSONObject properties = new JSONObject();
        put(properties, "screen_name", name);
        facts.add(fact("$mobile_screen_view", state, nowWall, properties));
        try {
            persistFacts(state, facts);
        } catch (PersistentIdentity.MobileStatePersistenceException e) {
            activeSinceElapsed = previousActiveSince;
            activeScreen = previousScreen;
            screenChangedDuringPause = previousScreenChangedDuringPause;
            throw e;
        }
        return immutable(facts);
    }

    synchronized MobileSnapshot snapshot() {
        if (disabled) return null;
        long nowWall = clock.wallMillis();
        long nowElapsed = clock.elapsedMillis();
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        List<MobileFact> facts = new ArrayList<>();
        reconcileClock(state, nowWall, nowElapsed, facts, true);
        boolean expired = state.sid != null && !foreground
                && nowWall - state.lastActive >= SESSION_TIMEOUT_MS;
        if (expired && automatic) {
            facts.add(fact("$mobile_session_end", state, nowWall, null));
        }
        if (state.sid == null || expired) {
            startNew(state, nowWall);
        }
        state.lastActive = Math.max(state.lastActive, nowWall);
        persistFacts(state, facts);
        return snapshot(state, nowWall);
    }

    synchronized List<MobileFact> rotate() {
        if (disabled) return Collections.emptyList();
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        if (state.sid == null) return Collections.emptyList();
        TimePoint pause = effectivePausePoint();
        long nowWall = pause == null ? clock.wallMillis() : pause.wallMillis;
        long nowElapsed = pause == null ? clock.elapsedMillis() : pause.elapsedMillis;
        if (clockInvalid(state, nowWall)) {
            List<MobileFact> facts = new ArrayList<>();
            if (foreground) accrue(state, nowElapsed);
            emitEngagement(state, nowWall, facts);
            if (automatic) facts.add(fact("$mobile_session_end", state, nowWall, null));
            startNew(state, nowWall);
            activeSinceElapsed = nowElapsed;
            activeScreen = null;
            persistFacts(state, facts);
            return immutable(facts);
        }
        if (foreground) accrue(state, nowElapsed);
        List<MobileFact> facts = new ArrayList<>();
        emitEngagement(state, nowWall, facts);
        if (automatic) facts.add(fact("$mobile_session_end", state, nowWall, null));
        if (foreground) {
            startNew(state, nowWall);
            activeSinceElapsed = nowElapsed;
        } else {
            clearSession(state);
        }
        persistFacts(state, facts);
        return immutable(facts);
    }

    synchronized List<MobileFact> continueAfterIdentityChange() {
        if (disabled || !foreground || !automatic) return Collections.emptyList();
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        if (state.sid == null || state.sessionStartEmitted) return Collections.emptyList();
        List<MobileFact> facts = new ArrayList<>();
        facts.add(fact("$mobile_session_start", state, state.startedAt, null));
        state.sessionStartEmitted = true;
        persistFacts(state, facts);
        return immutable(facts);
    }

    synchronized void disable() {
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        clearSession(state);
        state.pendingFacts = new org.json.JSONArray();
        disabled = true;
        foreground = false;
        activeScreen = null;
        pendingPause = null;
        requestedPause = null;
        committedState = new CommittedState(null, 0);
        screenChangedDuringPause = false;
        if (!identity.getOptOut()) identity.saveMobileState(token, state);
    }

    synchronized void enable() {
        disabled = identity.getOptOut();
    }

    synchronized List<MobileFact> pendingFacts() {
        if (disabled) return Collections.emptyList();
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        List<MobileFact> facts = new ArrayList<>();
        long futureLimit = clock.wallMillis() + MAX_FUTURE_MS;
        for (int i = 0; i < state.pendingFacts.length(); i++) {
            JSONObject value = state.pendingFacts.optJSONObject(i);
            if (value == null) continue;
            // A deferred future fact holds later facts so replay retains first-open and session order.
            if (value.optLong("occurred_at") > futureLimit) break;
            facts.add(MobileFact.fromJson(value));
        }
        return immutable(facts);
    }

    synchronized boolean hasPendingFact(String id) {
        PersistentIdentity.MobileState state = identity.getMobileState(token);
        for (int i = 0; i < state.pendingFacts.length(); i++) {
            JSONObject value = state.pendingFacts.optJSONObject(i);
            if (value != null && id.equals(value.optString("id"))) return true;
        }
        return false;
    }

    private boolean reconcileClock(PersistentIdentity.MobileState state, long nowWall,
                                   long nowElapsed, List<MobileFact> facts, boolean startFact) {
        if (!clockInvalid(state, nowWall)) return false;
        if (foreground) {
            accrue(state, nowElapsed);
            emitEngagement(state, nowWall, facts);
        }
        startNew(state, nowWall);
        activeSinceElapsed = nowElapsed;
        activeScreen = null;
        if (startFact && foreground && automatic) {
            facts.add(fact("$mobile_session_start", state, nowWall, null));
            state.sessionStartEmitted = true;
        }
        return true;
    }

    private static boolean clockInvalid(PersistentIdentity.MobileState state, long nowWall) {
        return state.sid != null && (state.startedAt - nowWall > MAX_FUTURE_MS
                || state.lastActive - nowWall > MAX_FUTURE_MS);
    }

    private void accrue(PersistentIdentity.MobileState state, long nowElapsed) {
        TimePoint pause = effectivePausePoint();
        long endElapsed = pause == null ? nowElapsed
                : Math.min(nowElapsed, pause.elapsedMillis);
        long duration = Math.max(0, endElapsed - activeSinceElapsed);
        state.accumulatedMs += duration;
        state.pendingMs += duration;
        activeSinceElapsed = Math.max(activeSinceElapsed, endElapsed);
    }

    private void emitEngagement(PersistentIdentity.MobileState state, long nowWall,
                                List<MobileFact> facts) {
        if (state.pendingMs <= 0) return;
        if (automatic) {
            JSONObject properties = new JSONObject();
            put(properties, "engagement_duration_ms", state.pendingMs);
            if (activeScreen != null) put(properties, "screen_name", activeScreen);
            TimePoint pause = effectivePausePoint();
            facts.add(fact("$mobile_session_engagement", state,
                    pause == null ? nowWall : pause.wallMillis, properties));
        }
        state.pendingMs = 0;
    }

    private MobileFact fact(String name, PersistentIdentity.MobileState state, long nowWall,
                            JSONObject properties) {
        return new MobileFact(UUID.randomUUID().toString(), identity.getVisitorId(), name,
                snapshot(state, nowWall), properties);
    }

    private void persistFacts(PersistentIdentity.MobileState state, List<MobileFact> facts) {
        for (MobileFact fact : facts) state.pendingFacts.put(fact.toJson());
        identity.saveMobileState(token, state);
        committedState = new CommittedState(state.sid, state.lastActive);
    }

    private static boolean hasPending(PersistentIdentity.MobileState state, String name) {
        for (int i = 0; i < state.pendingFacts.length(); i++) {
            JSONObject value = state.pendingFacts.optJSONObject(i);
            if (value != null && name.equals(value.optString("event"))) return true;
        }
        return false;
    }

    private MobileSnapshot snapshot(PersistentIdentity.MobileState state, long nowWall) {
        TimePoint pause = effectivePausePoint();
        long lastActive = pause == null ? state.lastActive
                : Math.min(state.lastActive, pause.wallMillis);
        return new MobileSnapshot(state.sid, state.startedAt,
                Math.max(Math.max(state.startedAt, lastActive), nowWall),
                appVersion, appBuild);
    }

    private TimePoint effectivePausePoint() {
        TimePoint requested = requestedPause;
        if (pendingPause == null) return requested;
        if (requested == null || pendingPause.elapsedMillis <= requested.elapsedMillis) {
            return pendingPause;
        }
        return requested;
    }

    private static void startNew(PersistentIdentity.MobileState state, long nowWall) {
        state.sid = UUID.randomUUID().toString();
        state.startedAt = nowWall;
        state.lastActive = nowWall;
        state.accumulatedMs = 0;
        state.pendingMs = 0;
        state.sessionStartEmitted = false;
    }

    private static void clearSession(PersistentIdentity.MobileState state) {
        state.sid = null;
        state.startedAt = 0;
        state.lastActive = 0;
        state.accumulatedMs = 0;
        state.pendingMs = 0;
        state.sessionStartEmitted = false;
    }

    private static List<MobileFact> immutable(List<MobileFact> facts) {
        return Collections.unmodifiableList(facts);
    }

    private static String optional(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean equal(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static JSONObject copy(JSONObject source) {
        JSONObject result = new JSONObject();
        if (source == null) return result;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            put(result, key, source.opt(key));
        }
        return result;
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String utc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }
}
