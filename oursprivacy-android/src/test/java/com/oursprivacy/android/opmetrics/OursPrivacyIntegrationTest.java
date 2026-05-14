package com.oursprivacy.android.opmetrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * Boots the SDK against a Robolectric Android, fires a small scenario of public
 * API calls, and asserts the canonical envelope that lands on the wire.
 *
 * <p>This is the JVM "layer 1" wire-shape verification — runs in seconds via
 * {@code ./gradlew test}, no device or emulator needed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
public class OursPrivacyIntegrationTest {

    private static final String TOKEN = "test-token-abc123";
    private static final long IDLE_TIMEOUT_MS = 5_000;

    private Context mContext;
    private CapturingRemoteService mNetwork;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        // Wipe persisted state so each test starts from a known-clean slate.
        mContext.getSharedPreferences("com.oursprivacy.android.OursPrivacy",
                android.content.Context.MODE_PRIVATE).edit().clear().commit();
        mNetwork = new CapturingRemoteService();
        AnalyticsMessages.sTestRemoteService = mNetwork;
    }

    @After
    public void tearDown() {
        AnalyticsMessages.sTestRemoteService = null;
    }

    @Test
    public void track_track_flush_emitsOneEnvelopeWithBothEvents() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, null);

        op.track("event_one", jsonOf("position", 1));
        op.track("event_two", jsonOf("position", 2));
        op.flush();

        assertTrue("worker drained", op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        assertEquals("expected one batched HTTPS request", 1, mNetwork.callCount());
        assertTrue(mNetwork.endpoints().get(0).endsWith("/ingest"));

        final JSONObject envelope = mNetwork.bodyAt(0);
        assertEquals(TOKEN, envelope.getString("token"));
        assertFalse(envelope.getBoolean("is_manually_set_id"));

        final JSONArray data = envelope.getJSONArray("data");
        assertEquals(2, data.length());

        final JSONObject first = data.getJSONObject(0);
        assertEquals("event_one", first.getString("event"));
        assertEquals(1, first.getJSONObject("eventProperties").getInt("position"));
        assertNotNull(first.getString("visitor_id"));
        assertNotNull(first.getString("distinct_id"));
        assertTrue(first.has("defaultProperties"));

        final JSONObject second = data.getJSONObject(1);
        assertEquals("event_two", second.getString("event"));
        assertEquals(2, second.getJSONObject("eventProperties").getInt("position"));

        // visitor_id is stable across events.
        assertEquals(first.getString("visitor_id"), second.getString("visitor_id"));
    }

    @Test
    public void track_flush_track_flush_emitsTwoSeparateEnvelopes() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, null);

        op.track("first_batch_event");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        op.track("second_batch_event");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        assertEquals("expected two separate POSTs", 2, mNetwork.callCount());

        final JSONObject first = mNetwork.bodyAt(0);
        assertEquals(1, first.getJSONArray("data").length());
        assertEquals("first_batch_event",
                first.getJSONArray("data").getJSONObject(0).getString("event"));

        final JSONObject second = mNetwork.bodyAt(1);
        assertEquals(1, second.getJSONArray("data").length());
        assertEquals("second_batch_event",
                second.getJSONArray("data").getJSONObject(0).getString("event"));
    }

    @Test
    public void identify_carriesUserPropertiesAndCamelCaseToSnakeCase() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, null);

        op.identify(OursPrivacyUserProperties.builder()
                .email("alex@example.com")
                .externalId("user_42")
                .phoneNumber("+1-555-0100")
                .firstName("Alex")
                .lastName("Doe")
                .build());
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        assertEquals(1, mNetwork.callCount());
        final JSONObject envelope = mNetwork.bodyAt(0);
        final JSONObject item = envelope.getJSONArray("data").getJSONObject(0);

        assertEquals("$identify", item.getString("event"));
        assertTrue(item.isNull("eventProperties"));

        final JSONObject user = item.getJSONObject("userProperties");
        assertEquals("alex@example.com", user.getString("email"));
        assertEquals("user_42", user.getString("external_id"));
        assertEquals("+1-555-0100", user.getString("phone_number"));
        assertEquals("Alex", user.getString("first_name"));
        assertEquals("Doe", user.getString("last_name"));
    }

    @Test
    public void setVisitorId_flipsIsManuallySetIdFlagOnEnvelope() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, null);

        op.setVisitorId("custom-visitor-id-from-web");
        op.track("after_stitch");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        final JSONObject envelope = mNetwork.bodyAt(0);
        assertTrue(envelope.getBoolean("is_manually_set_id"));
        final JSONObject item = envelope.getJSONArray("data").getJSONObject(0);
        assertEquals("custom-visitor-id-from-web", item.getString("visitor_id"));
    }

    @Test
    public void defaultUserCustomPropertiesMergeIntoIdentifyAndTrack() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .defaultUserCustomProperties(jsonOf("tier", "gold"))
                .build());

        op.identify(OursPrivacyUserProperties.builder()
                .externalId("user_42")
                .customProperties(jsonOf("role", "admin"))
                .build());
        op.track("checkout_started");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        final JSONArray data = mNetwork.bodyAt(0).getJSONArray("data");
        assertEquals(2, data.length());

        final JSONObject identifyUser = data.getJSONObject(0).getJSONObject("userProperties");
        final JSONObject identifyCustom = identifyUser.getJSONObject("custom_properties");
        assertEquals("gold", identifyCustom.getString("tier"));
        assertEquals("admin", identifyCustom.getString("role"));

        final JSONObject trackUser = data.getJSONObject(1).getJSONObject("userProperties");
        final JSONObject trackCustom = trackUser.getJSONObject("custom_properties");
        assertEquals("gold", trackCustom.getString("tier"));
        // No per-call custom on the track call, but defaults still merge.
        assertFalse(trackCustom.has("role"));
    }

    @Test
    public void consentIsOmittedWhenBothDefaultsAndPerCallAreEmpty() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .defaultUserCustomProperties(jsonOf("plan", "pro"))
                .build());

        op.track("page_viewed");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        final JSONObject item = mNetwork.bodyAt(0).getJSONArray("data").getJSONObject(0);
        final JSONObject user = item.getJSONObject("userProperties");
        assertTrue("custom_properties present", user.has("custom_properties"));
        assertFalse("consent omitted when both default and per-call bags are empty", user.has("consent"));
    }

    @Test
    public void consentMergesWhenEitherSideHasData() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .defaultUserConsentProperties(jsonOf("marketing", true))
                .build());

        op.identify(OursPrivacyUserProperties.builder()
                .consent(jsonOf("analytics", true))
                .build());
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        final JSONObject user = mNetwork.bodyAt(0).getJSONArray("data").getJSONObject(0)
                .getJSONObject("userProperties");
        final JSONObject consent = user.getJSONObject("consent");
        assertTrue(consent.getBoolean("marketing"));
        assertTrue(consent.getBoolean("analytics"));
    }

    @Test
    public void trackDeepLink_extractsUtmAndFiresDeepLinkOpened() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, null);

        op.trackDeepLink("https://example.com/landing?utm_source=newsletter&utm_campaign=spring&gclid=abc123");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        final JSONObject item = mNetwork.bodyAt(0).getJSONArray("data").getJSONObject(0);
        assertEquals("$deep_link_opened", item.getString("event"));

        final JSONObject defaults = item.getJSONObject("defaultProperties");
        assertEquals("newsletter", defaults.getString("utm_source"));
        assertEquals("spring", defaults.getString("utm_campaign"));
        assertEquals("abc123", defaults.getString("gclid"));
    }

    @Test
    public void trackDeepLink_setsVisitorIdWhenOursVisitorIdParamPresent() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, null);

        op.trackDeepLink("https://example.com/?ours_visitor_id=visitor-from-web-xyz");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        assertEquals("visitor-from-web-xyz", op.getVisitorId());
        final JSONObject envelope = mNetwork.bodyAt(0);
        assertTrue(envelope.getBoolean("is_manually_set_id"));
    }

    @Test
    public void optOut_clearsQueuedEventsAndDropsSubsequentTracks() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, null);

        op.track("queued_before_opt_out");
        op.optOutTracking();
        op.track("dropped_after_opt_out");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        assertEquals("flush() is a no-op when opted out", 0, mNetwork.callCount());
        assertTrue(op.hasOptedOutTracking());
    }

    @Test
    public void defaultEventPropertiesAreMergedIntoEveryTrack() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .defaultEventProperties(jsonOf("app_version", "2.0.0"))
                .build());

        op.track("a");
        op.track("b", jsonOf("custom_event_field", "yes"));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        final JSONArray data = mNetwork.bodyAt(0).getJSONArray("data");
        assertEquals("2.0.0", data.getJSONObject(0).getJSONObject("eventProperties").getString("app_version"));
        final JSONObject bProps = data.getJSONObject(1).getJSONObject("eventProperties");
        assertEquals("2.0.0", bProps.getString("app_version"));
        assertEquals("yes", bProps.getString("custom_event_field"));
    }

    @Test
    public void defaultPropertiesIncludeDeviceAndOsFields() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, null);

        op.track("first_event");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        final JSONObject defaults = mNetwork.bodyAt(0).getJSONArray("data").getJSONObject(0)
                .getJSONObject("defaultProperties");
        assertEquals("mobile", defaults.getString("device_type"));
        assertEquals("Android", defaults.getString("os_name"));
        assertNotNull(defaults.getString("device_vendor"));
        assertNotNull(defaults.getString("device_model"));
        assertNotNull(defaults.getString("version"));
    }

    @Test
    public void reset_regeneratesVisitorIdAndClearsDefaults() throws Exception {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        op.initialize(TOKEN, null);

        final String visitorBefore = op.getVisitorId();
        op.updateDefaultEventProperties(jsonOf("k", "v"));
        op.reset();
        op.track("post_reset");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        final String visitorAfter = op.getVisitorId();
        assertFalse("visitor_id rotated", visitorBefore.equals(visitorAfter));

        final JSONObject item = mNetwork.bodyAt(0).getJSONArray("data").getJSONObject(0);
        // Default event property cleared; eventProperties should be null.
        assertTrue(item.isNull("eventProperties"));
    }

    private static JSONObject jsonOf(String key, Object value) {
        try {
            return new JSONObject().put(key, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
