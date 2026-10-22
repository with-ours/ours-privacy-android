package com.oursprivacy.android.opmetrics;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.oursprivacy.android.util.OPLog;
import com.oursprivacy.android.util.ProxyServerInteractor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
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
    private boolean mInitializing;
    private HandlerThread mTrackingThread;
    private Handler mTrackingHandler;
    private volatile boolean mTrackingClosed;
    private volatile String mPublicVisitorId;
    private volatile long mIdentityRevision;
    private volatile long mConsentGeneration;
    private String mToken;
    private boolean mTrackAutomaticEvents;
    private boolean mTrackAutomaticCrashes;
    private volatile boolean mPrivacyClearFailed;
    private CountDownLatch mPrivacyTransition;
    private final CountDownLatch mInitializationComplete = new CountDownLatch(1);
    private final List<Runnable> mPendingInitializationWork = new ArrayList<>();
    private volatile Throwable mInitializationFailure;
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
    public void initialize(String token, OursPrivacyInitOptions options) {
        synchronized (this) {
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("token is required");
            }
            if (mInitialized || mInitializing || mInitializationFailure != null) {
                OPLog.w(LOGTAG, "initialize called more than once; ignoring this call.");
                return;
            }

            mInitializing = true;
        }
        try {
            final boolean defaultOptOut;
            synchronized (this) {
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
                mPublicVisitorId = mPersistence.getVisitorId();
                defaultOptOut = options != null
                        && Boolean.TRUE.equals(options.getOptedOutByDefault())
                        && !mPersistence.hasOptOutFlag();
                if (defaultOptOut) mPersistence.revokeConsentInMemory();
                wipeLegacyArtifactsIfNeeded(prefs);
                mMobileSession = mMobileClock == null
                        ? new MobileSession(mPersistence, mToken,
                                mBaseDefaultProperties.optString("app_version", null),
                                mBaseDefaultProperties.optString("app_build", null))
                        : new MobileSession(mPersistence, mToken,
                                mBaseDefaultProperties.optString("app_version", null),
                                mBaseDefaultProperties.optString("app_build", null), mMobileClock);
                mTrackingThread = new HandlerThread("com.oursprivacy.android.TrackingWorker");
                mTrackingThread.start();
                mTrackingHandler = new Handler(mTrackingThread.getLooper());
                mMessages = new AnalyticsMessages(mContext, mConfig, mToken, mPersistence,
                        options == null ? null : options.getIngestRejectionListener());
                mInitialized = true;
                if (defaultOptOut) {
                    mMessages.revokeUploads();
                    mPersistence.revokeConsentInMemory();
                } else {
                    finishInitialization(options);
                }
            }
            if (defaultOptOut) {
                optOutTracking();
                synchronized (this) {
                    finishInitialization(options);
                }
            }
        } catch (RuntimeException e) {
            failInitialization(e);
        } catch (Error e) {
            failInitialization(e);
            throw e;
        } finally {
            completeInitialization();
        }
    }

    private void failInitialization(Throwable failure) {
        synchronized (this) {
            mInitializationFailure = failure;
            mInitialized = false;
            mTrackingClosed = true;
            mConsentGeneration++;
        }
        if (mPersistence != null) cleanupInitialization(mPersistence::revokeConsentInMemory);
        if (mMessages != null) {
            cleanupInitialization(() -> mMessages.cancelUploads(mMessages.revokeUploads()));
            cleanupInitialization(mMessages::hardKill);
        }
        if (mLifecycleCallbacks != null) {
            cleanupInitialization(mLifecycleCallbacks::onTrackingDisabled);
            cleanupInitialization(() ->
                    ((Application) mContext).unregisterActivityLifecycleCallbacks(mLifecycleCallbacks));
        }
        if (mTrackingThread != null) cleanupInitialization(() -> mTrackingThread.quitSafely());
        OPLog.w(LOGTAG, "Unable to initialize SDK; tracking disabled for this instance", failure);
    }

    private void cleanupInitialization(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            OPLog.w(LOGTAG, "Unable to finish initialization cleanup", e);
        }
    }

    private void finishInitialization(OursPrivacyInitOptions options) {
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
            setVisitorIdNow(options.getVisitorId());
            mPublicVisitorId = options.getVisitorId();
        }
        if (options.getInitialURL() != null && !options.getInitialURL().isEmpty()
                && !mPersistence.getOptOut()) {
            applyAttribution(Attribution.parseAttributionFromURL(options.getInitialURL()));
            mPublicVisitorId = mPersistence.getVisitorId();
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
    public void trackScreen(String name) {
        if (!requireInitialized("trackScreen") || mPersistence.getOptOut()) return;
        if (name == null || !name.matches("^[A-Za-z][A-Za-z0-9 _-]{0,79}$")
                || name.endsWith(" ")) {
            throw new IllegalArgumentException("name must be a stable screen label");
        }
        MobileSession.TimePoint point = captureMobileTimePoint();
        dispatchTracking(() -> trackScreenNow(name, point));
    }

    private void trackScreenNow(String name, MobileSession.TimePoint point) {
        if (!requireInitialized("trackScreen")) return;
        if (mPersistence.getOptOut()) return;
        settleResumedLifecycleForManualCall();
        mMobileSession.screen(name, point);
        drainMobileFacts();
    }

    /** Package-internal track entry — lets the lifecycle callbacks tag $ae_* events as automatic. */
    void track(String eventName, JSONObject eventProperties, boolean isAutomaticEvent) {
        track(eventName, eventProperties, null, isAutomaticEvent);
    }

    private void track(String eventName, JSONObject properties,
                       OursPrivacyUserProperties userProperties, boolean automatic) {
        if (!requireInitialized("track") || mPersistence.getOptOut()) return;
        try {
            JSONObject captured = copyProperties(properties);
            JSONObject wireUser = userProperties == null ? null
                    : copyProperties(userProperties.toWireProperties());
            MobileSession.TimePoint point = captureMobileTimePoint();
            dispatchTracking(() -> trackNow(eventName, captured, wireUser, automatic, point));
        } catch (RuntimeException e) {
            OPLog.w(LOGTAG, "Invalid user properties; dropping event", e);
        }
    }

    private void trackNow(String eventName, JSONObject eventProperties,
                          JSONObject wireUser, boolean isAutomaticEvent) {
        trackNow(eventName, eventProperties, wireUser, isAutomaticEvent,
                captureMobileTimePoint());
    }

    private void trackNow(String eventName, JSONObject eventProperties,
                          JSONObject wireUser, boolean isAutomaticEvent,
                          MobileSession.TimePoint point) {
        if (!requireInitialized("track")) return;
        if (mPersistence.getOptOut()) return;
        if (isAutomaticEvent && !mTrackAutomaticEvents) return;
        if (!isAutomaticEvent && eventName != null && eventName.startsWith("$mobile_")) return;

        try {
            if (!isAutomaticEvent) settleResumedLifecycleForManualCall();
            drainMobileFacts();
            MobileSession.MobileSnapshot snapshot = mMobileSession.snapshot(point);
            drainMobileFacts();
            final Track.Context ctx = buildTrackContext(mPersistence.getVisitorId(), snapshot);
            final JSONObject item = Track.composeTrackEvent(eventName, eventProperties, wireUser, ctx);
            enqueueTrackItem(item);
        } catch (JSONException e) {
            OPLog.e(LOGTAG, "Failed to compose track event " + eventName, e);
        }
    }

    public void identify(OursPrivacyUserProperties userProperties) {
        if (!requireInitialized("identify") || mPersistence.getOptOut()) return;
        try {
            JSONObject wireUser = userProperties == null ? null
                    : copyProperties(userProperties.toWireProperties());
            MobileSession.TimePoint point = captureMobileTimePoint();
            dispatchTracking(() -> identifyNow(wireUser, point));
        } catch (RuntimeException e) {
            OPLog.w(LOGTAG, "Invalid user properties; dropping identify", e);
        }
    }

    private void identifyNow(JSONObject wireUser, MobileSession.TimePoint point) {
        if (!requireInitialized("identify")) return;
        if (mPersistence.getOptOut()) return;
        try {
            settleResumedLifecycleForManualCall();
            drainMobileFacts();
            MobileSession.MobileSnapshot snapshot = mMobileSession.snapshot(point);
            drainMobileFacts();
            final Track.Context ctx = buildTrackContext(mPersistence.getVisitorId(), snapshot);
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
        if (!requireInitialized("trackDeepLink") || mPersistence.getOptOut()
                || url == null || url.isEmpty()) return;
        Attribution.Result attribution = Attribution.parseAttributionFromURL(url);
        long revision = mIdentityRevision;
        if (attribution.oursVisitorId != null) {
            revision = ++mIdentityRevision;
            mPublicVisitorId = attribution.oursVisitorId;
        }
        long capturedRevision = revision;
        MobileSession.TimePoint point = captureMobileTimePoint();
        postTracking(() -> {
            try {
                applyAttribution(attribution, point);
                trackNow("$deep_link_opened", null, null, false, point);
            } catch (RuntimeException e) {
                if (capturedRevision == mIdentityRevision) {
                    mPublicVisitorId = mPersistence.getVisitorId();
                }
                throw e;
            }
        });
    }

    private void applyAttribution(Attribution.Result attribution) {
        applyAttribution(attribution, captureMobileTimePoint());
    }

    private void applyAttribution(Attribution.Result attribution, MobileSession.TimePoint point) {
        if (attribution.oursVisitorId != null) {
            if (mMobileSession == null) {
                mPersistence.setVisitorId(attribution.oursVisitorId, true);
            } else {
                setVisitorIdNow(attribution.oursVisitorId, point);

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
        return mPublicVisitorId;
    }

    /**
     * Replaces the auto-generated visitor_id with the caller-supplied value and
     * sets {@code is_manually_set_id: true} on every subsequent envelope.
     */
    public synchronized void setVisitorId(String visitorId) {
        if (!requireInitialized("setVisitorId") || visitorId == null || visitorId.isEmpty()) return;
        long revision = ++mIdentityRevision;
        mPublicVisitorId = visitorId;
        MobileSession.TimePoint point = captureMobileTimePoint();
        postTracking(() -> {
            try {
                setVisitorIdNow(visitorId, point);
                if (revision == mIdentityRevision) mPublicVisitorId = visitorId;
            }
            catch (RuntimeException e) {
                if (revision == mIdentityRevision) mPublicVisitorId = mPersistence.getVisitorId();
                throw e;
            }
        });
    }

    private void setVisitorIdNow(String visitorId) {
        setVisitorIdNow(visitorId, captureMobileTimePoint());
    }

    private void setVisitorIdNow(String visitorId, MobileSession.TimePoint point) {
        if (!requireInitialized("setVisitorId")) return;
        if (visitorId == null || visitorId.isEmpty()) {
            OPLog.w(LOGTAG, "setVisitorId called with null/empty id; ignoring");
            return;
        }
        settleResumedLifecycleForManualCall();
        boolean changed = !visitorId.equals(mPersistence.getVisitorId());
        if (changed && mMobileSession != null) {
            drainMobileFacts();
            mMobileSession.rotate(point);
        }
        mPersistence.setVisitorId(visitorId, true);
        if (changed && mMobileSession != null) {
            mMobileSession.continueAfterIdentityChange();
            drainMobileFacts();
        }
    }

    // ---------- default-property bags ----------

    public void updateDefaultEventProperties(JSONObject properties) {
        if (!requireInitialized("updateDefaultEventProperties")) return;
        JSONObject captured = copyProperties(properties);
        dispatchTracking(() -> updateDefaultEventPropertiesNow(captured));
    }

    private void updateDefaultEventPropertiesNow(JSONObject properties) {
        if (!requireInitialized("updateDefaultEventProperties")) return;
        if (properties == null) return;
        mPersistence.updateDefaultEventProperties(properties);
    }

    public void updateDefaultUserCustomProperties(JSONObject properties) {
        if (!requireInitialized("updateDefaultUserCustomProperties")) return;
        JSONObject captured = copyProperties(properties);
        dispatchTracking(() -> updateDefaultUserCustomPropertiesNow(captured));
    }

    private void updateDefaultUserCustomPropertiesNow(JSONObject properties) {
        if (!requireInitialized("updateDefaultUserCustomProperties")) return;
        if (properties == null) return;
        mPersistence.updateDefaultUserCustomProperties(properties);
    }

    public void updateDefaultUserConsentProperties(JSONObject properties) {
        if (!requireInitialized("updateDefaultUserConsentProperties")) return;
        JSONObject captured = copyProperties(properties);
        dispatchTracking(() -> updateDefaultUserConsentPropertiesNow(captured));
    }

    private void updateDefaultUserConsentPropertiesNow(JSONObject properties) {
        if (!requireInitialized("updateDefaultUserConsentProperties")) return;
        if (properties == null) return;
        mPersistence.updateDefaultUserConsentProperties(properties);
    }

    // ---------- opt-out ----------

    public void optOutTracking() {
        if (!requireInitialized("optOutTracking")) return;
        final CountDownLatch transition = beginPrivacyTransition();
        mConsentGeneration++;
        final List<UploadFence.Lease> active;
        final long identityRevision;
        synchronized (this) {
            identityRevision = ++mIdentityRevision;
            mPrivacyClearFailed = true;
            active = mMessages.revokeUploads();
            mPersistence.revokeConsentInMemory();
        }
        boolean cleared = false;
        try {
            mMessages.cancelUploads(active);
            AtomicBoolean clearSucceeded = new AtomicBoolean();
            runTrackingAndWait(() -> {
                try {
                    mPersistence.optOutAndClear();
                    if (identityRevision == mIdentityRevision) {
                        mPublicVisitorId = mPersistence.getVisitorId();
                    }
                    clearSucceeded.set(true);
                } finally {
                    mMobileSession.disable();
                    if (mLifecycleCallbacks != null) mLifecycleCallbacks.onTrackingDisabled();
                }
            });
            cleared = clearSucceeded.get();
        } finally {
            mMessages.awaitUploads(active);
            synchronized (this) {
                if (cleared) mPrivacyClearFailed = false;
            }
            endPrivacyTransition(transition);
        }
    }

    public void optInTracking() {
        if (!requireInitialized("optInTracking")) return;
        if (!awaitInitializationComplete()) return;
        CountDownLatch transition = beginPrivacyTransition();
        final long identityRevision;
        synchronized (this) { identityRevision = ++mIdentityRevision; }
        try {
            runTrackingAndWait(() -> {
                if (mPrivacyClearFailed) {
                    try {
                        mPersistence.optOutAndClear();
                        if (identityRevision == mIdentityRevision) {
                            mPublicVisitorId = mPersistence.getVisitorId();
                        }
                        mPrivacyClearFailed = false;
                    } catch (RuntimeException e) {
                        OPLog.w(LOGTAG, "Unable to complete privacy clear; staying opted out", e);
                        return;
                    }
                }
                mPersistence.setOptOut(false);
                mMessages.openUploads();
                mMobileSession.enable();
                if (mTrackAutomaticEvents && mLifecycleCallbacks != null) {
                    mLifecycleCallbacks.onTrackingEnabled();
                }
                trackNow("$opt_in", null, null, false);
            });
        } finally {
            endPrivacyTransition(transition);
        }
    }

    private boolean awaitInitializationComplete() {
        boolean interrupted = false;
        while (true) {
            try {
                mInitializationComplete.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return mInitializationFailure == null && mInitialized;
    }

    private CountDownLatch beginPrivacyTransition() {
        boolean interrupted = false;
        while (true) {
            CountDownLatch pending;
            synchronized (this) {
                pending = mPrivacyTransition;
                if (pending == null) {
                    mPrivacyTransition = new CountDownLatch(1);
                    if (interrupted) Thread.currentThread().interrupt();
                    return mPrivacyTransition;
                }
            }
            while (true) {
                try {
                    pending.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
    }

    private void endPrivacyTransition(CountDownLatch transition) {
        synchronized (this) {
            if (mPrivacyTransition == transition) mPrivacyTransition = null;
        }
        transition.countDown();
    }

    public boolean hasOptedOutTracking() {
        if (mInitializationFailure != null) return true;
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

    public void flush() {
        if (!requireInitialized("flush") || mPersistence.getOptOut()) return;
        dispatchTracking(this::flushNow);
    }

    private void flushNow() {
        if (!requireInitialized("flush")) return;
        if (mPersistence.getOptOut()) return;
        drainMobileFacts();
        mMessages.flushNow();
    }

    public synchronized void reset() {
        if (!requireInitialized("reset")) return;
        String visitorId = UUID.randomUUID().toString();
        long revision = ++mIdentityRevision;
        mPublicVisitorId = visitorId;
        MobileSession.TimePoint point = captureMobileTimePoint();
        postTracking(() -> {
            try {
                resetNow(visitorId, point);
                if (revision == mIdentityRevision) mPublicVisitorId = mPersistence.getVisitorId();
            }
            catch (RuntimeException e) {
                if (revision == mIdentityRevision) mPublicVisitorId = mPersistence.getVisitorId();
                throw e;
            }
        });
    }

    private void resetNow(String visitorId, MobileSession.TimePoint point) {
        if (!requireInitialized("reset")) return;
        if (mPrivacyClearFailed) {
            mPersistence.optOutAndClear();
            mPrivacyClearFailed = false;
            return;
        }
        settleResumedLifecycleForManualCall();
        drainMobileFacts();
        mMobileSession.rotate(point);
        try {
            mPersistence.resetPreservingMobileSession(mToken, visitorId);
            mPrivacyClearFailed = false;
        } finally {
            mMobileSession.continueAfterIdentityChange();
            drainMobileFacts();
        }
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
        return awaitTrackingIdle(timeoutMs) && mMessages.awaitWorkerIdle(timeoutMs);
    }

    /** Test-only: stop the worker thread. Prevents leaked HandlerThreads from bleeding into the next test. */
    void shutdownForTests() {
        awaitTrackingIdle(5_000);
        if (mMessages != null) mMessages.hardKill();
        mTrackingClosed = true;
        if (mTrackingThread != null) mTrackingThread.quitSafely();
    }

    /** Test-only: park the worker so the caller can deterministically stack messages behind it. */
    void parkWorkerForTests(java.util.concurrent.CountDownLatch parked,
                            java.util.concurrent.CountDownLatch release) {
        if (mMessages != null) mMessages.parkWorker(parked, release);
    }

    void onBackground() {
        if (!mInitialized) return;
        MobileSession.TimePoint point = captureMobileTimePoint();
        runLifecycleTracking(() -> onBackground(point, null));
    }

    MobileSession.TimePoint captureMobileTimePoint() {
        return mMobileSession == null ? null : mMobileSession.captureTimePoint();
    }

    void requestMobilePausePoint(MobileSession.TimePoint point) {
        if (mMobileSession != null) mMobileSession.requestPausePoint(point);
    }

    long nextMobileCheckpointElapsed() {
        return mMobileSession.nextCheckpointElapsed();
    }

    MobileSession.TimePoint captureMobilePausePoint(
            MobileSession.TimePoint point) {
        return mMobileSession == null ? null : mMobileSession.capturePausePoint(point);
    }

    void onActivityResume(MobileSession.TimePoint point) {
        if (mMobileSession != null) mMobileSession.cancelPendingPause(point);
    }

    void onBackground(MobileSession.TimePoint point) {
        onBackground(point, null);
    }

    void onBackground(MobileSession.TimePoint point,
                                   JSONObject legacySessionProperties) {
        if (!mInitialized || mPersistence.getOptOut()) return;
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
            } catch (JSONException | IllegalStateException e) {
                OPLog.e(LOGTAG, "Failed to record legacy session event", e);
            }
        }
        if (mConfig != null && mConfig.getFlushOnBackground()) {
            flushNow();
        }
    }

    void onCheckpoint() {
        if (!mInitialized) return;
        if (Looper.myLooper() != mTrackingHandler.getLooper()) {
            runLifecycleTracking(this::onCheckpoint);
            return;
        }
        if (!mInitialized || mPersistence.getOptOut()) return;
        mMobileSession.checkpoint();
        drainMobileFacts();
    }

    void onForeground() {
        if (!mInitialized) return;
        MobileSession.TimePoint point = captureMobileTimePoint();
        runLifecycleTracking(() -> onForeground(point));
    }

    void onForeground(MobileSession.TimePoint point) {
        if (!mInitialized || mPersistence.getOptOut()) return;
        drainMobileFacts();
        mMobileSession.foreground(mTrackAutomaticEvents,
                point == null ? mMobileSession.captureTimePoint() : point);
        drainMobileFacts();
    }

    private void settleResumedLifecycleForManualCall() {
        if (mLifecycleCallbacks != null) {
            mLifecycleCallbacks.settleResumedLifecycleForManualCall();
        }
    }

    private void postTracking(Runnable work) {
        long generation = mConsentGeneration;
        afterInitialization(() -> mTrackingHandler.post(() -> runTracking(() -> {
            if (!awaitInitializationComplete()) return;
            if (generation == mConsentGeneration) work.run();
        })));
    }

    private void dispatchTracking(Runnable work) {
        long generation = mConsentGeneration;
        afterInitialization(() -> runTracking(() -> {
            if (!awaitInitializationComplete()) return;
            if (generation == mConsentGeneration) work.run();
        }));
    }

    private synchronized void afterInitialization(Runnable dispatch) {
        if (mInitializationComplete.getCount() > 0) mPendingInitializationWork.add(dispatch);
        else dispatch.run();
    }

    private synchronized void completeInitialization() {
        mInitializing = false;
        mInitializationComplete.countDown();
        if (mInitializationFailure == null) {
            for (Runnable dispatch : mPendingInitializationWork) dispatch.run();
        }
        mPendingInitializationWork.clear();
    }

    Runnable lifecycleTracking(Runnable work) {
        long generation = mConsentGeneration;
        return () -> runTracking(() -> {
            if (generation == mConsentGeneration) work.run();
        });
    }

    void runLifecycleTracking(Runnable work) {
        lifecycleTracking(work).run();
    }

    boolean runTracking(Runnable work) {
        Runnable guarded = () -> {
            try { work.run(); }
            catch (RuntimeException e) { OPLog.w(LOGTAG, "Unable to record SDK operation", e); }
        };
        if (mTrackingHandler == null) return false;
        if (Looper.myLooper() == mTrackingHandler.getLooper()) {
            guarded.run();
            return true;
        }
        return mTrackingHandler.post(guarded);
    }

    private void runTrackingAndWait(Runnable work) {
        CountDownLatch completed = new CountDownLatch(1);
        if (!runTracking(() -> {
            try { work.run(); }
            finally { completed.countDown(); }
        })) return;
        awaitLatch(completed, 0);
    }

    boolean awaitTrackingIdle(long timeoutMs) {
        if (mTrackingClosed || mTrackingHandler == null || Looper.myLooper() == mTrackingHandler.getLooper()) return true;
        CountDownLatch idle = new CountDownLatch(1);
        mTrackingHandler.post(idle::countDown);
        return awaitLatch(idle, timeoutMs);
    }

    private static boolean awaitLatch(CountDownLatch latch, long timeoutMs) {
        try {
            if (timeoutMs > 0) return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            latch.await();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static JSONObject copyProperties(JSONObject properties) {
        if (properties == null) return null;
        try { return new JSONObject(properties.toString()); }
        catch (JSONException e) { OPLog.w(LOGTAG, "Invalid event properties"); return null; }
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
            if (!drainReadyTracks()) return;
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
        drainReadyTracks();
    }

    private void enqueueTrackItem(JSONObject item) {
        String afterFactId = mPersistence.latestPendingMobileFactId(mToken);
        mPersistence.holdTrack(mToken, item, afterFactId);
        drainMobileFacts();
    }

    private boolean drainReadyTracks() {
        while (mPersistence.hasHeldTracks(mToken)) {
            try {
                if (!mPersistence.moveReadyHeldTrack(mToken)) return true;
                if (mPersistence.getQueueSize() >= mConfig.getBulkUploadLimit()) {
                    mMessages.flushNow();
                }
            } catch (IllegalStateException e) {
                OPLog.w(LOGTAG, "Unable to queue held event; will retry", e);
                return false;
            }
        }
        return true;
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
