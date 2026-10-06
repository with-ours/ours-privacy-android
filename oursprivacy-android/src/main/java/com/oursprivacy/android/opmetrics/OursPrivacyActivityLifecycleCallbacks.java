package com.oursprivacy.android.opmetrics;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
/* package */ class OursPrivacyActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable check;
    private Runnable checkpoint;
    private long nextCheckpointElapsed = -1;
    private MobileSession.TimePoint pauseTimePoint;
    private boolean mIsForeground = false;
    private boolean mPaused = true;
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
        if (!mPaused) {
            pauseTimePoint = mMpInstance.captureMobilePausePoint();
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
                if (mIsForeground && mPaused) {
                    mIsForeground = false;
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
                    mMpInstance.onBackground(pauseTimePoint, sessionProperties);
                    pauseTimePoint = null;
                    nextCheckpointElapsed = -1;
                }
            }
        }, CHECK_DELAY);
    }

    @Override
    public void onActivityDestroyed(Activity activity) { }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }

    @Override
    public void onActivityResumed(Activity activity) {
        mCurrentActivity = new WeakReference<>(activity);

        mPaused = false;
        pauseTimePoint = null;
        boolean wasBackground = !mIsForeground;
        mIsForeground = true;

        if (check != null) {
            mHandler.removeCallbacks(check);
        }

        if (wasBackground) {
            // App is in foreground now
            sStartSessionTime = (double) System.currentTimeMillis();
            mMpInstance.onForeground();
            nextCheckpointElapsed = mMpInstance.captureMobileTimePoint().elapsedMillis
                    + MobileSession.ENGAGEMENT_THRESHOLD_MS;
        }
        scheduleCheckpoint();
    }

    private void scheduleCheckpoint() {
        if (checkpoint != null) {
            mHandler.removeCallbacks(checkpoint);
        }
        if (nextCheckpointElapsed < 0) return;
        checkpoint = new Runnable() {
            @Override
            public void run() {
                if (!mIsForeground || mPaused) return;
                mMpInstance.onCheckpoint();
                nextCheckpointElapsed = mMpInstance.captureMobileTimePoint().elapsedMillis
                        + MobileSession.ENGAGEMENT_THRESHOLD_MS;
                scheduleCheckpoint();
            }
        };
        long remaining = Math.max(0, nextCheckpointElapsed
                - mMpInstance.captureMobileTimePoint().elapsedMillis);
        mHandler.postDelayed(checkpoint, remaining);
    }

    @Override
    public void onActivityStopped(Activity activity) { }

    protected boolean isInForeground() {
        return mIsForeground;
    }

    private final OursPrivacyAPI mMpInstance;
    private final OPConfig mConfig;
    private WeakReference<Activity> mCurrentActivity;
}
