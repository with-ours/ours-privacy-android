package com.oursprivacy.android.opmetrics;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.core.content.ContextCompat;

import com.oursprivacy.android.util.OPLog;
import com.oursprivacy.android.util.ProxyServerInteractor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Future;


/**
 * Core class for interacting with OursPrivacy Analytics.
 *
 * <p>Call {@link #getInstance(Context, String, boolean)} with
 * your main application activity and your OursPrivacy API token as arguments
 * an to get an instance you can use to report how users are using your
 * application.
 *
 *
 * <p>The OursPrivacy library will periodically send information to
 * OursPrivacy servers, so your application will need to have
 * <code>android.permission.INTERNET</code>. In addition, to preserve
 * battery life, messages to OursPrivacy servers may not be sent immediately
 * when you call {@link #track(String)}.
 * The library will send messages periodically throughout the lifetime
 * of your application, but you will need to call {@link #flush()}
 * before your application is completely shutdown to ensure all of your
 * events are sent.
 *
 * <p>A typical use-case for the library might look like this:
 *
 * <pre>
 * {@code
 * public class MainActivity extends Activity {
 *      OursPrivacyAPI mOursPrivacy;
 *
 *      public void onCreate(Bundle saved) {
 *          mOursPrivacy = OursPrivacyAPI.getInstance(this, "YOUR OURSPRIVACY API TOKEN");
 *          ...
 *      }
 *
 *      public void whenSomethingInterestingHappens(int flavor) {
 *          JSONObject properties = new JSONObject();
 *          properties.put("flavor", flavor);
 *          mOursPrivacy.track("Something Interesting Happened", properties);
 *          ...
 *      }
 *
 *      public void onDestroy() {
 *          mOursPrivacy.flush();
 *          super.onDestroy();
 *      }
 * }
 * }
 * </pre>
 *
 * <p>In addition to this documentation, you may wish to take a look at
 * <a href="https://github.com/oursprivacy/sample-android-oursprivacy-integration">the OursPrivacy sample Android application</a>.
 * It demonstrates a variety of techniques.
 *
 * <p>There are also <a href="https://oursprivacy.com/docs/">step-by-step getting started documents</a>
 * available at oursprivacy.com
 *
 * @see <a href="https://oursprivacy.com/docs/integration-libraries/android">getting started documentation for tracking events</a>
 * @see <a href="https://oursprivacy.com/docs/people-analytics/android">getting started documentation for People Analytics</a>
 * @see <a href="https://github.com/oursprivacy/sample-android-oursprivacy-integration">The OursPrivacy Android sample application</a>
 */
public class OursPrivacyAPI {
    /**
     * String version of the library.
     */
    public static final String VERSION = OPConfig.VERSION;

    /**
     * You shouldn't instantiate OursPrivacyAPI objects directly.
     * Use OursPrivacyAPI.getInstance to get an instance.
     */
    OursPrivacyAPI(Context context, Future<SharedPreferences> referrerPreferences, String token, boolean optOutTrackingDefault, JSONObject superProperties, boolean trackAutomaticEvents) {
        this(context, referrerPreferences, token, OPConfig.getInstance(context, null), optOutTrackingDefault, superProperties, null, trackAutomaticEvents);
    }

    /**
     * You shouldn't instantiate OursPrivacyAPI objects directly.
     * Use OursPrivacyAPI.getInstance to get an instance.
     */
    OursPrivacyAPI(Context context, Future<SharedPreferences> referrerPreferences, String token, boolean optOutTrackingDefault, JSONObject superProperties, String instanceName, boolean trackAutomaticEvents) {
        this(context, referrerPreferences, token, OPConfig.getInstance(context, instanceName), optOutTrackingDefault, superProperties, instanceName, trackAutomaticEvents);
    }

