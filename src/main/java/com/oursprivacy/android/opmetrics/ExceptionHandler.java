package com.oursprivacy.android.opmetrics;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * Captures uncaught exceptions and emits an {@code $ae_crashed} event before
 * delegating to the previously installed default handler.
 *
 * <p>Single-instance — the SDK is single-instance too, so one handler is enough.
 */
public final class ExceptionHandler implements Thread.UncaughtExceptionHandler {

    private static final int SLEEP_TIMEOUT_MS = 400;

    private static ExceptionHandler sInstance;

    private final Thread.UncaughtExceptionHandler mDefaultHandler;
    private WeakReference<OursPrivacyAPI> mInstanceRef;

    private ExceptionHandler() {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    /** Registers the handler the first time it's called and binds the active SDK instance. */
    static synchronized void init(OursPrivacyAPI instance) {
        if (sInstance == null) {
            sInstance = new ExceptionHandler();
        }
        sInstance.mInstanceRef = new WeakReference<>(instance);
    }

    @Override
    public void uncaughtException(final Thread t, final Throwable e) {
        final OursPrivacyAPI instance = mInstanceRef == null ? null : mInstanceRef.get();
        if (instance != null && instance.getTrackAutomaticEvents()) {
            try {
                final JSONObject props = new JSONObject();
                props.put(AutomaticEvents.APP_CRASHED_REASON, e.toString());
                instance.track(AutomaticEvents.APP_CRASHED, props, true);
            } catch (JSONException ignored) {}
        }

        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(t, e);
        } else {
            killProcessAndExit();
        }
    }

    private void killProcessAndExit() {
        try {
            Thread.sleep(SLEEP_TIMEOUT_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }
}
