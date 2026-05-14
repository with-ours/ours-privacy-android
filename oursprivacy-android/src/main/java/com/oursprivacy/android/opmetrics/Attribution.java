package com.oursprivacy.android.opmetrics;

import android.net.Uri;

import com.oursprivacy.android.util.OPLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * URL query-string parser for deep-link attribution.
 *
 * Extracts marketing keys (UTMs + click IDs) and the cross-platform stitch
 * parameter {@code ours_visitor_id} from a deep-link URL. The two key lists
 * mirror the server schema in @ours/types — keep in sync.
 */
final class Attribution {

    /** Server-accepted UTM keys. */
    private static final Set<String> UTM_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "utm_campaign",
            "utm_content",
            "utm_medium",
            "utm_name",
            "utm_source",
            "utm_term"
    )));

    /** Server-accepted ad-attribution / click-ID keys. */
    private static final Set<String> CLICK_ID_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "alart",
            "aleid",
            "axwrt",
            "basis_cid",
            "clickid",
            "clid",
            "dclid",
            "epik",
            "fbc",
            "fbclid",
            "fbp",
            "gad_source",
            "gbraid",
            "gclid",
            "im_ref",
            "irclickid",
            "li_fat_id",
            "msclkid",
            "ndclid",
            "qclid",
            "rdt_cid",
            "sacid",
            "sccid",
            "ttclid",
            "twclid",
            "wbraid"
    )));

    /** Cross-platform stitch param. When present, the SDK calls setVisitorId(). */
    private static final String OURS_VISITOR_ID_KEY = "ours_visitor_id";

    static final class Result {
        final String rawUrl;
        final JSONObject utmParams;
        final JSONObject clickIds;
        final String oursVisitorId;

        Result(String rawUrl, JSONObject utmParams, JSONObject clickIds, String oursVisitorId) {
            this.rawUrl = rawUrl;
            this.utmParams = utmParams;
            this.clickIds = clickIds;
            this.oursVisitorId = oursVisitorId;
        }
    }

    /**
     * Parse a URL for attribution params. Unknown query keys are dropped.
     * Returns empty bags (not null) for any section with no matches.
     */
    static Result parseAttributionFromURL(String url) {
        final JSONObject utm = new JSONObject();
        final JSONObject clickIds = new JSONObject();
        String oursVisitorId = null;

        if (url == null || url.isEmpty()) {
            return new Result(url, utm, clickIds, null);
        }

        final Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            OPLog.w(LOGTAG, "Failed to parse deep-link URL: " + url, e);
            return new Result(url, utm, clickIds, null);
        }

        final Set<String> names;
        try {
            names = uri.getQueryParameterNames();
        } catch (UnsupportedOperationException e) {
            // Opaque (non-hierarchical) URI; no query to parse.
            return new Result(url, utm, clickIds, null);
        }

        for (String name : names) {
            if (name == null) continue;
            final String value = uri.getQueryParameter(name);
            if (value == null) continue;

            try {
                if (UTM_KEYS.contains(name)) {
                    utm.put(name, value);
                } else if (CLICK_ID_KEYS.contains(name)) {
                    clickIds.put(name, value);
                } else if (OURS_VISITOR_ID_KEY.equals(name)) {
                    oursVisitorId = value;
                }
            } catch (JSONException ignored) {
                // JSONObject.put only throws for NaN/Inf doubles; values are strings.
            }
        }

        return new Result(url, utm, clickIds, oursVisitorId);
    }

    private Attribution() {}

    private static final String LOGTAG = "OursPrivacy.Attribution";
}
