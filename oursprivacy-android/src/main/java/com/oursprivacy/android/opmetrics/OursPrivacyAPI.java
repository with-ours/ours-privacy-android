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
 * SDK serializes them onto a background worker.
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

    private volatile boolean mInitialized;
    private String mToken;
    private boolean mTrackAutomaticEvents;
    private OPConfig mConfig;
    private PersistentIdentity mPersistence;
    private AnalyticsMessages mMessages;
    private JSONObject mBaseDefaultProperties;
    private OursPrivacyActivityLifecycleCallbacks mLifecycleCallbacks;

    /**
     * Construct an SDK instance. Call {@link #initialize(String, OursPrivacyInitOptions)}
     * exactly once before any other method.
     *
     * @param context any context (reduced to application context internally)
     */
    public OursPrivacyAPI(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        mContext = context.getApplicationContext();
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
        mConfig = OPConfig.getInstance(mContext);

        final SharedPreferencesLoader loader = new SharedPreferencesLoader();
        final Future<SharedPreferences> prefs = loader.loadPreferences(mContext, PREFS_NAME, null);
        mPersistence = new PersistentIdentity(prefs);
        mBaseDefaultProperties = OPDefaultProperties.snapshot(mContext);
        mMessages = new AnalyticsMessages(mContext, mConfig, mToken, mPersistence);

        wipeLegacyArtifactsIfNeeded(prefs);
        registerLifecycleCallbacks();
        if (!mConfig.getDisableExceptionHandler()) {
            ExceptionHandler.init(this);
        }

        // Flip the initialized flag BEFORE applying options so option-driven calls
        // (e.g. trackDeepLink for initialURL) can route through the normal public API.
        mInitialized = true;

        applyInitializationOptions(options);

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
            mPersistence.setOptOut(true);
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
            mPersistence.setVisitorId(options.getVisitorId(), true);
        }
        if (options.getInitialURL() != null) {
            trackDeepLink(options.getInitialURL());
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

    /** Package-internal track entry — lets the lifecycle callbacks tag $ae_* events as automatic. */
    void track(String eventName, JSONObject eventProperties, boolean isAutomaticEvent) {
        track(eventName, eventProperties, null, isAutomaticEvent);
    }

    private void track(String eventName,
                       JSONObject eventProperties,
                       OursPrivacyUserProperties userProperties,
                       boolean isAutomaticEvent) {
        if (!requireInitialized("track")) return;
        if (mPersistence.getOptOut()) return;
        if (isAutomaticEvent && !mTrackAutomaticEvents) return;

        try {
            final Track.Context ctx = buildTrackContext();
            final JSONObject wireUser = userProperties == null ? null : userProperties.toWireProperties();
            final JSONObject item = Track.composeTrackEvent(eventName, eventProperties, wireUser, ctx);
            mMessages.enqueue(item);
        } catch (JSONException e) {
            OPLog.e(LOGTAG, "Failed to compose track event " + eventName, e);
        }
    }

    public void identify(OursPrivacyUserProperties userProperties) {
        if (!requireInitialized("identify")) return;
        if (mPersistence.getOptOut()) return;
        try {
            final Track.Context ctx = buildTrackContext();
            final JSONObject wireUser = userProperties == null ? null : userProperties.toWireProperties();
            final JSONObject item = Track.composeIdentifyEvent(wireUser, ctx);
            mMessages.enqueue(item);
        } catch (JSONException e) {
            OPLog.e(LOGTAG, "Failed to compose identify event", e);
        }
    }

    /**
     * Parses {@code url} for UTM keys, click-ID keys, and {@code ours_visitor_id};
     * replaces the attribution default-property overlay; fires {@code $deep_link_opened}.
     * If {@code ours_visitor_id} is present, also calls {@link #setVisitorId(String)}.
     */
    public void trackDeepLink(String url) {
        if (!requireInitialized("trackDeepLink")) return;
        if (url == null || url.isEmpty()) return;
        if (mPersistence.getOptOut()) return;

        final Attribution.Result attribution = Attribution.parseAttributionFromURL(url);

        if (attribution.oursVisitorId != null) {
            setVisitorId(attribution.oursVisitorId);
        }

        final JSONObject replacement = new JSONObject();
        try {
            Track.mergeOnto(replacement, attribution.utmParams);
            Track.mergeOnto(replacement, attribution.clickIds);
        } catch (JSONException ignored) {}
        mPersistence.replaceAttributionDefaultProperties(replacement);

        final JSONObject eventProps = new JSONObject();
        try {
            eventProps.put("url", url);
        } catch (JSONException ignored) {}
        track("$deep_link_opened", eventProps);
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
    public void setVisitorId(String visitorId) {
        if (!requireInitialized("setVisitorId")) return;
        if (visitorId == null || visitorId.isEmpty()) {
            OPLog.w(LOGTAG, "setVisitorId called with null/empty id; ignoring");
            return;
        }
        mPersistence.setVisitorId(visitorId, true);
    }

    // ---------- default-property bags ----------

    public void updateDefaultEventProperties(JSONObject properties) {
        if (!requireInitialized("updateDefaultEventProperties")) return;
        if (properties == null) return;
        mPersistence.updateDefaultEventProperties(properties);
    }

    public void updateDefaultUserCustomProperties(JSONObject properties) {
        if (!requireInitialized("updateDefaultUserCustomProperties")) return;
        if (properties == null) return;
        mPersistence.updateDefaultUserCustomProperties(properties);
    }

    public void updateDefaultUserConsentProperties(JSONObject properties) {
        if (!requireInitialized("updateDefaultUserConsentProperties")) return;
        if (properties == null) return;
        mPersistence.updateDefaultUserConsentProperties(properties);
    }

    // ---------- opt-out ----------

    public void optOutTracking() {
        if (!requireInitialized("optOutTracking")) return;
        // Pending events are discarded — the visitor explicitly asked to stop
        // being tracked. visitor_id rotates inside optOutAndClear so a later
        // opt-in starts with a fresh identity.
        mMessages.clearQueue();
        mPersistence.optOutAndClear();
    }

    public void optInTracking() {
        if (!requireInitialized("optInTracking")) return;
        mPersistence.setOptOut(false);
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

    public void flush() {
        if (!requireInitialized("flush")) return;
        if (mPersistence.getOptOut()) return;
        mMessages.flushNow();
    }

    public void reset() {
        if (!requireInitialized("reset")) return;
        // Best-effort flush: pending events get one chance to land before
        // persistence is wiped on the calling thread.
        mMessages.flushNow();
        mPersistence.reset();
    }

    // ---------- package-internal hooks ----------

    boolean getTrackAutomaticEvents() {
        return mTrackAutomaticEvents;
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

    void onBackground() {
        if (mConfig != null && mConfig.getFlushOnBackground()) {
            flush();
        }
    }

    void onForeground() {
        // intentional no-op — session metadata isn't part of the canonical envelope
    }

    // ---------- internals ----------

    private boolean requireInitialized(String methodName) {
        if (mInitialized) return true;
        OPLog.w(LOGTAG, "OursPrivacyAPI." + methodName
                + " called before initialize(token, options). Call is a no-op.");
        return false;
    }

    private Track.Context buildTrackContext() {
        return new Track.Context(
                mPersistence.getVisitorId(),
                mPersistence.getDefaultEventProperties(),
                mPersistence.getDefaultUserCustomProperties(),
                mPersistence.getDefaultUserConsentProperties(),
                mPersistence.getAttributionDefaultProperties(),
                mBaseDefaultProperties);
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
