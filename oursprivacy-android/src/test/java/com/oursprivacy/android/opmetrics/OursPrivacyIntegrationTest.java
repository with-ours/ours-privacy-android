package com.oursprivacy.android.opmetrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

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
    private Thread.UncaughtExceptionHandler mOriginalExceptionHandler;
    private ExceptionHandler mOriginalSdkExceptionHandler;
    private final java.util.List<OursPrivacyAPI> mInstances = new java.util.ArrayList<>();

    @Before
    public void setUp() {
        mOriginalExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        mOriginalSdkExceptionHandler = ReflectionHelpers.getStaticField(
                ExceptionHandler.class, "sInstance");
        ReflectionHelpers.setStaticField(ExceptionHandler.class, "sInstance", null);
        mContext = ApplicationProvider.getApplicationContext();
        PackageInfo packageInfo = Shadows.shadowOf(mContext.getPackageManager())
                .getInternalMutablePackageInfo(mContext.getPackageName());
        packageInfo.versionName = "2.5.1";
        packageInfo.setLongVersionCode(42);
        // Wipe persisted state so each test starts from a known-clean slate.
        mContext.getSharedPreferences("com.oursprivacy.android.OursPrivacy",
                android.content.Context.MODE_PRIVATE).edit().clear().commit();
        mNetwork = new CapturingRemoteService();
        AnalyticsMessages.sTestRemoteService = mNetwork;
    }

    @After
    public void tearDown() {
        // Kill every worker HandlerThread this test started. Without this, leaked
        // workers keep firing scheduled flushes against the next test's mNetwork.
        try {
            for (OursPrivacyAPI op : mInstances) op.shutdownForTests();
        } finally {
            mInstances.clear();
            AnalyticsMessages.sTestRemoteService = null;
            Thread.setDefaultUncaughtExceptionHandler(mOriginalExceptionHandler);
            ReflectionHelpers.setStaticField(
                    ExceptionHandler.class, "sInstance", mOriginalSdkExceptionHandler);
        }
    }

    private OursPrivacyAPI newApi() {
        final OursPrivacyAPI op = new OursPrivacyAPI(mContext);
        mInstances.add(op);
        return op;
    }

    private OursPrivacyAPI newApi(FakeClock clock, SharedPreferences preferences) {
        OursPrivacyAPI op = new OursPrivacyAPI(mContext, clock,
                CompletableFuture.completedFuture(preferences));
        mInstances.add(op);
        return op;
    }

    @Test
    public void lifecycleTrackingDoesNotRegisterOrEmitCrashesByDefault() throws Exception {
        AtomicReference<Throwable> forwarded = new AtomicReference<>();
        Thread.UncaughtExceptionHandler delegate = (thread, error) -> forwarded.set(error);
        Thread.setDefaultUncaughtExceptionHandler(delegate);

        OursPrivacyInitOptions options = OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build();
        assertFalse(options.getTrackAutomaticCrashes());
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, options);
        assertSame(delegate, Thread.getDefaultUncaughtExceptionHandler());

        RuntimeException crash = new RuntimeException("default crash probe");
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), crash);
        assertSame(crash, forwarded.get());
        assertEquals(0, queuedEventCount(AutomaticEvents.APP_CRASHED));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, count(allCapturedData(), AutomaticEvents.APP_CRASHED));
    }

    @Test
    public void explicitCrashOptInEmitsLegacyPayloadWithLifecycleOff() throws Exception {
        AtomicReference<Throwable> forwarded = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> forwarded.set(error));

        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticCrashes(true).build());
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() instanceof ExceptionHandler);

        IllegalStateException crash = new IllegalStateException("crash reason");
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), crash);
        assertSame(crash, forwarded.get());
        assertEquals(1, queuedEventCount(AutomaticEvents.APP_CRASHED));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        assertEquals(1, count(data, AutomaticEvents.APP_CRASHED));
        assertEquals(crash.toString(), find(data, AutomaticEvents.APP_CRASHED)
                .getJSONObject("eventProperties").getString(AutomaticEvents.APP_CRASHED_REASON));
        assertEquals(0, count(data, "$mobile_app_open"));
    }

    @Test
    public void optingOutStopsAnOptedInCrashHandlerFromEmitting() throws Exception {
        AtomicReference<Throwable> forwarded = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> forwarded.set(error));

        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticCrashes(true).build());
        op.optOutTracking();
        IllegalStateException crash = new IllegalStateException("after opt out");
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), crash);
        assertSame(crash, forwarded.get());
        assertEquals(0, queuedEventCount(AutomaticEvents.APP_CRASHED));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, mNetwork.callCount());
    }

    @Test
    public void manifestDisablePreventsCrashRegistrationDespiteOptIn() throws Exception {
        AtomicReference<Throwable> forwarded = new AtomicReference<>();
        Thread.UncaughtExceptionHandler delegate = (thread, error) -> forwarded.set(error);
        Thread.setDefaultUncaughtExceptionHandler(delegate);
        PackageInfo packageInfo = Shadows.shadowOf(mContext.getPackageManager())
                .getInternalMutablePackageInfo(mContext.getPackageName());
        if (packageInfo.applicationInfo.metaData == null) {
            packageInfo.applicationInfo.metaData = new Bundle();
        }
        packageInfo.applicationInfo.metaData.putBoolean(
                "com.oursprivacy.android.Config.DisableExceptionHandler", true);

        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticCrashes(true).build());
        assertSame(delegate, Thread.getDefaultUncaughtExceptionHandler());

        IllegalStateException crash = new IllegalStateException("manifest disabled");
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), crash);
        assertSame(crash, forwarded.get());
        assertEquals(0, queuedEventCount(AutomaticEvents.APP_CRASHED));
    }

    @Test
    public void explicitScreensQueueOnceAndAssignPriorEngagementBeforeNewView()
            throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true)
                .visitorId("stitched-visitor")
                .initialURL("https://example.test/?utm_source=campaign")
                .defaultEventProperties(jsonOf("caller_field", "caller-value"))
                .defaultUserCustomProperties(jsonOf("segment", "test-segment"))
                .build());
        op.onForeground();
        op.trackScreen("Schedule");
        clock.advance(2_000);
        op.trackScreen("Schedule");
        clock.advance(3_000);
        op.trackScreen("Visit Details");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        assertEquals(2, count(data, "$mobile_screen_view"));
        assertEquals(1, count(data, "$mobile_session_engagement"));
        JSONObject first = nth(data, "$mobile_screen_view", 0);
        JSONObject engagement = find(data, "$mobile_session_engagement");
        JSONObject second = nth(data, "$mobile_screen_view", 1);
        assertEquals("Schedule", first.getJSONObject("eventProperties").getString("screen_name"));
        assertEquals("Schedule", engagement.getJSONObject("eventProperties")
                .getString("screen_name"));
        assertEquals(5_000, engagement.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));
        assertEquals("Visit Details", second.getJSONObject("eventProperties")
                .getString("screen_name"));
        assertEquals("stitched-visitor", second.getString("visitor_id"));
        assertEquals(first.getJSONObject("defaultProperties").getString("sid"),
                second.getJSONObject("defaultProperties").getString("sid"));
        assertEquals("android", second.getJSONObject("defaultProperties")
                .getString("mobile_platform"));
        assertFalse(first.getJSONObject("eventProperties").has("caller_field"));
        assertTrue(first.isNull("userProperties"));
        assertFalse(first.getJSONObject("defaultProperties").has("utm_source"));
        assertTrue(indexOf(data, engagement) < indexOf(data, second));
    }

    @Test
    public void explicitScreenWorksWithAutomaticOffButOptOutDropsIt() throws Exception {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, null);
        op.trackScreen("Schedule");
        assertEquals(1, queuedEventCount("$mobile_screen_view"));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        JSONArray data = allCapturedData();
        assertEquals(1, count(data, "$mobile_screen_view"));
        assertEquals(0, count(data, "$mobile_app_open"));

        mNetwork.reset();
        op.optOutTracking();
        op.trackScreen("Visit Details");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedEventCount("$mobile_screen_view"));
        assertEquals(0, mNetwork.callCount());
    }

    @Test
    public void explicitScreenRejectsEmptyParameterizedAndOverlongLabels() {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, null);
        for (String label : new String[]{null, "", " schedule", "Schedule ", "visit/123",
                "Visit?patient=42", "A".repeat(81)}) {
            assertThrows(IllegalArgumentException.class, () -> op.trackScreen(label));
        }
    }

    @Test
    public void automaticForegroundAndManualBookingShareCanonicalSessionOnWire()
            throws Exception {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        op.onForeground();
        op.onForeground();
        op.track("appointment_booked", jsonOf("appointment_id", "booking-1"));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = mNetwork.bodyAt(0).getJSONArray("data");
        assertEquals(1, count(data, "$mobile_first_open"));
        assertEquals(1, count(data, "$mobile_app_open"));
        assertEquals(1, count(data, "$mobile_session_start"));
        assertEquals(1, count(data, "appointment_booked"));
        assertEquals(1, count(data, AutomaticEvents.FIRST_OPEN));
        JSONObject first = find(data, "$mobile_first_open");
        JSONObject booked = find(data, "appointment_booked");
        JSONObject defaults = booked.getJSONObject("defaultProperties");
        PackageInfo packageInfo = mContext.getPackageManager()
                .getPackageInfo(mContext.getPackageName(), 0);
        assertEquals(first.getJSONObject("defaultProperties").getString("sid"),
                defaults.getString("sid"));
        assertEquals("android", defaults.getString("mobile_platform"));
        assertEquals(1, defaults.getInt("mobile_contract_version"));
        assertEquals(packageInfo.versionName, defaults.getString("app_version"));
        assertEquals(Long.toString(packageInfo.getLongVersionCode()),
                defaults.getString("app_build"));
        assertEquals(OursPrivacyAPI.VERSION, defaults.getString("version"));
        assertTrue(defaults.getString("mobile_session_started_at")
                .matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
        assertTrue(defaults.getString("mobile_occurred_at")
                .matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
        assertFalse(booked.has("time"));
        assertEquals("booking-1", booked.getJSONObject("eventProperties")
                .getString("appointment_id"));
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < data.length(); i++) {
            assertTrue(ids.add(data.getJSONObject(i).getString("distinct_id")));
        }
    }

    @Test
    public void initialDeepLinkAndAutoOffBookingUseStitchedVisitorAndSession()
            throws Exception {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .visitorId("host-visitor")
                .initialURL("https://example.test/?ours_visitor_id=stitched-visitor&utm_source=app")
                .build());
        op.track("appointment_booked", jsonOf("appointment_id", "booking-2"));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = mNetwork.bodyAt(0).getJSONArray("data");
        assertEquals(0, count(data, "$mobile_first_open"));
        assertEquals(0, count(data, "$mobile_app_open"));
        JSONObject deepLink = find(data, "$deep_link_opened");
        JSONObject booking = find(data, "appointment_booked");
        assertEquals("stitched-visitor", deepLink.getString("visitor_id"));
        assertEquals("stitched-visitor", booking.getString("visitor_id"));
        assertEquals(deepLink.getJSONObject("defaultProperties").getString("sid"),
                booking.getJSONObject("defaultProperties").getString("sid"));
        assertEquals("app", booking.getJSONObject("defaultProperties").getString("utm_source"));
        assertEquals("android", deepLink.getJSONObject("defaultProperties")
                .getString("mobile_platform"));
        assertFalse(booking.has("time"));
    }

    @Test
    public void initialDeepLinkOmitsRawUrlAndPatientParameterFromTelemetry()
            throws Exception {
        OursPrivacyAPI op = newApi();
        ShadowLog.clear();
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true)
                .initialURL("https://example.test/visit?utm_source=campaign"
                        + "&ours_visitor_id=visitor-from-web&patient_email=secret")
                .build());
        op.onForeground();
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        JSONObject opened = find(data, "$deep_link_opened");
        assertEquals("visitor-from-web", opened.getString("visitor_id"));
        assertEquals("campaign", opened.getJSONObject("defaultProperties")
                .getString("utm_source"));
        assertTrue(opened.isNull("eventProperties"));
        assertFalse(data.toString().contains("https://example.test"));
        assertFalse(data.toString().contains("patient_email"));
        assertFalse(data.toString().contains("secret"));
        JSONObject automaticDefaults = find(data, "$mobile_first_open")
                .getJSONObject("defaultProperties");
        assertFalse(automaticDefaults.has("advertising_id"));
        assertFalse(automaticDefaults.has("gaid"));
        assertFalse(automaticDefaults.has("patient_email"));
        for (ShadowLog.LogItem log : ShadowLog.getLogs()) {
            assertFalse(log.msg.contains("https://example.test"));
            assertFalse(log.msg.contains("patient_email"));
        }
    }

    @Test
    public void explicitDeepLinkOmitsRawUrlAndPatientParameterFromTelemetry()
            throws Exception {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, null);
        op.trackDeepLink("https://example.test/visit?utm_source=campaign"
                + "&ours_visitor_id=visitor-from-web&patient_email=secret");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONObject opened = find(allCapturedData(), "$deep_link_opened");
        assertEquals("visitor-from-web", opened.getString("visitor_id"));
        assertEquals("campaign", opened.getJSONObject("defaultProperties")
                .getString("utm_source"));
        assertTrue(opened.isNull("eventProperties"));
        assertFalse(opened.toString().contains("https://example.test"));
        assertFalse(opened.toString().contains("patient_email"));
    }

    @Test
    public void canonicalFactsOmitCustomerFieldsWhileManualBookingRetainsThem()
            throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true)
                .initialURL("https://example.test/?ours_visitor_id=stitched&utm_source=campaign")
                .defaultEventProperties(jsonOf("caller_field", "caller-value"))
                .defaultUserCustomProperties(jsonOf("segment", "test-segment"))
                .build());
        op.onForeground();
        clock.advance(10_000);
        op.onBackground();
        op.track("appointment_booked", jsonOf("appointment_id", "booking-3"));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            if (!item.getString("event").startsWith("$mobile_")) continue;
            assertEquals("stitched", item.getString("visitor_id"));
            assertFalse(item.getJSONObject("defaultProperties").has("utm_source"));
            assertTrue(item.isNull("userProperties"));
            assertTrue(item.isNull("eventProperties")
                    || !item.getJSONObject("eventProperties").has("caller_field"));
        }
        JSONObject booking = find(data, "appointment_booked");
        assertEquals("stitched", booking.getString("visitor_id"));
        assertEquals("campaign", booking.getJSONObject("defaultProperties")
                .getString("utm_source"));
        assertEquals("caller-value", booking.getJSONObject("eventProperties")
                .getString("caller_field"));
        assertEquals("test-segment", booking.getJSONObject("userProperties")
                .getJSONObject("custom_properties").getString("segment"));
        assertEquals(1, count(data, "$mobile_first_open"));
        assertEquals(1, count(data, "$mobile_session_engagement"));
    }

    @Test
    public void backgroundAndWarmReturnEmitOneEngagementAndReuseSession()
            throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        op.onForeground();
        clock.advance(10_000);
        op.onBackground();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        JSONArray initial = mNetwork.bodyAt(0).getJSONArray("data");
        JSONObject start = find(initial, "$mobile_session_start");
        JSONObject engagement = find(initial, "$mobile_session_engagement");
        assertEquals(10_000, engagement.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));

        clock.advance(29 * 60_000 + 59_000);
        op.onForeground();
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        JSONArray warm = mNetwork.bodyAt(1).getJSONArray("data");
        assertEquals(1, count(warm, "$mobile_app_open"));
        assertEquals(0, count(warm, "$mobile_session_start"));
        assertEquals(start.getJSONObject("defaultProperties").getString("sid"),
                find(warm, "$mobile_app_open").getJSONObject("defaultProperties")
                        .getString("sid"));
    }

    @Test
    public void failedFirstOpenQueueMoveReplaysSameFactAfterRestart() throws Exception {
        FakeClock clock = new FakeClock();
        AtomicBoolean failQueueCommit = new AtomicBoolean(true);
        SharedPreferences wrapped = preferencesWithQueueCommitFailure(failQueueCommit);
        OursPrivacyAPI first = newApi(clock, wrapped);
        first.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        first.onForeground();
        JSONObject pending = mobileState().getJSONArray("pending_facts").getJSONObject(0);
        String id = pending.getString("id");
        String visitor = pending.getString("visitor_id");
        long occurredAt = pending.getLong("occurred_at");
        assertFalse(mobileState().getBoolean("first_open_accepted"));
        assertEquals(0, queuedEventCount("$mobile_first_open"));
        first.shutdownForTests();

        failQueueCommit.set(false);
        OursPrivacyAPI restarted = newApi(clock, wrapped);
        restarted.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        assertEquals(1, queuedEventCount("$mobile_first_open"));
        assertTrue(mobileState().getBoolean("first_open_accepted"));
        JSONObject queued = queuedEvent("$mobile_first_open");
        assertEquals(id, queued.getString("distinct_id"));
        assertEquals(visitor, queued.getString("visitor_id"));
        assertEquals(MobileSession.utc(occurredAt), queued.getJSONObject("defaultProperties")
                .getString("mobile_occurred_at"));
        restarted.onForeground();
        assertEquals(1, queuedEventCount("$mobile_first_open"));
    }

    @Test
    public void sentFirstOpenStaysConsumedAfterRestart() throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI first = newApi(clock, preferences());
        first.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        first.onForeground();
        first.flush();
        assertTrue(first.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(1, count(allCapturedData(), "$mobile_first_open"));
        first.shutdownForTests();
        mNetwork.reset();

        OursPrivacyAPI restarted = newApi(clock, preferences());
        restarted.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        restarted.onForeground();
        restarted.flush();
        assertTrue(restarted.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        assertEquals(0, count(data, "$mobile_first_open"));
        assertEquals(1, count(data, "$mobile_app_open"));
        assertTrue(mobileState().getBoolean("first_open_accepted"));
        assertEquals(0, queuedEventCount("$mobile_first_open"));
    }

    @Test
    public void queuedFirstOpenKeepsItsIdentityAfterRestart() throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI first = newApi(clock, preferences());
        first.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        first.onForeground();
        String firstId = queuedEvent("$mobile_first_open").getString("distinct_id");
        first.shutdownForTests();

        OursPrivacyAPI restarted = newApi(clock, preferences());
        restarted.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        restarted.onForeground();
        assertEquals(1, queuedEventCount("$mobile_first_open"));
        assertEquals(firstId, queuedEvent("$mobile_first_open").getString("distinct_id"));
        restarted.flush();
        assertTrue(restarted.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(1, count(allCapturedData(), "$mobile_first_open"));
    }

    @Test
    public void changedPackageVersionAfterRestartEmitsUpdateWithCurrentDefaults()
            throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI first = newApi(clock, preferences());
        first.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        first.onForeground();
        first.flush();
        assertTrue(first.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        first.shutdownForTests();
        mNetwork.reset();

        PackageInfo packageInfo = Shadows.shadowOf(mContext.getPackageManager())
                .getInternalMutablePackageInfo(mContext.getPackageName());
        packageInfo.versionName = "3.0.0";
        packageInfo.setLongVersionCode(43);
        OursPrivacyAPI updated = newApi(clock, preferences());
        updated.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        updated.onForeground();
        updated.flush();
        assertTrue(updated.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        JSONObject update = find(data, "$mobile_app_update");
        assertEquals("2.5.1", update.getJSONObject("eventProperties")
                .getString("previous_app_version"));
        assertEquals("42", update.getJSONObject("eventProperties")
                .getString("previous_app_build"));
        JSONObject defaults = update.getJSONObject("defaultProperties");
        assertEquals("3.0.0", defaults.getString("app_version"));
        assertEquals("43", defaults.getString("app_build"));
        assertEquals(0, count(data, "$mobile_first_open"));
    }

    @Test
    public void pendingFactKeepsAbsentVersionFromOriginalCapture() throws Exception {
        PackageInfo packageInfo = Shadows.shadowOf(mContext.getPackageManager())
                .getInternalMutablePackageInfo(mContext.getPackageName());
        packageInfo.versionName = null;
        FakeClock clock = new FakeClock();
        AtomicBoolean failQueueCommit = new AtomicBoolean(true);
        SharedPreferences wrapped = preferencesWithQueueCommitFailure(failQueueCommit);
        OursPrivacyAPI first = newApi(clock, wrapped);
        first.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        first.onForeground();
        assertTrue(mobileState().getJSONArray("pending_facts").length() > 0);
        first.shutdownForTests();

        packageInfo.versionName = "3.0.0";
        packageInfo.setLongVersionCode(43);
        failQueueCommit.set(false);
        OursPrivacyAPI restarted = newApi(clock, wrapped);
        restarted.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        JSONObject firstOpen = queuedEvent("$mobile_first_open");
        JSONObject defaults = firstOpen.getJSONObject("defaultProperties");
        assertFalse(defaults.has("app_version"));
        assertEquals("42", defaults.getString("app_build"));
    }

    @Test
    public void replayedFactKeepsCapturedEventPropertiesAfterDefaultsChange()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicBoolean failQueueCommit = new AtomicBoolean(true);
        OursPrivacyAPI op = newApi(clock, preferencesWithQueueCommitFailure(failQueueCommit));
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        op.onForeground();
        JSONObject pending = mobileState().getJSONArray("pending_facts").getJSONObject(0);
        String id = pending.getString("id");
        op.updateDefaultEventProperties(jsonOf("new_default", "later"));
        failQueueCommit.set(false);

        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        JSONObject firstOpen = find(mNetwork.bodyAt(0).getJSONArray("data"),
                "$mobile_first_open");
        assertEquals(id, firstOpen.getString("distinct_id"));
        assertTrue(firstOpen.isNull("eventProperties")
                || firstOpen.getJSONObject("eventProperties").length() == 0);
    }

    @Test
    public void replayedFirstOpenOmitsAttributionFromOriginalForeground()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicBoolean failQueueCommit = new AtomicBoolean(true);
        OursPrivacyAPI op = newApi(clock, preferencesWithQueueCommitFailure(failQueueCommit));
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true)
                .initialURL("https://example.test/?utm_source=first")
                .build());
        op.onForeground();
        assertFalse(mobileState().getJSONArray("pending_facts").getJSONObject(0)
                .has("attribution_properties"));
        failQueueCommit.set(false);
        op.trackDeepLink("https://example.test/?utm_source=second");
        op.track("appointment_booked");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        assertFalse(find(data, "$mobile_first_open")
                .getJSONObject("defaultProperties").has("utm_source"));
        assertEquals("second", find(data, "appointment_booked")
                .getJSONObject("defaultProperties").getString("utm_source"));
    }

    @Test
    public void initialStitchPrecedesCanonicalFirstOpen() throws Exception {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true)
                .visitorId("host-visitor")
                .initialURL("https://example.test/?ours_visitor_id=stitched&utm_source=app")
                .build());
        op.onForeground();
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = mNetwork.bodyAt(0).getJSONArray("data");
        assertEquals("stitched", find(data, "$mobile_first_open")
                .getString("visitor_id"));
        assertEquals("stitched", find(data, "$mobile_app_open")
                .getString("visitor_id"));
        assertEquals("stitched", find(data, "$mobile_session_start")
                .getString("visitor_id"));
        assertFalse(find(data, "$mobile_first_open")
                .getJSONObject("defaultProperties").has("utm_source"));
        assertEquals("app", find(data, "$deep_link_opened")
                .getJSONObject("defaultProperties").getString("utm_source"));
    }

    @Test
    public void initialVisitorStitchAfterRecreationRotatesPersistedSession()
            throws Exception {
        OursPrivacyAPI first = newApi();
        first.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        first.onForeground();
        String oldSid = queuedEvent("$mobile_first_open")
                .getJSONObject("defaultProperties").getString("sid");
        first.shutdownForTests();

        OursPrivacyAPI stitched = newApi();
        stitched.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .visitorId("new-visitor").build());
        stitched.track("appointment_booked");
        stitched.flush();
        assertTrue(stitched.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        JSONObject booking = find(mNetwork.bodyAt(0).getJSONArray("data"),
                "appointment_booked");
        assertEquals("new-visitor", booking.getString("visitor_id"));
        assertNotEquals(oldSid, booking.getJSONObject("defaultProperties")
                .getString("sid"));
    }

    @Test
    public void deferredFirstOpenRetriesOnFlushWithCapturedIdentityAndTime()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicBoolean failQueueCommit = new AtomicBoolean(true);
        OursPrivacyAPI op = newApi(clock, preferencesWithQueueCommitFailure(failQueueCommit));
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        op.onForeground();
        JSONObject pending = mobileState().getJSONArray("pending_facts").getJSONObject(0);
        String id = pending.getString("id");
        long occurredAt = pending.getLong("occurred_at");
        failQueueCommit.set(false);
        clock.wall -= 10 * 60_000;
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedEventCount("$mobile_first_open"));
        assertFalse(mobileState().getBoolean("first_open_accepted"));
        mNetwork.reset();

        clock.advance(6 * 60_000);
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(1, mNetwork.callCount());
        JSONObject accepted = find(mNetwork.bodyAt(0).getJSONArray("data"),
                "$mobile_first_open");
        assertEquals(id, accepted.getString("distinct_id"));
        assertEquals(MobileSession.utc(occurredAt), accepted
                .getJSONObject("defaultProperties").getString("mobile_occurred_at"));
    }

    @Test
    public void delayedVisitorWritePreservesBothForegroundEngagementSegments()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicBoolean delayIdentity = new AtomicBoolean();
        OursPrivacyAPI op = newApi(clock, preferencesWithIdentityDelay(clock, delayIdentity));
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        op.onForeground();
        String oldVisitor = op.getVisitorId();
        clock.advance(3_000);
        delayIdentity.set(true);
        op.setVisitorId("stitched");
        clock.advance(2_000);
        op.onBackground();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = mNetwork.bodyAt(0).getJSONArray("data");
        assertEquals(1, count(data, "$mobile_app_open"));
        assertEquals(2, count(data, "$mobile_session_engagement"));
        JSONObject firstEngagement = nth(data, "$mobile_session_engagement", 0);
        JSONObject secondEngagement = nth(data, "$mobile_session_engagement", 1);
        assertEquals(3_000, firstEngagement.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));
        assertEquals(7_000, secondEngagement.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));
        assertEquals(oldVisitor, firstEngagement.getString("visitor_id"));
        assertEquals("stitched", secondEngagement.getString("visitor_id"));
        assertNotEquals(firstEngagement.getJSONObject("defaultProperties").getString("sid"),
                secondEngagement.getJSONObject("defaultProperties").getString("sid"));
    }

    @Test
    public void duplicateActivityResumesAndPausesDoNotDuplicateCanonicalBoundaries()
            throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        OursPrivacyActivityLifecycleCallbacks callbacks =
                new OursPrivacyActivityLifecycleCallbacks(op, OPConfig.getInstance(mContext));
        callbacks.onActivityResumed(null);
        callbacks.onActivityResumed(null);
        clock.advance(10_000);
        callbacks.onActivityPaused(null);
        callbacks.onActivityPaused(null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY + 1));
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = mNetwork.bodyAt(0).getJSONArray("data");
        assertEquals(1, count(data, "$mobile_first_open"));
        assertEquals(1, count(data, "$mobile_app_open"));
        assertEquals(1, count(data, "$mobile_session_start"));
        assertEquals(1, count(data, "$mobile_session_engagement"));
    }

    @Test
    public void canonicalCallbacksKeepEnabledLegacyEventNames() throws Exception {
        PackageInfo packageInfo = Shadows.shadowOf(mContext.getPackageManager())
                .getInternalMutablePackageInfo(mContext.getPackageName());
        if (packageInfo.applicationInfo.metaData == null) {
            packageInfo.applicationInfo.metaData = new Bundle();
        }
        packageInfo.applicationInfo.metaData.putBoolean(
                "com.oursprivacy.android.Config.DisableAppOpenEvent", false);
        packageInfo.applicationInfo.metaData.putInt(
                "com.oursprivacy.android.Config.MinimumSessionDuration", 0);
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        OursPrivacyActivityLifecycleCallbacks callbacks =
                new OursPrivacyActivityLifecycleCallbacks(op, OPConfig.getInstance(mContext));
        callbacks.onActivityResumed(null);
        clock.advance(10_000);
        callbacks.onActivityPaused(null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY + 1));
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = mNetwork.bodyAt(0).getJSONArray("data");
        assertEquals(1, count(data, AutomaticEvents.FIRST_OPEN));
        assertEquals(1, count(data, "$app_open"));
        assertEquals(1, count(data, AutomaticEvents.SESSION));
        assertEquals(1, count(data, "$mobile_first_open"));
        assertEquals(1, count(data, "$mobile_session_engagement"));
    }

    @Test
    public void backgroundEngagementStopsAtPauseBeforeDebounce() throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        OursPrivacyActivityLifecycleCallbacks callbacks =
                new OursPrivacyActivityLifecycleCallbacks(op, OPConfig.getInstance(mContext));
        callbacks.onActivityResumed(null);
        clock.advance(10_000);
        callbacks.onActivityPaused(null);
        clock.advance(OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY + 1));
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONObject engagement = find(allCapturedData(), "$mobile_session_engagement");
        assertEquals(10_000, engagement.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));
    }

    @Test
    public void legacySessionCannotShiftCanonicalPauseTimeOrTimeout() throws Exception {
        PackageInfo packageInfo = Shadows.shadowOf(mContext.getPackageManager())
                .getInternalMutablePackageInfo(mContext.getPackageName());
        if (packageInfo.applicationInfo.metaData == null) {
            packageInfo.applicationInfo.metaData = new Bundle();
        }
        packageInfo.applicationInfo.metaData.putInt(
                "com.oursprivacy.android.Config.MinimumSessionDuration", 0);
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        OursPrivacyActivityLifecycleCallbacks callbacks =
                new OursPrivacyActivityLifecycleCallbacks(op, OPConfig.getInstance(mContext));
        callbacks.onActivityResumed(null);
        clock.advance(10_000);
        long pausedAt = clock.wall;
        callbacks.onActivityPaused(null);
        clock.advance(OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY + 1));
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray firstData = allCapturedData();
        String initialSid = find(firstData, "$mobile_session_start")
                .getJSONObject("defaultProperties").getString("sid");
        assertEquals(1, count(firstData, AutomaticEvents.SESSION));
        JSONObject engagement = find(firstData, "$mobile_session_engagement");
        assertEquals(10_000, engagement.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));
        assertEquals(MobileSession.utc(pausedAt), engagement
                .getJSONObject("defaultProperties").getString("mobile_occurred_at"));

        clock.advance(MobileSession.SESSION_TIMEOUT_MS
                - OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY);
        callbacks.onActivityResumed(null);
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        JSONArray data = allCapturedData();
        assertEquals(2, count(data, "$mobile_session_start"));
        assertNotEquals(initialSid, nth(data, "$mobile_session_start", 1)
                .getJSONObject("defaultProperties").getString("sid"));
    }

    @Test
    public void manualTrackDuringPauseDebounceCannotShiftCanonicalPauseBoundary()
            throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        OursPrivacyActivityLifecycleCallbacks callbacks =
                new OursPrivacyActivityLifecycleCallbacks(op, OPConfig.getInstance(mContext));
        callbacks.onActivityResumed(null);
        clock.advance(10_000);
        long pausedAt = clock.wall;
        callbacks.onActivityPaused(null);
        clock.advance(250);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        op.track("appointment_booked", jsonOf("appointment_id", "booking-during-pause"));
        clock.advance(250);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(251));
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray firstData = allCapturedData();
        String initialSid = find(firstData, "$mobile_session_start")
                .getJSONObject("defaultProperties").getString("sid");
        JSONObject booking = find(firstData, "appointment_booked");
        assertEquals(MobileSession.utc(pausedAt + 250), booking
                .getJSONObject("defaultProperties").getString("mobile_occurred_at"));
        JSONObject engagement = find(firstData, "$mobile_session_engagement");
        assertEquals(10_000, engagement.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));
        assertEquals(MobileSession.utc(pausedAt), engagement
                .getJSONObject("defaultProperties").getString("mobile_occurred_at"));

        clock.advance(MobileSession.SESSION_TIMEOUT_MS
                - OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY);
        callbacks.onActivityResumed(null);
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        JSONArray data = allCapturedData();
        assertEquals(2, count(data, "$mobile_session_start"));
        assertNotEquals(initialSid, nth(data, "$mobile_session_start", 1)
                .getJSONObject("defaultProperties").getString("sid"));

        clock.advance(2_000);
        callbacks.onActivityPaused(null);
        clock.advance(OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                OursPrivacyActivityLifecycleCallbacks.CHECK_DELAY + 1));
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(2_000, nth(allCapturedData(), "$mobile_session_engagement", 1)
                .getJSONObject("eventProperties").getLong("engagement_duration_ms"));
    }

    @Test
    public void foregroundCheckpointEmitsEngagementWithoutBackground() throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        OursPrivacyActivityLifecycleCallbacks callbacks =
                new OursPrivacyActivityLifecycleCallbacks(op, OPConfig.getInstance(mContext));
        callbacks.onActivityResumed(null);
        clock.advance(10_000);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_001));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        JSONObject engagement = find(data, "$mobile_session_engagement");
        assertEquals(10_000, engagement.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));
        assertEquals(0, count(data, "$mobile_session_end"));
    }

    @Test
    public void activitySwitchesDoNotRestartForegroundCheckpointDeadline()
            throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        OursPrivacyActivityLifecycleCallbacks callbacks =
                new OursPrivacyActivityLifecycleCallbacks(op, OPConfig.getInstance(mContext));
        callbacks.onActivityResumed(null);
        clock.advance(4_000);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000));
        callbacks.onActivityPaused(null);
        clock.advance(100);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        callbacks.onActivityResumed(null);
        clock.advance(4_000);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000));
        callbacks.onActivityPaused(null);
        clock.advance(100);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        callbacks.onActivityResumed(null);
        clock.advance(1_800);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_800));
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        assertEquals(1, count(data, "$mobile_session_engagement"));
        assertEquals(10_000, find(data, "$mobile_session_engagement")
                .getJSONObject("eventProperties").getLong("engagement_duration_ms"));
        assertEquals(1, count(data, "$mobile_app_open"));
    }

    @Test
    public void optOutClearsCanonicalQueueAndOptInKeepsFirstOpenConsumed()
            throws Exception {
        FakeClock clock = new FakeClock();
        OursPrivacyAPI op = newApi(clock, preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        op.onForeground();
        String oldSid = queuedEvent("$mobile_first_open")
                .getJSONObject("defaultProperties").getString("sid");
        op.optOutTracking();
        assertEquals(0, queuedEventCount("$mobile_first_open"));
        op.track("appointment_booked");
        op.onForeground();
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, mNetwork.callCount());

        op.optInTracking();
        op.onForeground();
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        JSONArray data = mNetwork.bodyAt(0).getJSONArray("data");
        assertEquals(0, count(data, "$mobile_first_open"));
        assertEquals(1, count(data, "$mobile_app_open"));
        assertNotEquals(oldSid, find(data, "$mobile_app_open")
                .getJSONObject("defaultProperties").getString("sid"));
    }

    @Test
    public void resetDuringForegroundRetainsNewSessionAndPriorVisitorFacts()
            throws Exception {
        FakeClock clock = new FakeClock();
        AtomicBoolean delayIdentity = new AtomicBoolean();
        OursPrivacyAPI op = newApi(clock, preferencesWithIdentityDelay(clock, delayIdentity));
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true).build());
        op.onForeground();
        String oldVisitor = op.getVisitorId();
        String oldSid = queuedEvent("$mobile_first_open")
                .getJSONObject("defaultProperties").getString("sid");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        clock.advance(3_000);
        delayIdentity.set(true);
        op.reset();
        String newVisitor = op.getVisitorId();
        clock.advance(2_000);
        op.onBackground();
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        JSONArray data = allCapturedData();
        JSONObject prior = nth(data, "$mobile_session_engagement", 0);
        JSONObject after = nth(data, "$mobile_session_engagement", 1);
        assertNotEquals(oldVisitor, newVisitor);
        assertEquals(oldVisitor, prior.getString("visitor_id"));
        assertEquals(oldSid, prior.getJSONObject("defaultProperties").getString("sid"));
        assertEquals(3_000, prior.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));
        assertEquals(newVisitor, after.getString("visitor_id"));
        assertNotEquals(oldSid, after.getJSONObject("defaultProperties").getString("sid"));
        assertEquals(7_000, after.getJSONObject("eventProperties")
                .getLong("engagement_duration_ms"));
        assertEquals(1, count(data, "$mobile_first_open"));
    }

    private JSONArray allCapturedData() throws Exception {
        JSONArray all = new JSONArray();
        for (String body : mNetwork.bodies()) {
            JSONArray batch = new JSONObject(body).getJSONArray("data");
            for (int i = 0; i < batch.length(); i++) all.put(batch.getJSONObject(i));
        }
        return all;
    }

    private SharedPreferences preferences() {
        return mContext.getSharedPreferences("com.oursprivacy.android.OursPrivacy",
                Context.MODE_PRIVATE);
    }

    private JSONObject mobileState() throws Exception {
        for (String key : preferences().getAll().keySet()) {
            if (key.startsWith("mobile_session_")) {
                return new JSONObject(preferences().getString(key, null));
            }
        }
        throw new AssertionError("missing mobile session");
    }

    private int queuedEventCount(String eventName) throws Exception {
        int count = 0;
        JSONArray queue = new JSONArray(preferences().getString("event_queue", "[]"));
        for (int i = 0; i < queue.length(); i++) {
            if (eventName.equals(queue.getJSONObject(i).getString("event"))) count++;
        }
        return count;
    }

    private JSONObject queuedEvent(String eventName) throws Exception {
        return find(new JSONArray(preferences().getString("event_queue", "[]")), eventName);
    }

    @Test
    public void mixedIndexedResponseReportsRejectedIdOnceAndLeavesLaterEventQueued()
            throws Exception {
        List<String[]> rejections = new ArrayList<>();
        AtomicReference<String> queueAtCallback = new AtomicReference<>();
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .onIngestRejected((distinctId, code) -> {
                    rejections.add(new String[]{distinctId, code});
                    queueAtCallback.set(preferences().getString("event_queue", "[]"));
                })
                .build());
        op.setFlushBatchSize(2);
        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"server-visitor\","
                + "\"accepted\":1,\"rejected\":[{\"index\":0,"
                + "\"code\":\"mobile_occurred_at_future\"}]}");
        mNetwork.failWith(new IOException("offline"));
        op.track("bad_event");
        op.track("good_event");
        op.track("later_event");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        String rejectedId = mNetwork.bodyAt(0).getJSONArray("data")
                .getJSONObject(0).getString("distinct_id");
        assertEquals(1, rejections.size());
        assertEquals(rejectedId, rejections.get(0)[0]);
        assertEquals("mobile_occurred_at_future", rejections.get(0)[1]);
        assertEquals(1, new JSONArray(queueAtCallback.get()).length());
        assertEquals(1, new JSONArray(preferences().getString("event_queue", "[]")).length());
        assertEquals("later_event", queuedEvent("later_event").getString("event"));
        assertEquals(2, mNetwork.callCount());
    }

    @Test
    public void allRejectedIndexedResponseDrainsBatchWithoutListener() throws Exception {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, null);
        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"server-visitor\","
                + "\"accepted\":0,\"rejected\":["
                + "{\"index\":0,\"code\":\"mobile_occurred_at_future\"},"
                + "{\"index\":1,\"code\":\"mobile_session_start_invalid\"}]}");
        op.track("bad_one");
        op.track("bad_two");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedCount());
        assertEquals(1, mNetwork.callCount());
    }

    @Test
    public void invalidIndexedAndLegacyResponsesKeepTheBatchForRetry() throws Exception {
        String[] invalid = {
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":1}",
                "{\"success\":true,\"visitor_id\":\"v\",\"rejected\":[]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":0,\"rejected\":[]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":1,"
                        + "\"rejected\":[{\"index\":0,\"code\":\"code\"}]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":0,"
                        + "\"rejected\":[{\"index\":0,\"code\":\"code\"},"
                        + "{\"index\":0,\"code\":\"code\"}]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":0,"
                        + "\"rejected\":[{\"index\":1,\"code\":\"code\"}]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":\"0\","
                        + "\"rejected\":[{\"index\":0,\"code\":\"code\"}]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":0,"
                        + "\"rejected\":[{\"index\":\"0\",\"code\":\"code\"}]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":0,"
                        + "\"rejected\":[{\"index\":-1,\"code\":\"code\"}]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":0,"
                        + "\"rejected\":[{\"index\":0,\"code\":null}]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":0,"
                        + "\"rejected\":[{\"index\":0,\"code\":\"\"}]}",
                "{\"success\":true,\"visitor_id\":\"v\",\"accepted\":0,\"rejected\":null}",
                "{\"success\":false,\"visitor_id\":\"v\",\"accepted\":1,\"rejected\":[]}",
                "{\"success\":true}",
                "{\"success\":true,\"visitor_id\":null}",
                "{\"success\":true,\"visitor_id\":1}",
                "{\"success\":false,\"visitor_id\":\"v\"}",
                "not json"
        };
        List<String> rejections = new ArrayList<>();
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .onIngestRejected((id, code) -> rejections.add(code)).build());
        op.track("event");
        for (String response : invalid) {
            mNetwork.respondWith(response);
            op.flush();
            assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
            assertEquals(response, 1, queuedCount());
            assertEquals(response, 0, rejections.size());
        }
        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\","
                + "\"accepted\":1,\"rejected\":[]}");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedCount());
    }

    @Test
    public void completeLegacyResponseAcknowledgesBeforeIndexedMode() throws Exception {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, null);
        op.track("legacy_event");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedCount());
    }

    @Test
    public void indexedModePersistsAcrossRestartOnlyForItsToken() throws Exception {
        OursPrivacyAPI first = newApi();
        first.initialize(TOKEN, null);
        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\","
                + "\"accepted\":1,\"rejected\":[]}");
        first.track("indexed_event");
        first.flush();
        assertTrue(first.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedCount());
        first.shutdownForTests();

        OursPrivacyAPI restarted = newApi();
        restarted.initialize(TOKEN, null);
        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\"}");
        restarted.track("retry_event");
        restarted.flush();
        assertTrue(restarted.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(1, queuedCount());
        assertEquals("retry_event", queuedEvent("retry_event").getString("event"));

        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\","
                + "\"accepted\":1,\"rejected\":[]}");
        restarted.flush();
        assertTrue(restarted.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedCount());
        restarted.shutdownForTests();

        OursPrivacyAPI otherToken = newApi();
        otherToken.initialize("different-token", null);
        otherToken.track("legacy_other_token");
        otherToken.flush();
        assertTrue(otherToken.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedCount());
    }

    @Test
    public void failedAcknowledgmentCommitKeepsQueueAndSuppressesCallback() throws Exception {
        AtomicBoolean failCommit = new AtomicBoolean(false);
        SharedPreferences wrapped = preferencesWithAcknowledgmentCommitFailure(failCommit);
        List<String> rejections = new ArrayList<>();
        OursPrivacyAPI op = newApi(new FakeClock(), wrapped);
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .onIngestRejected((id, code) -> rejections.add(code)).build());
        op.track("bad_event");
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\","
                + "\"accepted\":0,\"rejected\":[{\"index\":0,\"code\":\"bad_code\"}]}");
        failCommit.set(true);
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(1, queuedCount());
        assertTrue(rejections.isEmpty());

        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\"}");
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedCount());
        assertTrue(rejections.isEmpty());
    }

    @Test
    public void resetDuringIndexedPostCannotAcknowledgeSameLengthNewSessionFacts()
            throws Exception {
        List<String> rejections = new ArrayList<>();
        OursPrivacyAPI op = newApi(new FakeClock(), preferences());
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .trackAutomaticEvents(true)
                .onIngestRejected((id, code) -> rejections.add(code))
                .build());
        op.onForeground();
        op.flush();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        assertEquals(0, queuedCount());
        mNetwork.reset();
        op.setFlushBatchSize(2);

        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\",\"accepted\":1,"
                + "\"rejected\":[{\"index\":0,\"code\":\"old_rejected\"}]}");
        mNetwork.failWith(new IOException("hold replacement"));
        mNetwork.blockNextRequest(captured, release);
        try {
            op.track("old_one");
            op.track("old_two");
            op.flush();
            assertTrue("old POST captured", captured.await(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(2, mNetwork.bodyAt(0).getJSONArray("data").length());

            op.reset();
            JSONArray replacement = new JSONArray(preferences().getString("event_queue", "[]"));
            assertEquals(2, replacement.length());
            assertEquals(1, count(replacement, "$mobile_session_start"));

            release.countDown();
            assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
            assertEquals(2, queuedCount());
            assertEquals(1, count(new JSONArray(preferences()
                    .getString("event_queue", "[]")), "$mobile_session_start"));
            assertTrue(rejections.isEmpty());
            assertEquals(2, mNetwork.callCount());

            mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\"}");
            op.flush();
            assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
            assertEquals(0, queuedCount());
            assertEquals(3, mNetwork.callCount());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void resetDuringPostCannotAcknowledgeReusedCallerDistinctId() throws Exception {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, null);
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\","
                + "\"accepted\":1,\"rejected\":[]}");
        mNetwork.failWith(new IOException("hold replacement"));
        mNetwork.blockNextRequest(captured, release);
        try {
            op.track("same_event", jsonOf("$distinct_id", "caller-id"));
            op.flush();
            assertTrue(captured.await(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals("caller-id", mNetwork.bodyAt(0).getJSONArray("data")
                    .getJSONObject(0).getString("distinct_id"));

            op.reset();
            op.track("same_event", jsonOf("$distinct_id", "caller-id"));
            assertEquals(1, queuedCount());
            assertEquals("caller-id", queuedEvent("same_event").getString("distinct_id"));

            release.countDown();
            assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
            assertEquals(1, queuedCount());
            assertEquals(2, mNetwork.callCount());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void appendedEventDoesNotInvalidateSentQueueHead() throws Exception {
        OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, null);
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        mNetwork.respondWith("{\"success\":true,\"visitor_id\":\"v\","
                + "\"accepted\":1,\"rejected\":[]}");
        mNetwork.failWith(new IOException("hold appended event"));
        mNetwork.blockNextRequest(captured, release);
        try {
            op.track("sent_event");
            op.flush();
            assertTrue(captured.await(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS));

            op.track("appended_event");
            release.countDown();
            assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
            assertEquals(1, queuedCount());
            assertEquals("appended_event", queuedEvent("appended_event").getString("event"));
            assertEquals(2, mNetwork.callCount());
        } finally {
            release.countDown();
        }
    }

    private int queuedCount() throws Exception {
        return new JSONArray(preferences().getString("event_queue", "[]")).length();
    }

    private SharedPreferences preferencesWithAcknowledgmentCommitFailure(AtomicBoolean fail) {
        SharedPreferences wrapped = spy(preferences());
        doAnswer(invocation -> {
            SharedPreferences.Editor delegate = preferences().edit();
            AtomicBoolean acknowledgmentEdit = new AtomicBoolean();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if ("putBoolean".equals(method.getName())
                                && ((String) args[0]).startsWith("indexed_ingest_")) {
                            acknowledgmentEdit.set(true);
                        }
                        Object result = method.invoke(delegate, args);
                        if ("commit".equals(method.getName()) && acknowledgmentEdit.get()
                                && fail.compareAndSet(true, false)) {
                            return false;
                        }
                        return result == delegate ? proxy : result;
                    });
        }).when(wrapped).edit();
        return wrapped;
    }

    private SharedPreferences preferencesWithQueueCommitFailure(AtomicBoolean fail) {
        SharedPreferences wrapped = spy(preferences());
        doAnswer(invocation -> {
            SharedPreferences.Editor delegate = preferences().edit();
            AtomicBoolean queueEdit = new AtomicBoolean();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if ("putString".equals(method.getName()) && "event_queue".equals(args[0])) {
                            queueEdit.set(true);
                        }
                        Object result = method.invoke(delegate, args);
                        if ("commit".equals(method.getName()) && queueEdit.get() && fail.get()) {
                            return false;
                        }
                        return result == delegate ? proxy : result;
                    });
        }).when(wrapped).edit();
        return wrapped;
    }

    private SharedPreferences preferencesWithIdentityDelay(FakeClock clock,
                                                              AtomicBoolean delay) {
        SharedPreferences wrapped = spy(preferences());
        doAnswer(invocation -> {
            SharedPreferences.Editor delegate = preferences().edit();
            AtomicBoolean identityEdit = new AtomicBoolean();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if ("putString".equals(method.getName()) && "visitor_id".equals(args[0])) {
                            identityEdit.set(true);
                        }
                        if ("apply".equals(method.getName()) && identityEdit.get()
                                && delay.getAndSet(false)) {
                            clock.advance(5_000);
                        }
                        Object result = method.invoke(delegate, args);
                        return result == delegate ? proxy : result;
                    });
        }).when(wrapped).edit();
        return wrapped;
    }

    private static JSONObject nth(JSONArray data, String event, int index) throws Exception {
        int seen = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject value = data.getJSONObject(i);
            if (event.equals(value.getString("event")) && seen++ == index) return value;
        }
        throw new AssertionError("missing " + event + " #" + index);
    }

    private static final class FakeClock implements MobileSession.Clock {
        long wall = 1_780_000_000_000L;
        long elapsed = 100_000L;
        @Override public long wallMillis() { return wall; }
        @Override public long elapsedMillis() { return elapsed; }
        void advance(long millis) { wall += millis; elapsed += millis; }
    }

    private static int count(JSONArray data, String name) throws Exception {
        int found = 0;
        for (int i = 0; i < data.length(); i++) {
            if (name.equals(data.getJSONObject(i).getString("event"))) found++;
        }
        return found;
    }

    private static int indexOf(JSONArray data, JSONObject item) throws Exception {
        for (int i = 0; i < data.length(); i++) {
            if (item.getString("distinct_id")
                    .equals(data.getJSONObject(i).getString("distinct_id"))) return i;
        }
        throw new AssertionError("missing item");
    }

    private static JSONObject find(JSONArray data, String name) throws Exception {
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            if (name.equals(item.getString("event"))) return item;
        }
        throw new AssertionError("missing " + name);
    }

    @Test
    public void track_track_flush_emitsOneEnvelopeWithBothEvents() throws Exception {
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
        final OursPrivacyAPI op = newApi();
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
    public void backToBackFlushes_doNotDropTheSecondFlush() throws Exception {
        // Regression: scheduleFlush() used to coalesce against the same message
        // code as user-initiated flushes, so when the worker drained an explicit
        // FLUSH it would removeMessages(FLUSH) — silently killing the next
        // user-initiated FLUSH already queued behind it. Reset() exercises this
        // pattern internally (flushNow before persistence wipe + caller's flush).
        // Park the worker before any of the messages drain to make the race
        // deterministic.
        final OursPrivacyAPI op = newApi();
        op.initialize(TOKEN, null);
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        final java.util.concurrent.CountDownLatch parked = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        op.parkWorkerForTests(parked, release);
        assertTrue("worker parked", parked.await(IDLE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS));

        op.flush();
        op.track("between_flushes");
        op.flush();

        release.countDown();
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));

        assertEquals("second FLUSH must survive the first FLUSH's reschedule",
                1, mNetwork.callCount());
        assertEquals("between_flushes",
                mNetwork.bodyAt(0).getJSONArray("data").getJSONObject(0).getString("event"));
    }

    @Test
    public void reset_regeneratesVisitorIdAndClearsDefaults() throws Exception {
        final OursPrivacyAPI op = newApi();
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
