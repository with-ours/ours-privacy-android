package com.oursprivacy.android.opmetrics;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.DisplayMetrics;

import com.oursprivacy.android.util.Base64Coder;
import com.oursprivacy.android.util.HttpService;
import com.oursprivacy.android.util.LegacyVersionUtils;
import com.oursprivacy.android.util.OPLog;
import com.oursprivacy.android.util.RemoteService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.net.ssl.SSLSocketFactory;

/**
 * Manage communication of events with the internal database and the OursPrivacy servers.
 *
 * <p>This class straddles the thread boundary between user threads and
 * a logical OursPrivacy thread.
 */
/* package */ class AnalyticsMessages {

    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /* package */ AnalyticsMessages(final Context context, OPConfig config) {
        mContext = context;
        mConfig = config;
        mInstanceName = config.getInstanceName();
        mWorker = createWorker();
        getPoster().checkIsOursPrivacyBlocked();
    }

    protected Worker createWorker() {
        return new Worker();
    }

    /**
     * Use this to get an instance of AnalyticsMessages instead of creating one directly
     * for yourself.
     *
     * @param messageContext should be the Main Activity of the application
     *     associated with these messages.
     *
     * @param config The MPConfig configuration settings for the AnalyticsMessages instance.
     *
     */
    public static AnalyticsMessages getInstance(final Context messageContext, OPConfig config) {
        synchronized (sInstances) {
            final Context appContext = messageContext.getApplicationContext();
            AnalyticsMessages ret;
            String instanceName = config.getInstanceName();
            if (!sInstances.containsKey(instanceName)) {
                ret = new AnalyticsMessages(appContext, config);
                sInstances.put(instanceName, ret);
            } else {
                ret = sInstances.get(instanceName);
            }
            return ret;
        }
    }

    public void eventsMessage(final EventDescription eventDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = eventDescription;
        mWorker.runMessage(m);
    }

    public void postToServer(final OursPrivacyDescription flushDescription) {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;
        m.obj = flushDescription.getToken();
        m.arg1 = 0;

        mWorker.runMessage(m);
    }

    public void emptyTrackingQueues(final OursPrivacyDescription oursprivacyDescription) {
        final Message m = Message.obtain();
        m.what = EMPTY_QUEUES;
        m.obj = oursprivacyDescription;

        mWorker.runMessage(m);
    }

    public void updateEventProperties(final UpdateEventsPropertiesDescription updateEventsProperties) {
        final Message m = Message.obtain();
        m.what = REWRITE_EVENT_PROPERTIES;
        m.obj = updateEventsProperties;

        mWorker.runMessage(m);
    }

    public void removeResidualImageFiles(File fileOrDirectory) {
        final Message m = Message.obtain();
        m.what = REMOVE_RESIDUAL_IMAGE_FILES;
        m.obj = fileOrDirectory;
        mWorker.runMessage(m);
    }

    public void hardKill() {
        final Message m = Message.obtain();
        m.what = KILL_WORKER;

        mWorker.runMessage(m);
    }

    /////////////////////////////////////////////////////////
    // For testing, to allow for Mocking.

    /* package */ boolean isDead() {
        return mWorker.isDead();
    }

    protected OPDbAdapter makeDbAdapter(Context context) {
        return OPDbAdapter.getInstance(context, mConfig);
    }

    protected RemoteService getPoster() {
        return new HttpService(mConfig.shouldGzipRequestPayload());
    }

    ////////////////////////////////////////////////////

    static class EventDescription extends OursPrivacyMessageDescription {
        public EventDescription(String eventName,
                                JSONObject properties,
                                String token) {
            this(eventName, properties, token, false, new JSONObject());
        }

        public EventDescription(String eventName,
                                JSONObject properties,
                                String token,
                                boolean isAutomatic,
                                JSONObject sessionMetada) {
            super(token, properties);
            mEventName = eventName;
            mIsAutomatic = isAutomatic;
            mSessionMetadata = sessionMetada;
        }

        public String getEventName() {
            return mEventName;
        }

        public JSONObject getProperties() {
            return getMessage();
        }

        public JSONObject getSessionMetadata() {
            return mSessionMetadata;
        }

        public boolean isAutomatic() {
            return mIsAutomatic;
        }

        private final String mEventName;
        private final JSONObject mSessionMetadata;
        private final boolean mIsAutomatic;
    }

    static class PeopleDescription extends OursPrivacyMessageDescription {
        public PeopleDescription(JSONObject message, String token) {
            super(token, message);
        }

        @Override
        public String toString() {
            return getMessage().toString();
        }

        public boolean isAnonymous() {
            return !getMessage().has("$distinct_id");
        }
    }

    static class OursPrivacyMessageDescription extends OursPrivacyDescription {
        public OursPrivacyMessageDescription(String token, JSONObject message) {
            super(token);
            if (message != null && message.length() > 0) {
                Iterator<String> it = message.keys();
                while (it.hasNext()) {
                    String jsonKey = it.next();
                    try {
                        message.get(jsonKey).toString();
                    } catch (AssertionError e) {
                        // see https://github.com/oursprivacy/oursprivacy-android/issues/567
                        message.remove(jsonKey);
                        OPLog.e(LOGTAG, "Removing people profile property from update (see https://github.com/oursprivacy/oursprivacy-android/issues/567)", e);
                    } catch (JSONException e) {}
                }
            }
            this.mMessage = message;
        }

        public JSONObject getMessage() {
            return mMessage;
        }

        private final JSONObject mMessage;
    }


    static class UpdateEventsPropertiesDescription extends OursPrivacyDescription {
        private final Map<String, String> mProps;

        public UpdateEventsPropertiesDescription(String token, Map<String, String> props) {
            super(token);
            mProps = props;
        }

        public Map<String, String> getProperties() {
            return mProps;
        }
    }

    static class OursPrivacyDescription {
        public OursPrivacyDescription(String token) {
            this.mToken = token;
        }

        public String getToken() {
            return mToken;
        }

        private final String mToken;
    }


    public void sendIdentify(String distinctId, String token, HashMap<String, Object> userProperties, String url) {
        final RemoteService poster = getPoster();

        final Map<String, Object> params = new HashMap<String, Object>();
        params.put("userId", distinctId);
        params.put("token", token);
        params.put("userProperties", userProperties);
        if (OPConfig.DEBUG) {
            params.put("verbose", "1");
        }

        byte[] response;
        try {
            final SSLSocketFactory socketFactory = mConfig.getSSLSocketFactory();
            response = poster.performRequest(url, mConfig.getProxyServerInteractor(), null, new JSONObject(params).toString(), socketFactory);
            if (null == response) {
                logAboutMessageToOursPrivacy("Response was null, unexpected failure posting to " + url + ".");
            } else {
                String parsedResponse;
                try {
                    parsedResponse = new String(response, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("UTF not supported on this platform?", e);
                }

                logAboutMessageToOursPrivacy("Successfully posted to " + url + ": \n" + distinctId);
                logAboutMessageToOursPrivacy("Response was " + parsedResponse);
            }
        } catch (final OutOfMemoryError e) {
            OPLog.e(LOGTAG, "Out of memory when posting to " + url + ".", e);
        } catch (final MalformedURLException e) {
            OPLog.e(LOGTAG, "Cannot interpret " + url + " as a URL.", e);
        } catch (final RemoteService.ServiceUnavailableException e) {
            logAboutMessageToOursPrivacy("Cannot post message to " + url + ".", e);
        } catch (final SocketTimeoutException e) {
            logAboutMessageToOursPrivacy("Cannot post message to " + url + ".", e);
        } catch (final IOException e) {
            logAboutMessageToOursPrivacy("Cannot post message to " + url + ".", e);
        }
    }

    // Sends a message if and only if we are running with OursPrivacy Message log enabled.
    // Will be called from the OursPrivacy thread.
    private void logAboutMessageToOursPrivacy(String message) {
        OPLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")");
    }

    private void logAboutMessageToOursPrivacy(String message, Throwable e) {
        OPLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")", e);
    }

    // Worker will manage the (at most single) IO thread associated with
    // this AnalyticsMessages instance.
    // XXX: Worker class is unnecessary, should be just a subclass of HandlerThread
    class Worker {
        public Worker() {
            mHandler = restartWorkerThread();
        }

        public boolean isDead() {
            synchronized(mHandlerLock) {
                return mHandler == null;
            }
        }

        public void runMessage(Message msg) {
            synchronized(mHandlerLock) {
                if (mHandler == null) {
                    // We died under suspicious circumstances. Don't try to send any more events.
                    logAboutMessageToOursPrivacy("Dead oursprivacy worker dropping a message: " + msg.what);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        // NOTE that the returned worker will run FOREVER, unless you send a hard kill
        // (which you really shouldn't)
        protected Handler restartWorkerThread() {
            final HandlerThread thread = new HandlerThread("com.oursprivacy.android.AnalyticsWorker", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            return new AnalyticsMessageHandler(thread.getLooper());
        }

        class AnalyticsMessageHandler extends Handler {
            public AnalyticsMessageHandler(Looper looper) {
                super(looper);
                mDbAdapter = null;
                mSystemInformation = SystemInformation.getInstance(mContext);
                mFlushInterval = mConfig.getFlushInterval();
            }

            @Override
            public void handleMessage(Message msg) {
                if (mDbAdapter == null) {
                    mDbAdapter = makeDbAdapter(mContext);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), OPDbAdapter.Table.EVENTS);
                }

                try {
                    int returnCode = OPDbAdapter.DB_UNDEFINED_CODE;
                    String token = null;

                    if (msg.what == ENQUEUE_EVENTS) {
                        final EventDescription eventDescription = (EventDescription) msg.obj;
                        try {
                            final JSONObject message = prepareEventObject(eventDescription);
                            logAboutMessageToOursPrivacy("Queuing event for sending later");
                            logAboutMessageToOursPrivacy("    " + message.toString());
                            token = eventDescription.getToken();
                            returnCode = mDbAdapter.addJSON(message, token, OPDbAdapter.Table.EVENTS);
                        } catch (final JSONException e) {
                            OPLog.e(LOGTAG, "Exception tracking event " + eventDescription.getEventName(), e);
                        }
                    } else if (msg.what == REWRITE_EVENT_PROPERTIES) {
                        final UpdateEventsPropertiesDescription description = (UpdateEventsPropertiesDescription) msg.obj;
                        int updatedEvents = mDbAdapter.rewriteEventDataWithProperties(description.getProperties(), description.getToken());
                        OPLog.d(LOGTAG, updatedEvents + " stored events were updated with new properties.");
                    } else if (msg.what == FLUSH_QUEUE) {
                        logAboutMessageToOursPrivacy("Flushing queue due to scheduled or forced flush");
                        updateFlushFrequency();
                        token = (String) msg.obj;
                        sendAllData(mDbAdapter, token);
                    } else if (msg.what == EMPTY_QUEUES) {
                        final OursPrivacyDescription message = (OursPrivacyDescription) msg.obj;
                        token = message.getToken();
                        mDbAdapter.cleanupAllEvents(OPDbAdapter.Table.EVENTS, token);
                    } else if (msg.what == KILL_WORKER) {
                        OPLog.w(LOGTAG, "Worker received a hard kill. Dumping all events and force-killing. Thread id " + Thread.currentThread().getId());
                        synchronized(mHandlerLock) {
                            mDbAdapter.deleteDB();
                            mHandler = null;
                            Looper.myLooper().quit();
                        }
                    } else if (msg.what == REMOVE_RESIDUAL_IMAGE_FILES) {
                        final File file = (File) msg.obj;
                        LegacyVersionUtils.removeLegacyResidualImageFiles(file);
                    } else {
                        OPLog.e(LOGTAG, "Unexpected message received by OursPrivacy worker: " + msg);
                    }

                    ///////////////////////////
                    if ((returnCode >= mConfig.getBulkUploadLimit() || returnCode == OPDbAdapter.DB_OUT_OF_MEMORY_ERROR) && mFailedRetries <= 0 && token != null) {
                        logAboutMessageToOursPrivacy("Flushing queue due to bulk upload limit (" + returnCode + ") for project " + token);
                        updateFlushFrequency();
                        sendAllData(mDbAdapter, token);
                    } else if (returnCode > 0 && !hasMessages(FLUSH_QUEUE, token)) {
                        // The !hasMessages(FLUSH_QUEUE, token) check is a courtesy for the common case
                        // of delayed flushes already enqueued from inside of this thread.
                        // Callers outside of this thread can still send
                        // a flush right here, so we may end up with two flushes
                        // in our queue, but we're OK with that.

                        logAboutMessageToOursPrivacy("Queue depth " + returnCode + " - Adding flush in " + mFlushInterval);
                        if (mFlushInterval >= 0) {
                            final Message flushMessage = Message.obtain();
                            flushMessage.what = FLUSH_QUEUE;
                            flushMessage.obj = token;
                            flushMessage.arg1 = 1;
                            sendMessageDelayed(flushMessage, mFlushInterval);
                        }
                    }
                } catch (final RuntimeException e) {
                    OPLog.e(LOGTAG, "Worker threw an unhandled exception", e);
                    synchronized (mHandlerLock) {
                        mHandler = null;
                        try {
                            Looper.myLooper().quit();
                            OPLog.e(LOGTAG, "OursPrivacy will not process any more analytics messages", e);
                        } catch (final Exception tooLate) {
                            OPLog.e(LOGTAG, "Could not halt looper", tooLate);
                        }
                    }
                } catch (JSONException e) {
                    OPLog.e(LOGTAG, "Worker threw an unhandled exception", e);
                }
            }// handleMessage

            protected long getTrackEngageRetryAfter() {
                return mTrackEngageRetryAfter;
            }

            private void sendAllData(OPDbAdapter dbAdapter, String token) throws JSONException {
                final RemoteService poster = getPoster();
                if (!poster.isOnline(mContext, mConfig.getOfflineMode())) {
                    logAboutMessageToOursPrivacy("Not flushing data to OursPrivacy because the device is not connected to the internet.");
                    return;
                }

                sendData(dbAdapter, token, OPDbAdapter.Table.EVENTS, mConfig.getEventsEndpoint());
            }

            private void sendData(OPDbAdapter dbAdapter, String token, OPDbAdapter.Table table, String url) throws JSONException {
                final RemoteService poster = getPoster();
                ArrayList<JSONObject> eventsData = dbAdapter.generateDataString(table, token);

                final String lastId = eventsData.get(eventsData.size() - 1).getString("_id");
                for (JSONObject item : eventsData) {
                    final String rawMessage = item.toString();
                    final String encodedData = Base64Coder.encodeString(rawMessage);
                    final Map<String, Object> params = new HashMap<>();
                    params.put("data", encodedData);
                    if (OPConfig.DEBUG) {
                        params.put("verbose", "1");
                    }

                    boolean deleteEvents = true;
                    byte[] response;
                    try {
                        final SSLSocketFactory socketFactory = mConfig.getSSLSocketFactory();
                        response = poster.performRequest(url, mConfig.getProxyServerInteractor(), params, rawMessage, socketFactory);
                        if (null == response) {
                            deleteEvents = false;
                            logAboutMessageToOursPrivacy("Response was null, unexpected failure posting to " + url + ".");
                        } else {
                            deleteEvents = true; // Delete events on any successful post, regardless of 1 or 0 response
                            String parsedResponse;
                            try {
                                parsedResponse = new String(response, "UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                throw new RuntimeException("UTF not supported on this platform?", e);
                            }
                            if (mFailedRetries > 0) {
                                mFailedRetries = 0;
                                removeMessages(FLUSH_QUEUE, token);
                            }

                            logAboutMessageToOursPrivacy("Successfully posted to " + url + ": \n" + rawMessage);
                            logAboutMessageToOursPrivacy("Response was " + parsedResponse);
                        }
                    } catch (final OutOfMemoryError e) {
                        OPLog.e(LOGTAG, "Out of memory when posting to " + url + ".", e);
                    } catch (final MalformedURLException e) {
                        OPLog.e(LOGTAG, "Cannot interpret " + url + " as a URL.", e);
                    } catch (final RemoteService.ServiceUnavailableException e) {
                        logAboutMessageToOursPrivacy("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                        mTrackEngageRetryAfter = e.getRetryAfter() * 1000;
                    } catch (final SocketTimeoutException e) {
                        logAboutMessageToOursPrivacy("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                    } catch (final IOException e) {
                        logAboutMessageToOursPrivacy("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                    }

                    if (deleteEvents) {
                        logAboutMessageToOursPrivacy("Not retrying this batch of events, deleting them from DB.");
                        dbAdapter.cleanupEvents(lastId, table, token);
                    } else {
                        removeMessages(FLUSH_QUEUE, token);
                        mTrackEngageRetryAfter = Math.max((long)Math.pow(2, mFailedRetries) * 60000, mTrackEngageRetryAfter);
                        mTrackEngageRetryAfter = Math.min(mTrackEngageRetryAfter, 10 * 60 * 1000); // limit 10 min
                        final Message flushMessage = Message.obtain();
                        flushMessage.what = FLUSH_QUEUE;
                        flushMessage.obj = token;
                        sendMessageDelayed(flushMessage, mTrackEngageRetryAfter);
                        mFailedRetries++;
                        logAboutMessageToOursPrivacy("Retrying this batch of events in " + mTrackEngageRetryAfter + " ms");
                        break;
                    }
                }
            }

            private JSONObject getDefaultEventProperties()
                    throws JSONException {
                final JSONObject ret = new JSONObject();

                ret.put("mp_lib", "android");
                ret.put("$lib_version", OPConfig.VERSION);

                // For querying together with data from other libraries
                ret.put("$os", "Android");
                ret.put("$os_version", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);

                ret.put("$manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
                ret.put("$brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
                ret.put("$model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);

                final DisplayMetrics displayMetrics = mSystemInformation.getDisplayMetrics();
                ret.put("$screen_dpi", displayMetrics.densityDpi);
                ret.put("$screen_height", displayMetrics.heightPixels);
                ret.put("$screen_width", displayMetrics.widthPixels);

                final String applicationVersionName = mSystemInformation.getAppVersionName();
                if (null != applicationVersionName) {
                    ret.put("$app_version", applicationVersionName);
                    ret.put("$app_version_string", applicationVersionName);
                }

                 final Integer applicationVersionCode = mSystemInformation.getAppVersionCode();
                 if (null != applicationVersionCode) {
                    final String applicationVersion = String.valueOf(applicationVersionCode);
                    ret.put("$app_release", applicationVersion);
                    ret.put("$app_build_number", applicationVersion);
                }

                final Boolean hasNFC = mSystemInformation.hasNFC();
                if (null != hasNFC)
                    ret.put("$has_nfc", hasNFC.booleanValue());

                final Boolean hasTelephony = mSystemInformation.hasTelephony();
                if (null != hasTelephony)
                    ret.put("$has_telephone", hasTelephony.booleanValue());

                final String carrier = mSystemInformation.getCurrentNetworkOperator();
                if (null != carrier && !carrier.trim().isEmpty())
                    ret.put("$carrier", carrier);

                final Boolean isWifi = mSystemInformation.isWifiConnected();
                if (null != isWifi)
                    ret.put("$wifi", isWifi.booleanValue());

                final Boolean isBluetoothEnabled = mSystemInformation.isBluetoothEnabled();
                if (isBluetoothEnabled != null)
                    ret.put("$bluetooth_enabled", isBluetoothEnabled);

                final String bluetoothVersion = mSystemInformation.getBluetoothVersion();
                if (bluetoothVersion != null)
                    ret.put("$bluetooth_version", bluetoothVersion);

                return ret;
            }

            private JSONObject prepareEventObject(EventDescription eventDescription) throws JSONException {
                final JSONObject eventObj = new JSONObject();
                final JSONObject eventProperties = eventDescription.getProperties();
                final JSONObject sendProperties = getDefaultEventProperties();
                eventObj.put("token", eventDescription.getToken());
                sendProperties.put("token", eventDescription.getToken());
                if (eventProperties != null) {
                    for (final Iterator<?> iter = eventProperties.keys(); iter.hasNext();) {
                        final String key = (String) iter.next();
                        sendProperties.put(key, eventProperties.get(key));
                    }
                    if (sendProperties.has("$device_id")) {
                        eventObj.put("userId", sendProperties.get("$device_id"));
                    }
                }
                eventObj.put("event", eventDescription.getEventName());
                eventObj.put("eventProperties", sendProperties);
                sendProperties.put("time", System.currentTimeMillis());
                return eventObj;
            }

            private OPDbAdapter mDbAdapter;
            private final long mFlushInterval;
            private long mTrackEngageRetryAfter;
            private int mFailedRetries;
        }// AnalyticsMessageHandler

        private void updateFlushFrequency() {
            final long now = System.currentTimeMillis();
            final long newFlushCount = mFlushCount + 1;

            if (mLastFlushTime > 0) {
                final long flushInterval = now - mLastFlushTime;
                final long totalFlushTime = flushInterval + (mAveFlushFrequency * mFlushCount);
                mAveFlushFrequency = totalFlushTime / newFlushCount;

                final long seconds = mAveFlushFrequency / 1000;
                logAboutMessageToOursPrivacy("Average send frequency approximately " + seconds + " seconds.");
            }

            mLastFlushTime = now;
            mFlushCount = newFlushCount;
        }

        private final Object mHandlerLock = new Object();
        private Handler mHandler;
        private long mFlushCount = 0;
        private long mAveFlushFrequency = 0;
        private long mLastFlushTime = -1;
        private SystemInformation mSystemInformation;
    }

    public long getTrackEngageRetryAfter() {
        return ((Worker.AnalyticsMessageHandler) mWorker.mHandler).getTrackEngageRetryAfter();
    }
    /////////////////////////////////////////////////////////

    // Used across thread boundaries
    private final Worker mWorker;
    private final String mInstanceName;
    protected final Context mContext;
    protected final OPConfig mConfig;

    // Messages for our thread
    private static final int ENQUEUE_EVENTS = 1; // push given JSON message to events DB
    private static final int FLUSH_QUEUE = 2; // submit events, people, and groups data
    private static final int KILL_WORKER = 5; // Hard-kill the worker thread, discarding all events on the event queue. This is for testing, or disasters.
    private static final int EMPTY_QUEUES = 6; // Remove any local (and pending to be flushed) events or people/group updates from the db
    private static final int REWRITE_EVENT_PROPERTIES = 8; // Update or add properties to existing queued events
    private static final int REMOVE_RESIDUAL_IMAGE_FILES = 9; // Remove residual image files left from the legacy SDK versions

    private static final String LOGTAG = "OursPrivacyAPI.Messages";

    private static final Map<String, AnalyticsMessages> sInstances = new HashMap<>();

}
