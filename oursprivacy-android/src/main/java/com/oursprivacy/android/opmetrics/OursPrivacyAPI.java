package com.oursprivacy.android.opmetrics;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.oursprivacy.android.util.OPLog;
import com.oursprivacy.android.util.ProxyServerInteractor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Future;

/**
 * Public entry point for the OursPrivacy Android SDK.
 *
 * <p>Construct one instance per app, then call
 * {@link #initialize(String, OursPrivacyInitOptions)} exactly once with your
 * project token. Every other method is a no-op (and logs a warning) until
 * {@code initialize} has run. Tracking calls are safe from any thread; the
 * SDK serializes composition and queue insertion; delivery runs on a background worker.
 *
 * <pre>{@code
 * OursPrivacyAPI op = new OursPrivacyAPI(context);
 * op.initialize("YOUR_TOKEN", OursPrivacyInitOptions.builder()
 *     .trackAutomaticEvents(true)
 *     .build());
 * op.track("App Opened");
 * op.identify(
 *     OursPrivacyUserProperties.builder()
 *         .externalId("user_42")
 *         .email("alex@example.com")
 *         .build());
 * }</pre>
 */
public class OursPrivacyAPI {

    public static final String VERSION = OPConfig.VERSION;

    private static final String PREFS_NAME = "com.oursprivacy.android.OursPrivacy";
    private static final String KEY_LEGACY_WIPED = "legacy_db_wiped";

    private final Context mContext;
    private final MobileSession.Clock mMobileClock;
    private final Future<SharedPreferences> mProvidedPreferences;

    private volatile boolean mInitialized;
    private String mToken;
    private boolean mTrackAutomaticEvents;
    private boolean mTrackAutomaticCrashes;
    private OPConfig mConfig;
    private PersistentIdentity mPersistence;
    private AnalyticsMessages mMessages;
    private JSONObject mBaseDefaultProperties;
    private MobileSession mMobileSession;
    private OursPrivacyActivityLifecycleCallbacks mLifecycleCallbacks;

    /**
     * Construct an SDK instance. Call {@link #initialize(String, OursPrivacyInitOptions)}
     * exactly once before any other method.
     *
     * @param context any context (reduced to application context internally)
     */
    public OursPrivacyAPI(Context context) {
        this(context, null, null);
    }

