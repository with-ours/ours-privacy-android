package com.oursprivacy.android.opmetrics;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.DisplayMetrics;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Collects the device / OS / screen fields that go into every event's
 * {@code defaultProperties}. Device fields are cached per process; package
 * metadata is read for each SDK instance so an updated app is observed.
 */
final class OPDefaultProperties {

    private static JSONObject sCached;
    private static final Object sLock = new Object();

    /**
     * Returns a shallow copy of the defaults. Callers may freely overlay
     * caller-supplied default-event-property bags + attribution bags without
     * mutating the cache.
     */
    static JSONObject snapshot(Context context) {
        synchronized (sLock) {
            if (sCached == null) {
                sCached = build(context.getApplicationContext());
            }
            JSONObject defaults = copy(sCached);
            addAppProperties(defaults, context.getApplicationContext());
            return defaults;
        }
    }

    @SuppressWarnings("deprecation")
    private static void addAppProperties(JSONObject defaults, Context appContext) {
        if (appContext == null) return;
        try {
            PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            if (info.versionName != null && !info.versionName.isEmpty()) {
                defaults.put("app_version", info.versionName);
            }
            long build = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            defaults.put("app_build", Long.toString(build));
        } catch (PackageManager.NameNotFoundException | JSONException ignored) {
        }
    }

    private static JSONObject build(Context appContext) {
        final JSONObject out = new JSONObject();
        try {
            out.put("device_vendor", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            out.put("device_model", Build.MODEL == null ? "" : Build.MODEL);
            out.put("device_type", "mobile");
            out.put("os_name", "Android");
            out.put("os_version", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            out.put("version", OPConfig.VERSION);
            final DisplayMetrics dm = appContext == null
                    ? Resources.getSystem().getDisplayMetrics()
                    : appContext.getResources().getDisplayMetrics();
            if (dm != null) {
                out.put("screen_width", dm.widthPixels);
                out.put("screen_height", dm.heightPixels);
            }
        } catch (JSONException ignored) {
            // JSONObject.put only throws for NaN/Inf doubles; we put strings + ints.
        }
        return out;
    }

    private static JSONObject copy(JSONObject src) {
        final JSONObject out = new JSONObject();
        try {
            final java.util.Iterator<String> keys = src.keys();
            while (keys.hasNext()) {
                final String k = keys.next();
                out.put(k, src.opt(k));
            }
        } catch (JSONException ignored) {}
        return out;
    }

    private OPDefaultProperties() {}
}
