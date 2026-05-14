package com.oursprivacy.android.opmetrics;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;

import com.oursprivacy.android.BuildConfig;
import com.oursprivacy.android.util.OPConstants;
import com.oursprivacy.android.util.OPLog;
import com.oursprivacy.android.util.OfflineMode;
import com.oursprivacy.android.util.ProxyServerInteractor;

import java.security.GeneralSecurityException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * Global configuration for the SDK. Most callers don't touch this directly — the
 * defaults are picked up from {@code <meta-data>} entries in {@code AndroidManifest.xml}
 * under the {@code com.oursprivacy.android.Config.*} prefix.
 *
 * <p>Supported manifest keys:
 * <dl>
 *   <dt>EnableDebugLogging</dt>      <dd>boolean. Verbose log output. Default false.</dd>
 *   <dt>BulkUploadLimit</dt>         <dd>int. Queue size that triggers an auto-flush. Default 40.</dd>
 *   <dt>FlushInterval</dt>           <dd>int ms. Time-based auto-flush. Default 10000.</dd>
 *   <dt>FlushBatchSize</dt>          <dd>int. Events per POST. Clamped to 50.</dd>
 *   <dt>FlushOnBackground</dt>       <dd>boolean. Flush when the app goes background. Default true.</dd>
 *   <dt>DisableAppOpenEvent</dt>     <dd>boolean. Suppress the automatic {@code $app_open}. Default true.</dd>
 *   <dt>DisableExceptionHandler</dt> <dd>boolean. Don't auto-capture uncaught exceptions. Default false.</dd>
 *   <dt>MinimumSessionDuration</dt>  <dd>int ms. Min duration for {@code $ae_session}. Default 10000.</dd>
 *   <dt>SessionTimeoutDuration</dt>  <dd>int ms. Max session duration. Default {@link Integer#MAX_VALUE}.</dd>
 *   <dt>GzipRequestPayload</dt>      <dd>boolean. Gzip the POST body. Default false.</dd>
 * </dl>
 */
public final class OPConfig {

    public static final String VERSION = BuildConfig.OURSPRIVACY_VERSION;

    public static boolean DEBUG = false;

    public static OPConfig getInstance(Context context) {
        return readConfig(context.getApplicationContext());
    }

    OPConfig(Bundle metaData, Context context) {
        SSLSocketFactory foundSSLFactory;
        try {
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            foundSSLFactory = sslContext.getSocketFactory();
        } catch (final GeneralSecurityException e) {
            OPLog.i(LOGTAG, "System has no SSL support.", e);
            foundSSLFactory = null;
        }
        mSSLSocketFactory = foundSSLFactory;

        DEBUG = metaData.getBoolean("com.oursprivacy.android.Config.EnableDebugLogging", false);
        if (DEBUG) {
            OPLog.setLevel(OPLog.VERBOSE);
        }

        mBulkUploadLimit = metaData.getInt("com.oursprivacy.android.Config.BulkUploadLimit", 40);
        mFlushInterval = metaData.getInt("com.oursprivacy.android.Config.FlushInterval", 10 * 1000);
        mFlushBatchSize = clampBatchSize(metaData.getInt("com.oursprivacy.android.Config.FlushBatchSize", 50));
        mShouldGzipRequestPayload = metaData.getBoolean("com.oursprivacy.android.Config.GzipRequestPayload", false);
        mFlushOnBackground = metaData.getBoolean("com.oursprivacy.android.Config.FlushOnBackground", true);
        mDisableAppOpenEvent = metaData.getBoolean("com.oursprivacy.android.Config.DisableAppOpenEvent", true);
        mDisableExceptionHandler = metaData.getBoolean("com.oursprivacy.android.Config.DisableExceptionHandler", false);
        mMinSessionDuration = metaData.getInt("com.oursprivacy.android.Config.MinimumSessionDuration", 10 * 1000);
        mSessionTimeoutDuration = metaData.getInt("com.oursprivacy.android.Config.SessionTimeoutDuration", Integer.MAX_VALUE);

        mServerURL = OPConstants.URL.OURSPRIVACY_API;
        mEventsEndpoint = mServerURL + OPConstants.URL.EVENT;

        OPLog.v(LOGTAG, toString());
    }

    public synchronized void setSSLSocketFactory(SSLSocketFactory factory) {
        mSSLSocketFactory = factory;
    }

    public synchronized void setOfflineMode(OfflineMode offlineMode) {
        mOfflineMode = offlineMode;
    }

    public int getBulkUploadLimit() { return mBulkUploadLimit; }
    public int getFlushInterval() { return mFlushInterval; }
    public boolean getFlushOnBackground() { return mFlushOnBackground; }
    public int getFlushBatchSize() { return mFlushBatchSize; }
    public boolean shouldGzipRequestPayload() { return mShouldGzipRequestPayload; }

    public void setFlushBatchSize(int batchSize) {
        mFlushBatchSize = clampBatchSize(batchSize);
    }

    public void setFlushOnBackground(boolean flushOnBackground) {
        mFlushOnBackground = flushOnBackground;
    }

    public boolean getDisableAppOpenEvent() { return mDisableAppOpenEvent; }
    public boolean getDisableExceptionHandler() { return mDisableExceptionHandler; }
    public int getMinimumSessionDuration() { return mMinSessionDuration; }
    public int getSessionTimeoutDuration() { return mSessionTimeoutDuration; }

    public String getServerURL() { return mServerURL; }
    public String getEventsEndpoint() { return mEventsEndpoint; }

    public void setServerURL(String serverURL) {
        if (serverURL == null || serverURL.isEmpty()) return;
        mServerURL = stripTrailingSlash(serverURL);
        mEventsEndpoint = mServerURL + OPConstants.URL.EVENT;
    }

    public void setServerURL(String serverURL, ProxyServerInteractor callback) {
        setServerURL(serverURL);
        setProxyServerInteractor(callback);
    }

    public synchronized SSLSocketFactory getSSLSocketFactory() { return mSSLSocketFactory; }

    public synchronized OfflineMode getOfflineMode() { return mOfflineMode; }

    public ProxyServerInteractor getProxyServerInteractor() { return mProxyServerInteractor; }

    public void setProxyServerInteractor(ProxyServerInteractor interactor) {
        mProxyServerInteractor = interactor;
    }

    public void setLoggingEnabled(boolean enabled) {
        DEBUG = enabled;
        OPLog.setLevel(DEBUG ? OPLog.VERBOSE : OPLog.NONE);
    }

    static OPConfig readConfig(Context appContext) {
        final String packageName = appContext.getPackageName();
        try {
            final ApplicationInfo appInfo = appContext.getPackageManager()
                    .getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            Bundle configBundle = appInfo.metaData;
            if (configBundle == null) {
                configBundle = new Bundle();
            }
            return new OPConfig(configBundle, appContext);
        } catch (final NameNotFoundException e) {
            throw new RuntimeException("Can't configure OursPrivacy with package name " + packageName, e);
        }
    }

    private static int clampBatchSize(int batchSize) {
        if (batchSize < 1) return 1;
        if (batchSize > 50) return 50;
        return batchSize;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return null;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public String toString() {
        return "OursPrivacy (" + VERSION + ") configured with:\n"
                + "    BulkUploadLimit " + getBulkUploadLimit() + "\n"
                + "    FlushInterval " + getFlushInterval() + "\n"
                + "    FlushBatchSize " + getFlushBatchSize() + "\n"
                + "    EnableDebugLogging " + DEBUG + "\n"
                + "    EventsEndpoint " + getEventsEndpoint() + "\n"
                + "    MinimumSessionDuration: " + getMinimumSessionDuration() + "\n"
                + "    SessionTimeoutDuration: " + getSessionTimeoutDuration() + "\n"
                + "    DisableExceptionHandler: " + getDisableExceptionHandler() + "\n"
                + "    FlushOnBackground: " + getFlushOnBackground();
    }

    private final int mBulkUploadLimit;
    private final int mFlushInterval;
    private boolean mFlushOnBackground;
    private final boolean mDisableAppOpenEvent;
    private final boolean mDisableExceptionHandler;
    private final int mMinSessionDuration;
    private final int mSessionTimeoutDuration;
    private final boolean mShouldGzipRequestPayload;

    private int mFlushBatchSize;
    private String mServerURL;
    private String mEventsEndpoint;

    private SSLSocketFactory mSSLSocketFactory;
    private OfflineMode mOfflineMode;
    private ProxyServerInteractor mProxyServerInteractor;

    private static final String LOGTAG = "OursPrivacy.Config";
}