    OursPrivacyAPI(Context context, MobileSession.Clock mobileClock,
                   Future<SharedPreferences> preferences) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        mContext = context.getApplicationContext();
        mMobileClock = mobileClock;
        mProvidedPreferences = preferences;
    }

    /**
     * Apply your project token and bootstrap options. Must be called exactly once
     * before any other public method. Pass {@code null} for {@code options} to
     * accept all defaults.
     *
     * @param token   ingest project token. Required.
     * @param options optional bag of bootstrap settings. May be null.
     */
    public synchronized void initialize(String token, OursPrivacyInitOptions options) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("token is required");
        }
        if (mInitialized) {
            OPLog.w(LOGTAG, "initialize called more than once; ignoring this call.");
            return;
        }

        mToken = token;
        mTrackAutomaticEvents = options != null
                && Boolean.TRUE.equals(options.getTrackAutomaticEvents());
        mTrackAutomaticCrashes = options != null && options.getTrackAutomaticCrashes();
        mConfig = OPConfig.getInstance(mContext);

        final SharedPreferencesLoader loader = new SharedPreferencesLoader();
        final Future<SharedPreferences> prefs = mProvidedPreferences == null
                ? loader.loadPreferences(mContext, PREFS_NAME, null) : mProvidedPreferences;
        mPersistence = mMobileClock == null
                ? new PersistentIdentity(prefs)
                : new PersistentIdentity(prefs, mMobileClock::wallMillis);
        mBaseDefaultProperties = OPDefaultProperties.snapshot(mContext);
        mMessages = new AnalyticsMessages(mContext, mConfig, mToken, mPersistence,
                options == null ? null : options.getIngestRejectionListener());

        wipeLegacyArtifactsIfNeeded(prefs);
        mMobileSession = mMobileClock == null
                ? new MobileSession(mPersistence, mToken,
                        mBaseDefaultProperties.optString("app_version", null),
                        mBaseDefaultProperties.optString("app_build", null))
                : new MobileSession(mPersistence, mToken,
                        mBaseDefaultProperties.optString("app_version", null),
                        mBaseDefaultProperties.optString("app_build", null), mMobileClock);
        mInitialized = true;
        applyInitializationOptions(options);
        registerLifecycleCallbacks();
        if (mTrackAutomaticCrashes && !mConfig.getDisableExceptionHandler()) {
            ExceptionHandler.init(this);
        }
        drainMobileFacts();
        if (options != null && options.getInitialURL() != null
                && !options.getInitialURL().isEmpty() && !mPersistence.getOptOut()) {
            track("$deep_link_opened", (JSONObject) null);
        }

        emitFirstLaunchAndUpdateEventsIfNeeded();
        if (!mConfig.getDisableAppOpenEvent() && mTrackAutomaticEvents) {
            track("$app_open", null, true);
        }
    }

    private void applyInitializationOptions(OursPrivacyInitOptions options) {
        if (options == null) return;

        if (options.getServerURL() != null) {
            mConfig.setServerURL(options.getServerURL());
        }
        if (Boolean.TRUE.equals(options.getOptedOutByDefault()) && !mPersistence.hasOptOutFlag()) {
            optOutTracking();
        }
        if (options.getDefaultEventProperties() != null) {
            mPersistence.updateDefaultEventProperties(options.getDefaultEventProperties());
        }
        if (options.getDefaultUserCustomProperties() != null) {
            mPersistence.updateDefaultUserCustomProperties(options.getDefaultUserCustomProperties());
        }
        if (options.getDefaultUserConsentProperties() != null) {
            mPersistence.updateDefaultUserConsentProperties(options.getDefaultUserConsentProperties());
        }
        if (options.getVisitorId() != null) {
            setVisitorId(options.getVisitorId());
        }
        if (options.getInitialURL() != null && !options.getInitialURL().isEmpty()
                && !mPersistence.getOptOut()) {
            applyAttribution(Attribution.parseAttributionFromURL(options.getInitialURL()));
        }
    }

    // ---------- tracking ----------

    public void track(String eventName) {
        track(eventName, null, null, false);
    }

    public void track(String eventName, JSONObject eventProperties) {
        track(eventName, eventProperties, null, false);
    }

    public void track(String eventName, JSONObject eventProperties, OursPrivacyUserProperties userProperties) {
        track(eventName, eventProperties, userProperties, false);
    }

    /**
     * Records a stable screen destination. Call when navigation reaches a new
     * destination; repeated calls with the same name are ignored.
     */
    public synchronized void trackScreen(String name) {
        if (!requireInitialized("trackScreen")) return;
        if (mPersistence.getOptOut()) return;
        mMobileSession.screen(name);
        drainMobileFacts();
    }

    /** Package-internal track entry — lets the lifecycle callbacks tag $ae_* events as automatic. */
    void track(String eventName, JSONObject eventProperties, boolean isAutomaticEvent) {
        track(eventName, eventProperties, null, isAutomaticEvent);
    }

    private synchronized void track(String eventName,
                       JSONObject eventProperties,
                       OursPrivacyUserProperties userProperties,
                       boolean isAutomaticEvent) {
        if (!requireInitialized("track")) return;
        if (mPersistence.getOptOut()) return;
        if (isAutomaticEvent && !mTrackAutomaticEvents) return;

        try {
            drainMobileFacts();
            MobileSession.MobileSnapshot snapshot = mMobileSession.snapshot();
            drainMobileFacts();
            final Track.Context ctx = buildTrackContext(mPersistence.getVisitorId(), snapshot);
            final JSONObject wireUser = userProperties == null ? null : userProperties.toWireProperties();
            final JSONObject item = Track.composeTrackEvent(eventName, eventProperties, wireUser, ctx);
            enqueueTrackItem(item);
        } catch (JSONException e) {
            OPLog.e(LOGTAG, "Failed to compose track event " + eventName, e);
        }
    }

    public synchronized void identify(OursPrivacyUserProperties userProperties) {
        if (!requireInitialized("identify")) return;
        if (mPersistence.getOptOut()) return;
        try {
            drainMobileFacts();
            final Track.Context ctx = buildTrackContext(mPersistence.getVisitorId(), null);
            final JSONObject wireUser = userProperties == null ? null : userProperties.toWireProperties();
            final JSONObject item = Track.composeIdentifyEvent(wireUser, ctx);
            enqueueTrackItem(item);
        } catch (JSONException e) {
            OPLog.e(LOGTAG, "Failed to compose identify event", e);
        }
    }

    /**
     * Parses {@code url} for UTM keys, click-ID keys, and {@code ours_visitor_id};
     * replaces the attribution default-property overlay; fires {@code $deep_link_opened}.
     * If {@code ours_visitor_id} is present, also calls {@link #setVisitorId(String)}.
     */
    public synchronized void trackDeepLink(String url) {
        if (!requireInitialized("trackDeepLink")) return;
        if (url == null || url.isEmpty()) return;
        if (mPersistence.getOptOut()) return;

        applyAttribution(Attribution.parseAttributionFromURL(url));
        track("$deep_link_opened", (JSONObject) null);
    }

    private void applyAttribution(Attribution.Result attribution) {
        if (attribution.oursVisitorId != null) {
            if (mMobileSession == null) {
                mPersistence.setVisitorId(attribution.oursVisitorId, true);
            } else {
                setVisitorId(attribution.oursVisitorId);
            }
        }
        final JSONObject replacement = new JSONObject();
        try {
            Track.mergeOnto(replacement, attribution.utmParams);
            Track.mergeOnto(replacement, attribution.clickIds);
        } catch (JSONException ignored) {}
        mPersistence.replaceAttributionDefaultProperties(replacement);
    }

    // ---------- identity ----------

    public String getVisitorId() {
        if (!requireInitialized("getVisitorId")) return null;
        return mPersistence.getVisitorId();
    }

    /**
     * Replaces the auto-generated visitor_id with the caller-supplied value and
     * sets {@code is_manually_set_id: true} on every subsequent envelope.
     */
    public synchronized void setVisitorId(String visitorId) {
        if (!requireInitialized("setVisitorId")) return;
        if (visitorId == null || visitorId.isEmpty()) {
            OPLog.w(LOGTAG, "setVisitorId called with null/empty id; ignoring");
            return;
        }
        boolean changed = !visitorId.equals(mPersistence.getVisitorId());
        if (changed && mMobileSession != null) {
            drainMobileFacts();
            mMobileSession.rotate();
        }
        mPersistence.setVisitorId(visitorId, true);
        if (changed && mMobileSession != null) {
            mMobileSession.continueAfterIdentityChange();
            drainMobileFacts();
        }
    }

    // ---------- default-property bags ----------

    public synchronized void updateDefaultEventProperties(JSONObject properties) {
        if (!requireInitialized("updateDefaultEventProperties")) return;
        if (properties == null) return;
        mPersistence.updateDefaultEventProperties(properties);
    }

    public synchronized void updateDefaultUserCustomProperties(JSONObject properties) {
        if (!requireInitialized("updateDefaultUserCustomProperties")) return;
        if (properties == null) return;
        mPersistence.updateDefaultUserCustomProperties(properties);
    }

    public synchronized void updateDefaultUserConsentProperties(JSONObject properties) {
        if (!requireInitialized("updateDefaultUserConsentProperties")) return;
        if (properties == null) return;
        mPersistence.updateDefaultUserConsentProperties(properties);
    }

    // ---------- opt-out ----------

    public synchronized void optOutTracking() {
        if (!requireInitialized("optOutTracking")) return;
        // Pending events are discarded — the visitor explicitly asked to stop
        // being tracked. visitor_id rotates inside optOutAndClear so a later
        // opt-in starts with a fresh identity.
        mPersistence.optOutAndClear();
        mMobileSession.disable();
    }

    public synchronized void optInTracking() {
        if (!requireInitialized("optInTracking")) return;
        mPersistence.setOptOut(false);
        mMobileSession.enable();
        if (mTrackAutomaticEvents && mLifecycleCallbacks != null
                && mLifecycleCallbacks.isInForeground()) {
            onForeground();
        }
        track("$opt_in");
    }

    public boolean hasOptedOutTracking() {
        if (!requireInitialized("hasOptedOutTracking")) return false;
        return mPersistence.getOptOut();
    }

    // ---------- config ----------

    public void setLoggingEnabled(boolean enabled) {
        if (!requireInitialized("setLoggingEnabled")) return;
        mConfig.setLoggingEnabled(enabled);
    }

    public void setServerURL(String serverURL) {
        if (!requireInitialized("setServerURL")) return;
        mConfig.setServerURL(serverURL);
    }

    public void setServerURL(String serverURL, ProxyServerInteractor callback) {
        if (!requireInitialized("setServerURL")) return;
        mConfig.setServerURL(serverURL, callback);
    }

    public void setFlushBatchSize(int batchSize) {
        if (!requireInitialized("setFlushBatchSize")) return;
        mConfig.setFlushBatchSize(batchSize);
    }

    public int getFlushBatchSize() {
        if (!requireInitialized("getFlushBatchSize")) return 0;
        return mConfig.getFlushBatchSize();
    }

    public void setFlushOnBackground(boolean flushOnBackground) {
        if (!requireInitialized("setFlushOnBackground")) return;
        mConfig.setFlushOnBackground(flushOnBackground);
    }

    // ---------- lifecycle ----------

    public synchronized void flush() {
        if (!requireInitialized("flush")) return;
        if (mPersistence.getOptOut()) return;
        drainMobileFacts();
        mMessages.flushNow();
    }

    public synchronized void reset() {
        if (!requireInitialized("reset")) return;
        // Best-effort flush: pending events get one chance to land before
        // persistence is wiped on the calling thread.
        mMessages.flushNow();
        mMobileSession.rotate();
        mPersistence.resetPreservingMobileSession(mToken);
        mMobileSession.continueAfterIdentityChange();
        drainMobileFacts();
    }

    // ---------- package-internal hooks ----------

    boolean getTrackAutomaticEvents() {
        return mTrackAutomaticEvents;
    }

    boolean getTrackAutomaticCrashes() {
        return mTrackAutomaticCrashes;
    }

    /** Test-only: block until all queued work has been processed by the worker. */
    boolean awaitWorkerIdle(long timeoutMs) {
        if (mMessages == null) return true;
        return mMessages.awaitWorkerIdle(timeoutMs);
    }

    /** Test-only: stop the worker thread. Prevents leaked HandlerThreads from bleeding into the next test. */
    void shutdownForTests() {
        if (mMessages != null) mMessages.hardKill();
    }

    /** Test-only: park the worker so the caller can deterministically stack messages behind it. */
    void parkWorkerForTests(java.util.concurrent.CountDownLatch parked,
                            java.util.concurrent.CountDownLatch release) {
        if (mMessages != null) mMessages.parkWorker(parked, release);
    }

    synchronized void onBackground() {
        onBackground(mMobileSession == null ? null : mMobileSession.captureTimePoint(), null);
    }

    synchronized MobileSession.TimePoint captureMobileTimePoint() {
        return mMobileSession == null ? null : mMobileSession.captureTimePoint();
    }

    synchronized MobileSession.TimePoint captureMobilePausePoint() {
        return mMobileSession == null ? null : mMobileSession.capturePausePoint();
    }

    synchronized void onActivityResume() {
        if (mMobileSession != null) mMobileSession.cancelPendingPause();
    }

    synchronized void onBackground(MobileSession.TimePoint point) {
        onBackground(point, null);
    }

    synchronized void onBackground(MobileSession.TimePoint point,
                                   JSONObject legacySessionProperties) {
        if (!mInitialized) return;
        if (point == null) {
            point = mMobileSession.captureTimePoint();
        }
        mMobileSession.background(point);
        drainMobileFacts();
        if (legacySessionProperties != null && mTrackAutomaticEvents
                && !mPersistence.getOptOut()) {
            try {
                Track.Context context = buildTrackContext(mPersistence.getVisitorId(),
                        mMobileSession.snapshotAt(point));
                enqueueTrackItem(Track.composeTrackEvent(AutomaticEvents.SESSION,
                        legacySessionProperties, null, context));
            } catch (JSONException e) {
                OPLog.e(LOGTAG, "Failed to compose legacy session event", e);
            }
        }
        if (mConfig != null && mConfig.getFlushOnBackground()) {
            flush();
        }
    }

    synchronized void onCheckpoint() {
        if (!mInitialized || mPersistence.getOptOut()) return;
        mMobileSession.checkpoint();
        drainMobileFacts();
    }

    synchronized void onForeground() {
        if (!mInitialized || mPersistence.getOptOut()) return;
        drainMobileFacts();
        mMobileSession.foreground(mTrackAutomaticEvents);
        drainMobileFacts();
    }

    // ---------- internals ----------

    private boolean requireInitialized(String methodName) {
        if (mInitialized) return true;
        OPLog.w(LOGTAG, "OursPrivacyAPI." + methodName
                + " called before initialize(token, options). Call is a no-op.");
        return false;
    }

    private Track.Context buildTrackContext(String visitorId,
                                            MobileSession.MobileSnapshot snapshot) {
        return buildTrackContext(visitorId, snapshot,
                mPersistence.getAttributionDefaultProperties());
    }

    private Track.Context buildTrackContext(String visitorId,
                                            MobileSession.MobileSnapshot snapshot,
                                            JSONObject attributionProperties) {
        return new Track.Context(
                visitorId,
                mPersistence.getDefaultEventProperties(),
                mPersistence.getDefaultUserCustomProperties(),
                mPersistence.getDefaultUserConsentProperties(),
                attributionProperties,
                mBaseDefaultProperties,
                snapshot);
    }

    private void drainMobileFacts() {
        if (mMobileSession == null || mPersistence.getOptOut()) return;
        for (MobileSession.MobileFact fact : mMobileSession.pendingFacts()) {
            try {
                Track.Context context = buildTrackContext(fact.visitorId(), fact.snapshot(),
                        new JSONObject());
                JSONObject item = Track.composeTrackEvent(fact.eventName(),
                        fact.eventProperties(), null, context, fact.id());
                if (!mPersistence.enqueueMobileFact(mToken, fact.id(), item)) return;
                if (mPersistence.getQueueSize() >= mConfig.getBulkUploadLimit()) {
                    mMessages.flushNow();
                }
            } catch (JSONException | IllegalStateException e) {
                OPLog.w(LOGTAG, "Unable to queue mobile fact; will retry", e);
                return;
            }
        }
    }

    private void enqueueTrackItem(JSONObject item) {
        mPersistence.enqueueEvent(item);
        if (mPersistence.getQueueSize() >= mConfig.getBulkUploadLimit()) {
            mMessages.flushNow();
        }
    }

    private void registerLifecycleCallbacks() {
        if (mContext instanceof Application) {
            final Application app = (Application) mContext;
            mLifecycleCallbacks = new OursPrivacyActivityLifecycleCallbacks(this, mConfig);
            app.registerActivityLifecycleCallbacks(mLifecycleCallbacks);
        } else {
            OPLog.i(LOGTAG, "Context is not an Application; auto-flush on background is disabled.");
        }
    }

    private void emitFirstLaunchAndUpdateEventsIfNeeded() {
        if (!mTrackAutomaticEvents) return;
        try {
            final SharedPreferencesLoader loader = new SharedPreferencesLoader();
            final SharedPreferences prefs = loader.loadPreferences(mContext, PREFS_NAME, null).get();
            final boolean hasLaunched = prefs.getBoolean("has_launched", false);
            if (!hasLaunched) {
                track(AutomaticEvents.FIRST_OPEN, null, true);
                prefs.edit().putBoolean("has_launched", true).apply();
            }
            final int currentVersion = packageVersionCode();
            final int previousVersion = prefs.getInt("latest_version_code", -1);
            if (previousVersion == -1) {
                prefs.edit().putInt("latest_version_code", currentVersion).apply();
            } else if (currentVersion > previousVersion) {
                final JSONObject props = new JSONObject();
                try {
                    props.put(AutomaticEvents.VERSION_UPDATED, packageVersionName());
                } catch (JSONException ignored) {}
                track(AutomaticEvents.APP_UPDATED, props, true);
                prefs.edit().putInt("latest_version_code", currentVersion).apply();
            }
        } catch (Exception e) {
            OPLog.w(LOGTAG, "Failed to evaluate first-launch / app-updated events", e);
        }
    }

    private void wipeLegacyArtifactsIfNeeded(Future<SharedPreferences> prefsFuture) {
        try {
            final SharedPreferences prefs = prefsFuture.get();
            if (prefs.getBoolean(KEY_LEGACY_WIPED, false)) return;
            final File dbDir = new File(mContext.getApplicationInfo().dataDir, "databases");
            for (String name : new String[]{"oursprivacy", "oursprivacy-journal", "oursprivacy-wal", "oursprivacy-shm"}) {
                final File f = new File(dbDir, name);
                if (f.exists() && !f.delete()) {
                    OPLog.v(LOGTAG, "Couldn't delete legacy artifact " + f.getAbsolutePath());
                }
            }
            prefs.edit().putBoolean(KEY_LEGACY_WIPED, true).apply();
        } catch (Exception e) {
            OPLog.v(LOGTAG, "Skipped legacy-artifact wipe", e);
        }
    }

    @SuppressWarnings("deprecation")
    private int packageVersionCode() {
        try {
            final PackageInfo info = mContext.getPackageManager()
                    .getPackageInfo(mContext.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) info.getLongVersionCode()
                    : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private String packageVersionName() {
        try {
            return mContext.getPackageManager()
                    .getPackageInfo(mContext.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static final String LOGTAG = "OursPrivacy.API";
}
