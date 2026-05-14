package com.oursprivacy.android.opmetrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * Focused unit tests for the deep-link parser. Lives in {@code src/test/} so it
 * runs on the JVM via {@code ./gradlew test}; uses Robolectric because the parser
 * leans on {@code android.net.Uri}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
public class AttributionTest {

    @Test
    public void parsesAllSixUtmKeys() {
        final Attribution.Result r = Attribution.parseAttributionFromURL(
                "https://example.com/?utm_source=src&utm_medium=med&utm_campaign=camp"
                        + "&utm_content=ctn&utm_term=trm&utm_name=nm");

        assertEquals("src", r.utmParams.optString("utm_source"));
        assertEquals("med", r.utmParams.optString("utm_medium"));
        assertEquals("camp", r.utmParams.optString("utm_campaign"));
        assertEquals("ctn", r.utmParams.optString("utm_content"));
        assertEquals("trm", r.utmParams.optString("utm_term"));
        assertEquals("nm", r.utmParams.optString("utm_name"));
    }

    @Test
    public void parsesCommonClickIds() {
        final Attribution.Result r = Attribution.parseAttributionFromURL(
                "https://example.com/?gclid=g1&fbclid=f1&ttclid=t1&msclkid=m1&aleid=a1");

        assertEquals("g1", r.clickIds.optString("gclid"));
        assertEquals("f1", r.clickIds.optString("fbclid"));
        assertEquals("t1", r.clickIds.optString("ttclid"));
        assertEquals("m1", r.clickIds.optString("msclkid"));
        assertEquals("a1", r.clickIds.optString("aleid"));
    }

    @Test
    public void capturesOursVisitorIdForStitching() {
        final Attribution.Result r = Attribution.parseAttributionFromURL(
                "myapp://open?ours_visitor_id=visitor-abc&utm_source=email");
        assertEquals("visitor-abc", r.oursVisitorId);
        assertEquals("email", r.utmParams.optString("utm_source"));
    }

    @Test
    public void unknownQueryParamsAreDropped() {
        final Attribution.Result r = Attribution.parseAttributionFromURL(
                "https://example.com/?random_key=ignored&utm_source=keep");
        assertEquals("keep", r.utmParams.optString("utm_source"));
        assertEquals(0, r.clickIds.length());
        assertNull(r.oursVisitorId);
    }

    @Test
    public void emptyOrInvalidUrlReturnsEmptyBags() {
        final Attribution.Result empty = Attribution.parseAttributionFromURL("");
        assertEquals(0, empty.utmParams.length());
        assertEquals(0, empty.clickIds.length());
        assertNull(empty.oursVisitorId);

        final Attribution.Result nullUrl = Attribution.parseAttributionFromURL(null);
        assertEquals(0, nullUrl.utmParams.length());
    }

    @Test
    public void urlWithNoQueryReturnsEmptyBags() {
        final Attribution.Result r = Attribution.parseAttributionFromURL("https://example.com/landing");
        assertEquals(0, r.utmParams.length());
        assertEquals(0, r.clickIds.length());
        assertTrue(r.rawUrl.endsWith("/landing"));
    }
}
