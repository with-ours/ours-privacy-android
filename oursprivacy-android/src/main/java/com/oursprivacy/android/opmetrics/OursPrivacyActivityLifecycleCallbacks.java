package com.oursprivacy.android.opmetrics;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.oursprivacy.android.util.OPLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
/* package */ class OursPrivacyActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable check;
    private Runnable checkpoint;
    private Runnable foregroundRetry;
    private long nextCheckpointElapsed = -1;
    private long checkpointRetryDelayMs = CHECK_DELAY;
    private long foregroundRetryDelayMs = CHECK_DELAY;
    private MobileSession.TimePoint pauseTimePoint;
    private MobileSession.TimePoint resumeTimePoint;
    private JSONObject pendingLegacySessionProperties;
    private boolean backgroundPending;
    private boolean mIsForeground = false;
    private volatile boolean mPaused = true;
    private static Double sStartSessionTime;
    public static final int CHECK_DELAY = 500;

    public OursPrivacyActivityLifecycleCallbacks(OursPrivacyAPI mpInstance, OPConfig config) {
        mMpInstance = mpInstance;
        mConfig = config;
        if (sStartSessionTime == null) {
            sStartSessionTime = (double) System.currentTimeMillis();
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }

    @Override
    public void onActivityPaused(final Activity activity) {
        MobileSession.TimePoint entryPoint = null;
        if (!mPaused) {
            entryPoint = mMpInstance.captureMobileTimePoint();
            mMpInstance.requestMobilePausePoint(entryPoint);
            mPaused = true;
        }
        synchronized (mMpInstance) {
            cancelForegroundRetry();
            resumeTimePoint = null;
            if (entryPoint != null && mIsForeground && !backgroundPending) {
                pauseTimePoint = mMpInstance.captureMobilePausePoint(entryPoint);
            }
            mPaused = true;

            if (check != null) {
                mHandler.removeCallbacks(check);
            }
            if (checkpoint != null) {
                mHandler.removeCallbacks(checkpoint);
            }
            mCurrentActivity = null;

            mHandler.postDelayed(check = new Runnable(){
                @Override
                public void run() {
                    synchronized (mMpInstance) {
                        if (mIsForeground && mPaused) {
                            JSONObject sessionProperties = null;
                            try {
                                double sessionLength = System.currentTimeMillis() - sStartSessionTime;
                                if (sessionLength >= mConfig.getMinimumSessionDuration() && sessionLength < mConfig.getSessionTimeoutDuration() && mMpInstance.getTrackAutomaticEvents()) {
                                    double elapsedTime = sessionLength / 1000;
                                    double elapsedTimeRounded = Math.round(elapsedTime * 10.0) / 10.0;
                                    sessionProperties = new JSONObject();
                                    sessionProperties.put(AutomaticEvents.SESSION_LENGTH, elapsedTimeRounded);
                                }
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                            pendingLegacySessionProperties = sessionProperties;
                            collectBackground();
                        }
                    }
                }
            }, CHECK_DELAY);
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) { }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }

    @Override
    public void onActivityResumed(Activity activity) {
        MobileSession.TimePoint entryPoint = mMpInstance.captureMobileTimePoint();
        synchronized (mMpInstance) {
            mCurrentActivity = new WeakReference<>(activity);
            if (mPaused) resumeTimePoint = entryPoint;
            mPaused = false;
            if (check != null) {
                mHandler.removeCallbacks(check);
            }
            collectResumedForegroundLocked();
        }
    }

    void settleResumedLifecycleForManualCall() {
        synchronized (mMpInstance) {
            if (mPaused || mMpInstance.hasOptedOutTracking()
                    || (!backgroundPending && mIsForeground)) return;
            if (!collectResumedForegroundLocked()) {
                throw new PersistentIdentity.MobileStatePersistenceException(
                        "Unable to record resumed lifecycle");
            }
        }
    }

    private boolean collectResumedForegroundLocked() {
        if (mPaused || mMpInstance.hasOptedOutTracking()) return true;
        if (backgroundPending && !collectBackground()) {
            scheduleForegroundRetry();
            return false;
        }
        if (resumeTimePoint == null) {
            resumeTimePoint = mMpInstance.captureMobileTimePoint();
        }
        mMpInstance.onActivityResume(resumeTimePoint);
        if (!mIsForeground) {
            try {
                mMpInstance.onForeground(resumeTimePoint);
            } catch (PersistentIdentity.MobileStatePersistenceException e) {
                OPLog.w("OursPrivacyActivityLifecycleCallbacks",
                        "Unable to record foreground; will retry", e);
                scheduleForegroundRetry();
                return false;
            }
            mIsForeground = true;
            sStartSessionTime = (double) System.currentTimeMillis();
            checkpointRetryDelayMs = CHECK_DELAY;
            nextCheckpointElapsed = mMpInstance.nextMobileCheckpointElapsed();
        }
        cancelForegroundRetry();
        resumeTimePoint = null;
        if (!mPaused) scheduleCheckpoint();
        return true;
    }

    private void scheduleForegroundRetry() {
        if (foregroundRetry != null) mHandler.removeCallbacks(foregroundRetry);
        foregroundRetry = () -> {
            synchronized (mMpInstance) {
                foregroundRetry = null;
                collectResumedForegroundLocked();
            }
        };
        mHandler.postDelayed(foregroundRetry, foregroundRetryDelayMs);
        foregroundRetryDelayMs = Math.min(30_000, foregroundRetryDelayMs * 2);
    }

    private void cancelForegroundRetry() {
        if (foregroundRetry != null) mHandler.removeCallbacks(foregroundRetry);
        foregroundRetry = null;
        foregroundRetryDelayMs = CHECK_DELAY;
    }

    private boolean collectBackground() {
        try {
            mMpInstance.onBackground(pauseTimePoint, pendingLegacySessionProperties);
        } catch (PersistentIdentity.MobileStatePersistenceException e) {
            backgroundPending = true;
            OPLog.w("OursPrivacyActivityLifecycleCallbacks",
                    "Unable to record background; will retry", e);
            return false;
        }
        mIsForeground = false;
        backgroundPending = false;
        pauseTimePoint = null;
        pendingLegacySessionProperties = null;
        nextCheckpointElapsed = -1;
        return true;
    }

    private void scheduleCheckpoint() {
        if (checkpoint != null) {
            mHandler.removeCallbacks(checkpoint);
        }
        if (nextCheckpointElapsed < 0) return;
        checkpoint = new Runnable() {
            @Override
            public void run() {
                synchronized (mMpInstance) {
                    if (!mIsForeground || mPaused) return;
                    try {
                        mMpInstance.onCheckpoint();
                        checkpointRetryDelayMs = CHECK_DELAY;
                        nextCheckpointElapsed = mMpInstance.captureMobileTimePoint().elapsedMillis
                                + MobileSession.ENGAGEMENT_THRESHOLD_MS;
                    } catch (PersistentIdentity.MobileStatePersistenceException e) {
                        nextCheckpointElapsed = mMpInstance.captureMobileTimePoint().elapsedMillis
                                + checkpointRetryDelayMs;
                        checkpointRetryDelayMs = Math.min(30_000, checkpointRetryDelayMs * 2);
                        OPLog.w("OursPrivacyActivityLifecycleCallbacks",
                                "Unable to record checkpoint; will retry", e);
                    }
                    scheduleCheckpoint();
                }
            }
        };
        long remaining = Math.max(0, nextCheckpointElapsed
                - mMpInstance.captureMobileTimePoint().elapsedMillis);
        mHandler.postDelayed(checkpoint, remaining);
    }

    @Override
    public void onActivityStopped(Activity activity) { }

    protected boolean isInForeground() {
        synchronized (mMpInstance) {
            return mIsForeground;
        }
    }

    void onTrackingDisabled() {
        synchronized (mMpInstance) {
            cancelForegroundRetry();
            if (check != null) mHandler.removeCallbacks(check);
            if (checkpoint != null) mHandler.removeCallbacks(checkpoint);
            mIsForeground = false;
            backgroundPending = false;
            pauseTimePoint = null;
            pendingLegacySessionProperties = null;
            resumeTimePoint = null;
            nextCheckpointElapsed = -1;
            checkpointRetryDelayMs = CHECK_DELAY;
        }
    }

    void onTrackingEnabled() {
        synchronized (mMpInstance) {
            if (mPaused) return;
            cancelForegroundRetry();
            resumeTimePoint = null;
            mMpInstance.onForeground();
            mIsForeground = true;
            sStartSessionTime = (double) System.currentTimeMillis();
            checkpointRetryDelayMs = CHECK_DELAY;
            nextCheckpointElapsed = mMpInstance.nextMobileCheckpointElapsed();
            scheduleCheckpoint();
        }
    }

    private final OursPrivacyAPI mMpInstance;
    private final OPConfig mConfig;
    private WeakReference<Activity> mCurrentActivity;
}