    /**
     * You shouldn't instantiate OursPrivacyAPI objects directly.
     * Use OursPrivacyAPI.getInstance to get an instance.
     */
    OursPrivacyAPI(Context context, Future<SharedPreferences> referrerPreferences, String token, OPConfig config, boolean optOutTrackingDefault, JSONObject superProperties, String instanceName, boolean trackAutomaticEvents) {
        mContext = context;
        mToken = token;
        mInstanceName = instanceName;
        mConfig = config;
        mTrackAutomaticEvents = trackAutomaticEvents;

        final Map<String, String> deviceInfo = new HashMap<String, String>();
        deviceInfo.put("$android_lib_version", OPConfig.VERSION);
        deviceInfo.put("$android_os", "Android");
        deviceInfo.put("$android_os_version", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);
        deviceInfo.put("$android_manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
        deviceInfo.put("$android_brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
        deviceInfo.put("$android_model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);
        try {
            final PackageManager manager = mContext.getPackageManager();
            final PackageInfo info = manager.getPackageInfo(mContext.getPackageName(), 0);
            deviceInfo.put("$android_app_version", info.versionName);
            deviceInfo.put("$android_app_version_code", Integer.toString(info.versionCode));
        } catch (final PackageManager.NameNotFoundException e) {
            OPLog.e(LOGTAG, "Exception getting app version name", e);
        }
        mDeviceInfo = Collections.unmodifiableMap(deviceInfo);

        mSessionMetadata = new SessionMetadata();
        mMessages = getAnalyticsMessages();
        mPersistentIdentity = getPersistentIdentity(context, referrerPreferences, token, instanceName);
        mEventTimings = mPersistentIdentity.getTimeEvents();

        if (optOutTrackingDefault && (hasOptedOutTracking() || !mPersistentIdentity.hasOptOutFlag(token))) {
            optOutTracking();
        }

        final boolean dbExists = OPDbAdapter.getInstance(mContext, mConfig).getDatabaseFile().exists();

        registerOursPrivacyActivityLifecycleCallbacks();

        if (mPersistentIdentity.isFirstLaunch(dbExists, mToken) && mTrackAutomaticEvents) {
            track(AutomaticEvents.FIRST_OPEN, null, true);
            mPersistentIdentity.setHasLaunched(mToken);
        }

        if (sendAppOpen() && mTrackAutomaticEvents) {
            track("$app_open", null);
        }

        if (mPersistentIdentity.isNewVersion(deviceInfo.get("$android_app_version_code")) && mTrackAutomaticEvents) {
            try {
                final JSONObject messageProps = new JSONObject();
                messageProps.put(AutomaticEvents.VERSION_UPDATED, deviceInfo.get("$android_app_version"));
                track(AutomaticEvents.APP_UPDATED, messageProps, true);
            } catch (JSONException e) {}
        }

        if (!mConfig.getDisableExceptionHandler()) {
            ExceptionHandler.init();
        }

        if (mConfig.getRemoveLegacyResidualFiles()) {
            mMessages.removeResidualImageFiles(new File(mContext.getApplicationInfo().dataDir));
        }

        // Event tracking integration w/ Session Replay SDK requires Android 13 or higher.
        // It is also NOT supported in "Instant" apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.getPackageManager().isInstantApp()) {
            BroadcastReceiver sessionReplayReceiver = new SessionReplayBroadcastReceiver(this);
            ContextCompat.registerReceiver(
                    mContext.getApplicationContext(),
                    sessionReplayReceiver,
                    SessionReplayBroadcastReceiver.INTENT_FILTER,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
        }
    }

    /**
     * Get the instance of OursPrivacyAPI associated with your OursPrivacy project token.
     *
     * <p>Use getInstance to get a reference to a shared
     * instance of OursPrivacyAPI you can use to send events
     * and People Analytics updates to OursPrivacy.</p>
     * <p>getInstance is thread safe, but the returned instance is not,
     * and may be shared with other callers of getInstance.
     * The best practice is to call getInstance, and use the returned OursPrivacyAPI,
     * object from a single thread (probably the main UI thread of your application).</p>
     * <p>If you do choose to track events from multiple threads in your application,
     * you should synchronize your calls on the instance itself, like so:</p>
     * <pre>
     * {@code
     * OursPrivacyAPI instance = OursPrivacyAPI.getInstance(context, token);
     * synchronized(instance) { // Only necessary if the instance will be used in multiple threads.
     *     instance.track(...)
     * }
     * }
     * </pre>
     *
     * @param context The application context you are tracking
     * @param token Your OursPrivacy project token. You can get your project token on the OursPrivacy web site,
     *     in the settings dialog.
     * @param trackAutomaticEvents Whether or not to collect common mobile events
     *                             include app sessions, first app opens, app updated, etc.
     * @return an instance of OursPrivacyAPI associated with your project
     */
    public static OursPrivacyAPI getInstance(Context context, String token, boolean trackAutomaticEvents) {
        if (null == token || null == context) {
            return null;
        }
        synchronized (sInstanceMap) {
            final Context appContext = context.getApplicationContext();

            if (null == sReferrerPrefs) {
                sReferrerPrefs = sPrefsLoader.loadPreferences(context, OPConfig.REFERRER_PREFS_NAME, null);
            }
            Map<Context, OursPrivacyAPI> instances = sInstanceMap.get(token);
            if (null == instances) {
                instances = new HashMap<Context, OursPrivacyAPI>();
                sInstanceMap.put(token, instances);
            }

            OursPrivacyAPI instance = instances.get(appContext);
            if (null == instance && ConfigurationChecker.checkBasicConfiguration(appContext)) {
                instance = new OursPrivacyAPI(appContext, sReferrerPrefs, token, false, null, null, trackAutomaticEvents);
                registerAppLinksListeners(context, instance);
                instances.put(appContext, instance);
            }

            checkIntentForInboundAppLink(context);

            return instance;
        }
    }

    /**
     * Controls whether to enable the run time debug logging
     *
     * @param enableLogging If true, emit more detailed log messages. Defaults to false
     */
    public void setEnableLogging(boolean enableLogging) {
        mConfig.setEnableLogging(enableLogging);
    }

    /**
     * Set maximum number of events/updates to send in a single network request
     *
     * @param flushBatchSize  int, the number of events to be flushed at a time, defaults to 50
     */
    public void setFlushBatchSize(int flushBatchSize) {
        mConfig.setFlushBatchSize(flushBatchSize);
    }

    /**
     * Get maximum number of events/updates to send in a single network request
     *
     * @return the integer number of events to be flushed at a time
     */
    public int getFlushBatchSize() {
        return mConfig.getFlushBatchSize();
    }

    /**
     * Set the base URL used for OursPrivacy API requests.
     * Useful if you need to proxy OursPrivacy requests. Defaults to https://api.oursprivacy.com/api/v1.
     * To route data to OursPrivacy's EU servers, set to https://api-eu.oursprivacy.com
     *
     * @param serverURL the base URL used for OursPrivacy API requests
     */
    public void setServerURL(String serverURL) {
        mConfig.setServerURL(serverURL);
    }

    /**
     * Set the base URL used for OursPrivacy API requests.
     * Useful if you need to proxy OursPrivacy requests. Defaults to https://api.oursprivacy.com/api/v1.
     * To route data to OursPrivacy's EU servers, set to https://api-eu.oursprivacy.com
     *
     * @param serverURL the base URL used for OursPrivacy API requests
     * @param callback the callback for oursprivacy proxy server api headers and status
     */
    public void setServerURL(String serverURL, ProxyServerInteractor callback) {
        mConfig.setServerURL(serverURL, callback);
    }

    public Boolean getTrackAutomaticEvents() { return mTrackAutomaticEvents; }

    /**
     * Associate all future calls to {@link #track(String, JSONObject)} with the user identified by
     * the given distinct id.
     *
     * <p>Calls to {@link #track(String, JSONObject)} made before corresponding calls to identify
     * will use an anonymous locally generated distinct id, which means it is best to call identify
     * early to ensure that your OursPrivacy funnels and retention analytics can continue to track the
     * user throughout their lifetime. We recommend calling identify when the user authenticates.
     *
     * <p>Once identify is called, the local distinct id persists across restarts of
     * your application.
     *
     * @param distinctId a string uniquely identifying this user. Events sent to
     *     OursPrivacy using the same disinct id will be considered associated with the
     *     same visitor/customer for retention and funnel reporting, so be sure that the given
     *     value is globally unique for each individual user you intend to track.
     *
     */
    public void identify(String distinctId, HashMap<String, Object> userProperties) {
        if (hasOptedOutTracking()) return;
        if (distinctId == null) {
            OPLog.e(LOGTAG, "Can't identify with null distinct_id.");
            return;
        }
        synchronized (mPersistentIdentity) {
            String currentEventsDistinctId = mPersistentIdentity.getEventsDistinctId();
            if (!distinctId.equals(currentEventsDistinctId)) {
                if (distinctId.startsWith("$device:")) {
                    OPLog.e(LOGTAG, "Can't identify with '$device:' distinct_id.");
                    return;
                }

                mPersistentIdentity.setEventsDistinctId(distinctId);
                mPersistentIdentity.setAnonymousIdIfAbsent(currentEventsDistinctId);
                mPersistentIdentity.markEventsUserIdPresent();
                try {
                    JSONObject identifyPayload = new JSONObject();
                    identifyPayload.put("$anon_distinct_id", currentEventsDistinctId);
                    track("$identify", identifyPayload);
                    getAnalyticsMessages().sendIdentify(currentEventsDistinctId, mToken, userProperties, mConfig.getIdentifyEndpoint());
                } catch (JSONException e) {
                    OPLog.e(LOGTAG, "Could not track $identify event");
                }
            }
        }
    }

    /**
     * Track an event.
     *
     * <p>Every call to track eventually results in a data point sent to OursPrivacy. These data points
     * are what are measured, counted, and broken down to create your OursPrivacy reports. Events
     * have a string name, and an optional set of name/value pairs that describe the properties of
     * that event.
     *
     * @param eventName The name of the event to send
     * @param properties A Map containing the key value pairs of the properties to include in this event.
     *                   Pass null if no extra properties exist.
     *
     * See also {@link #track(String, org.json.JSONObject)}
     */
    public void trackMap(String eventName, Map<String, Object> properties) {
        if (hasOptedOutTracking()) return;
        if (null == properties) {
            track(eventName, null);
        } else {
            try {
                track(eventName, new JSONObject(properties));
            } catch (NullPointerException e) {
                OPLog.w(LOGTAG, "Can't have null keys in the properties of trackMap!");
            }
        }
    }

    /**
     * Track an event.
     *
     * <p>Every call to track eventually results in a data point sent to OursPrivacy. These data points
     * are what are measured, counted, and broken down to create your OursPrivacy reports. Events
     * have a string name, and an optional set of name/value pairs that describe the properties of
     * that event.
     *
     * @param eventName The name of the event to send
     * @param properties A JSONObject containing the key value pairs of the properties to include in this event.
     *                   Pass null if no extra properties exist.
     */
    public void track(String eventName, JSONObject properties) {
        if (hasOptedOutTracking()) return;
        track(eventName, properties, false);
    }

    /**
     * Equivalent to {@link #track(String, JSONObject)} with a null argument for properties.
     * Consider adding properties to your tracking to get the best insights and experience from OursPrivacy.
     * @param eventName the name of the event to send
     */
    public void track(String eventName) {
        if (hasOptedOutTracking()) return;
        track(eventName, null);
    }

    /**
     * Push all queued OursPrivacy events and People Analytics changes to OursPrivacy servers.
     *
     * <p>Events and People messages are pushed gradually throughout
     * the lifetime of your application. This means that to ensure that all messages
     * are sent to OursPrivacy when your application is shut down, you will
     * need to call flush() to let the OursPrivacy library know it should
     * send all remaining messages to the server. We strongly recommend
     * placing a call to flush() in the onDestroy() method of
     * your main application activity.
     */
    public void flush() {
        if (hasOptedOutTracking()) return;
        mMessages.postToServer(new AnalyticsMessages.OursPrivacyDescription(mToken));
    }

    /**
     * Returns the string id currently being used to uniquely identify the user. Before any calls to
     * {@link #identify(String, HashMap)}, this will be an id automatically generated by the library.
     *
     *
     * @return The distinct id that uniquely identifies the current user.
     *
     * @see #identify(String, HashMap)
     */
    public String getDistinctId() {
        return mPersistentIdentity.getEventsDistinctId();
    }

    /**
     * Returns the user id with which identify is called  and all the with events sent using
     * {@link #track(String, JSONObject)} will have this id as a user id
     *
     * @return The user id associated with event tracking
     */
    protected String getUserId() {
        return mPersistentIdentity.getEventsUserId();
    }

    private String getAnonymousId() {
        return mPersistentIdentity.getAnonymousId();
    }

    /**
     * Clears tweaks and all distinct_ids, superProperties, and push registrations from persistent storage.
     * Will not clear referrer information.
     */
    public void reset() {
        // Will clear distinct_ids, superProperties,
        // and waiting People Analytics properties. Will have no effect
        // on messages already queued to send with AnalyticsMessages.
        mPersistentIdentity.clearPreferences();
        identify(getDistinctId(), null);
        flush();
    }

    /**
     * Returns an unmodifiable map that contains the device description properties
     * that will be sent to OursPrivacy. These are not all of the default properties,
     * but are a subset that are dependant on the user's device or installed version
     * of the host application, and are guaranteed not to change while the app is running.
     *
     * @return Map containing the device description properties that are sent to OursPrivacy.
     */
    public Map<String, String> getDeviceInfo() {
        return mDeviceInfo;
    }

    /**
     * Use this method to opt-out a user from tracking. Events and people updates that haven't been
     * flushed yet will be deleted. Use {@link #flush()} before calling this method if you want
     * to send all the queues to OursPrivacy before.
     *
     * This method will also remove any user-related information from the device.
     */
    public void optOutTracking() {
        getAnalyticsMessages().emptyTrackingQueues(new AnalyticsMessages.OursPrivacyDescription(mToken));
        mPersistentIdentity.clearPreferences();
        synchronized (mEventTimings) {
            mEventTimings.clear();
            mPersistentIdentity.clearTimedEvents();
        }
        mPersistentIdentity.clearReferrerProperties();
        mPersistentIdentity.setOptOutTracking(true, mToken);
    }

    /**
     * Use this method to opt-in an already opted-out user from tracking. People updates and track
     * calls will be sent to OursPrivacy after using this method.
     * This method will internally track an opt-in event to your project. If you want to identify
     * the opt-in event and/or pass properties to the event, see {@link #optInTracking(String)} and
     * {@link #optInTracking(String, JSONObject)}
     *
     * See also {@link #optOutTracking()}.
     */
    public void optInTracking() {
        optInTracking(null, null);
    }

    /**
     * Use this method to opt-in an already opted-out user from tracking. People updates and track
     * calls will be sent to OursPrivacy after using this method.
     * This method will internally track an opt-in event to your project.
     *
     * @param distinctId Optional string to use as the distinct ID for events.
     *                   This will call {@link #identify(String, HashMap)}.
     *
     * See also {@link #optInTracking(String)}, {@link #optInTracking(String, JSONObject)} and
     *  {@link #optOutTracking()}.
     */
    public void optInTracking(String distinctId) {
        optInTracking(distinctId, null);
    }

    /**
     * Use this method to opt-in an already opted-out user from tracking. People updates and track
     * calls will be sent to OursPrivacy after using this method.
     * This method will internally track an opt-in event to your project.
     *
     * @param distinctId Optional string to use as the distinct ID for events.
     *                   This will call {@link #identify(String, HashMap)}.
     *
     * @param properties Optional JSONObject that could be passed to add properties to the
     *                   opt-in event that is sent to OursPrivacy.
     *
     * See also {@link #optInTracking()} and {@link #optOutTracking()}.
     */
    public void optInTracking(String distinctId, JSONObject properties) {
        mPersistentIdentity.setOptOutTracking(false, mToken);
        if (distinctId != null) {
            identify(distinctId, null);
        }
        track("$opt_in", properties);
    }
    /**
     * Will return true if the user has opted out from tracking. See {@link #optOutTracking()} and
     * {@link OursPrivacyAPI#getInstance(Context, String, boolean)} for more information.
     *
     * @return true if user has opted out from tracking. Defaults to false.
     */
    public boolean hasOptedOutTracking() {
        return mPersistentIdentity.getOptOutTracking(mToken);
    }

    /**
     * Attempt to register OursPrivacyActivityLifecycleCallbacks to the application's event lifecycle.
     * Once registered, we can automatically flush on an app background.
     *
     * This is only available if the android version is >= 16.
     *
     * This function is automatically called when the library is initialized unless you explicitly
     * set com.oursprivacy.android.Config.AutoShowOursPrivacyUpdates to false in your AndroidManifest.xml
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    /* package */ void registerOursPrivacyActivityLifecycleCallbacks() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            if (mContext.getApplicationContext() instanceof Application) {
                final Application app = (Application) mContext.getApplicationContext();
                mOursPrivacyActivityLifecycleCallbacks = new OursPrivacyActivityLifecycleCallbacks(this, mConfig);
                app.registerActivityLifecycleCallbacks(mOursPrivacyActivityLifecycleCallbacks);
            } else {
                OPLog.i(LOGTAG, "Context is not an Application, OursPrivacy won't be able to automatically flush on an app background.");
            }
        }
    }

    /**
     * Based on the application's event lifecycle this method will determine whether the app
     * is running in the foreground or not.
     *
     * If your build version is below 14 this method will always return false.
     *
     * @return True if the app is running in the foreground.
     */
    public boolean isAppInForeground() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            if (mOursPrivacyActivityLifecycleCallbacks != null) {
                return mOursPrivacyActivityLifecycleCallbacks.isInForeground();
            }
        } else {
            OPLog.e(LOGTAG, "Your build version is below 14. This method will always return false.");
        }
        return false;
    }

    /* package */ void onBackground() {
        if (mConfig.getFlushOnBackground()) {
            flush();
        }
    }

    /* package */ void onForeground() {
        mSessionMetadata.initSession();
    }

    // Package-level access. Used (at least) by OursPrivacyFCMMessagingService
    // when OS-level events occur.
    /* package */ interface InstanceProcessor {
        void process(OursPrivacyAPI m);
    }

    /* package */ static void allInstances(InstanceProcessor processor) {
        synchronized (sInstanceMap) {
            for (final Map<Context, OursPrivacyAPI> contextInstances : sInstanceMap.values()) {
                for (final OursPrivacyAPI instance : contextInstances.values()) {
                    processor.process(instance);
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////
    // Conveniences for testing. These methods should not be called by
    // non-test client code.

    /* package */ AnalyticsMessages getAnalyticsMessages() {
        return AnalyticsMessages.getInstance(mContext, mConfig);
    }


    /* package */ PersistentIdentity getPersistentIdentity(final Context context, Future<SharedPreferences> referrerPreferences, final String token) {
        return getPersistentIdentity(context, referrerPreferences, token, null);
    }

    /* package */ PersistentIdentity getPersistentIdentity(final Context context, Future<SharedPreferences> referrerPreferences, final String token, final String instanceName) {
        String instanceKey = instanceName != null ? instanceName : token;
        final String prefsName = "com.oursprivacy.android.opmetrics.OursPrivacyAPI_" + instanceKey;
        final Future<SharedPreferences> storedPreferences = sPrefsLoader.loadPreferences(context, prefsName, null);

        final String timeEventsPrefsName = "com.oursprivacy.android.opmetrics.OursPrivacyAPI.TimeEvents_" + instanceKey;
        final Future<SharedPreferences> timeEventsPrefs = sPrefsLoader.loadPreferences(context, timeEventsPrefsName, null);

        final String oursprivacyPrefsName = "com.oursprivacy.android.opmetrics.OursPrivacy";
        final Future<SharedPreferences> oursprivacyPrefs = sPrefsLoader.loadPreferences(context, oursprivacyPrefsName, null);

        return new PersistentIdentity(referrerPreferences, storedPreferences, timeEventsPrefs, oursprivacyPrefs);
    }

    /* package */ boolean sendAppOpen() {
        return !mConfig.getDisableAppOpenEvent();
    }

    ///////////////////////

    protected void track(String eventName, JSONObject properties, boolean isAutomaticEvent) {
        if (hasOptedOutTracking() || (isAutomaticEvent && !mTrackAutomaticEvents)) {
            return;
        }

        final Long eventBegin;
        synchronized (mEventTimings) {
            eventBegin = mEventTimings.get(eventName);
            mEventTimings.remove(eventName);
            mPersistentIdentity.removeTimedEvent(eventName);
        }

        try {
            final JSONObject messageProps = new JSONObject();

            final Map<String, String> referrerProperties = mPersistentIdentity.getReferrerProperties();
            for (final Map.Entry<String, String> entry : referrerProperties.entrySet()) {
                final String key = entry.getKey();
                final String value = entry.getValue();
                messageProps.put(key, value);
            }

            mPersistentIdentity.addSuperPropertiesToObject(messageProps);

            // Don't allow super properties or referral properties to override these fields,
            // but DO allow the caller to override them in their given properties.
            final double timeSecondsDouble = (System.currentTimeMillis()) / 1000.0;
            final String distinctId = getDistinctId();
            final String anonymousId = getAnonymousId();
            final String userId = getUserId();
            messageProps.put("time", System.currentTimeMillis());
            messageProps.put("distinct_id", distinctId);
            messageProps.put("$had_persisted_distinct_id", mPersistentIdentity.getHadPersistedDistinctId());
            if(anonymousId != null) {
                messageProps.put("$device_id", anonymousId);
            }
            if(userId != null) {
                messageProps.put("$user_id", userId);
            }

            if (null != eventBegin) {
                final double eventBeginDouble = ((double) eventBegin) / 1000.0;
                final double secondsElapsed = timeSecondsDouble - eventBeginDouble;
                messageProps.put("$duration", secondsElapsed);
            }

            if (null != properties) {
                final Iterator<?> propIter = properties.keys();
                while (propIter.hasNext()) {
                    final String key = (String) propIter.next();
                    messageProps.put(key, properties.opt(key));
                }
            }

            final AnalyticsMessages.EventDescription eventDescription =
                    new AnalyticsMessages.EventDescription(eventName, messageProps,
                            mToken, isAutomaticEvent, mSessionMetadata.getMetadataForEvent());
            mMessages.eventsMessage(eventDescription);
        } catch (final JSONException e) {
            OPLog.e(LOGTAG, "Exception tracking event " + eventName, e);
        }
    }

    private static void registerAppLinksListeners(Context context, final OursPrivacyAPI oursprivacy) {
        // Register a BroadcastReceiver to receive com.parse.bolts.measurement_event and track a call to oursprivacy
        try {
            final Class<?> clazz = Class.forName("androidx.localbroadcastmanager.content.LocalBroadcastManager");
            final Method methodGetInstance = clazz.getMethod("getInstance", Context.class);
            final Method methodRegisterReceiver = clazz.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class);
            final Object localBroadcastManager = methodGetInstance.invoke(null, context);
            methodRegisterReceiver.invoke(localBroadcastManager, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final JSONObject properties = new JSONObject();
                    final Bundle args = intent.getBundleExtra("event_args");
                    if (args != null) {
                        for (final String key : args.keySet()) {
                            try {
                                properties.put(key, args.get(key));
                            } catch (final JSONException e) {
                                OPLog.e(APP_LINKS_LOGTAG, "failed to add key \"" + key + "\" to properties for tracking bolts event", e);
                            }
                        }
                    }
                    oursprivacy.track("$" + intent.getStringExtra("event_name"), properties);
                }
            }, new IntentFilter("com.parse.bolts.measurement_event"));
        } catch (final InvocationTargetException e) {
            OPLog.d(APP_LINKS_LOGTAG, "Failed to invoke LocalBroadcastManager.registerReceiver() -- App Links tracking will not be enabled due to this exception", e);
        } catch (final ClassNotFoundException e) {
            OPLog.d(APP_LINKS_LOGTAG, "To enable App Links tracking, add implementation 'androidx.localbroadcastmanager:localbroadcastmanager:1.0.0': " + e.getMessage());
        } catch (final NoSuchMethodException e) {
            OPLog.d(APP_LINKS_LOGTAG, "To enable App Links tracking, add implementation 'androidx.localbroadcastmanager:localbroadcastmanager:1.0.0': " + e.getMessage());
        } catch (final IllegalAccessException e) {
            OPLog.d(APP_LINKS_LOGTAG, "App Links tracking will not be enabled due to this exception: " + e.getMessage());
        }
    }

    private static void checkIntentForInboundAppLink(Context context) {
        // call the Bolts getTargetUrlFromInboundIntent method simply for a side effect
        // if the intent is the result of an App Link, it'll trigger al_nav_in
        // https://github.com/BoltsFramework/Bolts-Android/blob/1.1.2/Bolts/src/bolts/AppLinks.java#L86
        if (context instanceof Activity) {
            try {
                final Class<?> clazz = Class.forName("bolts.AppLinks");
                final Intent intent = ((Activity) context).getIntent();
                final Method getTargetUrlFromInboundIntent = clazz.getMethod("getTargetUrlFromInboundIntent", Context.class, Intent.class);
                getTargetUrlFromInboundIntent.invoke(null, context, intent);
            } catch (final InvocationTargetException e) {
                OPLog.d(APP_LINKS_LOGTAG, "Failed to invoke bolts.AppLinks.getTargetUrlFromInboundIntent() -- Unable to detect inbound App Links", e);
            } catch (final ClassNotFoundException e) {
                OPLog.d(APP_LINKS_LOGTAG, "Please install the Bolts library >= 1.1.2 to track App Links: " + e.getMessage());
            } catch (final NoSuchMethodException e) {
                OPLog.d(APP_LINKS_LOGTAG, "Please install the Bolts library >= 1.1.2 to track App Links: " + e.getMessage());
            } catch (final IllegalAccessException e) {
                OPLog.d(APP_LINKS_LOGTAG, "Unable to detect inbound App Links: " + e.getMessage());
            }
        } else {
            OPLog.d(APP_LINKS_LOGTAG, "Context is not an instance of Activity. To detect inbound App Links, pass an instance of an Activity to getInstance.");
        }
    }

    /* package */ Context getContext() {
        return mContext;
    }

    private final Context mContext;
    private final AnalyticsMessages mMessages;
    private final OPConfig mConfig;
    private final Boolean mTrackAutomaticEvents;
    private final String mToken;
    private final String mInstanceName;
    private final PersistentIdentity mPersistentIdentity;
    private final Map<String, String> mDeviceInfo;
    private final Map<String, Long> mEventTimings;
    private OursPrivacyActivityLifecycleCallbacks mOursPrivacyActivityLifecycleCallbacks;
    private final SessionMetadata mSessionMetadata;

    // Maps each token to a singleton OursPrivacyAPI instance
    private static final Map<String, Map<Context, OursPrivacyAPI>> sInstanceMap = new HashMap<String, Map<Context, OursPrivacyAPI>>();
    private static final SharedPreferencesLoader sPrefsLoader = new SharedPreferencesLoader();
    private static Future<SharedPreferences> sReferrerPrefs;

    private static final String LOGTAG = "OursPrivacyAPI.API";
    private static final String APP_LINKS_LOGTAG = "OursPrivacyAPI.AL";
    private static final String ENGAGE_DATE_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ss";
}
