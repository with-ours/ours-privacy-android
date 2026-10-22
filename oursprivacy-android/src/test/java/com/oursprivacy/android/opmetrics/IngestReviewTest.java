package com.oursprivacy.android.opmetrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;

import com.oursprivacy.android.util.RemoteService;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
public class IngestReviewTest {
    private static final String TOKEN = "ingest-review-token";
    private static final long IDLE_TIMEOUT_MS = 5_000;

    private Context context;
    private CapturingRemoteService network;
    private final List<OursPrivacyAPI> instances = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PackageInfo packageInfo = Shadows.shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName());
        if (packageInfo.applicationInfo.metaData == null) {
            packageInfo.applicationInfo.metaData = new Bundle();
        }
        packageInfo.applicationInfo.metaData.putInt(
                "com.oursprivacy.android.Config.FlushInterval", 3_600_000);
        preferences().edit().clear().commit();
        network = new CapturingRemoteService();
        AnalyticsMessages.sTestRemoteService = network;
    }

    @After
    public void tearDown() {
        try {
            for (OursPrivacyAPI op : instances) op.shutdownForTests();
        } finally {
            instances.clear();
            AnalyticsMessages.sTestRemoteService = null;
            AnalyticsMessages.sTestBatchReady = null;
        }
    }

    @Test
    public void nonemptyNonobjectResponsesRetainEachHeadFourTimesThenRejectDurably()
            throws Exception {
        for (String response : List.of("<html>Please sign in to Wi-Fi</html>",
                "{\"success\":true", "[]", "\"accepted\"", "null", "1", "true", " \n\t")) {
            preferences().edit().clear().commit();
            network.reset();
            List<String> rejectedIds = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            List<String> queuesAtCallback = new ArrayList<>();
            OursPrivacyAPI op = newApi((id, code) -> {
                rejectedIds.add(id);
                codes.add(code);
                queuesAtCallback.add(preferences().getString("event_queue", "[]"));
            });
            op.setFlushBatchSize(1);
            op.track("offline");
            op.track("following");
            assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
            String original = preferences().getString("event_queue", "[]");
            JSONArray originalQueue = new JSONArray(original);
            String headId = originalQueue.getJSONObject(0).getString("distinct_id");
            String followingId = originalQueue.getJSONObject(1).getString("distinct_id");

            for (int attempt = 1; attempt <= 4; attempt++) {
                network.respondWith(response);
                flush(op);
                assertEquals(response + " attempt " + attempt, original,
                        preferences().getString("event_queue", "[]"));
                assertTrue(rejectedIds.isEmpty());
            }

            network.respondWith(response);
            network.respondWith(response);
            flush(op);
            assertEquals(List.of(headId), rejectedIds);
            assertEquals(List.of("invalid_acknowledgment"), codes);
            assertEquals(List.of("following"), eventNames(new JSONArray(queuesAtCallback.get(0))));
            assertEquals(List.of("following"), eventNames(queue()));
            assertEquals(6, network.callCount());
            assertEquals(headId, network.bodyAt(4).getJSONArray("data")
                    .getJSONObject(0).getString("distinct_id"));
            assertEquals(followingId, network.bodyAt(5).getJSONArray("data")
                    .getJSONObject(0).getString("distinct_id"));

            for (int attempt = 2; attempt <= 4; attempt++) {
                network.respondWith(response);
                flush(op);
                assertEquals(List.of("following"), eventNames(queue()));
                assertEquals(List.of(headId), rejectedIds);
            }
            network.respondWith(response);
            flush(op);
            assertEquals(List.of(headId, followingId), rejectedIds);
            assertEquals(List.of("invalid_acknowledgment", "invalid_acknowledgment"), codes);
            assertEquals(0, new JSONArray(queuesAtCallback.get(1)).length());
            assertEquals(0, queue().length());
            flush(op);
            assertEquals(10, network.callCount());
            op.shutdownForTests();
            instances.remove(op);
        }
    }

    @Test
    public void emptyAndUnknownObjectResponsesAcknowledgeAfterIndexedSuccess() throws Exception {
        List<String> codes = new ArrayList<>();
        OursPrivacyAPI op = newApi((id, code) -> codes.add(code));
        for (String response : List.of(
                "{\"success\":true,\"accepted\":1,\"rejected\":[]}",
                "", "{}", "{\"proxy_request_id\":\"request\"}",
                "{\"success\":true}", "{\"success\":true,\"request_id\":\"request\"}")) {
            op.track("compatible_success");
            network.respondWith(response);
            flush(op);
            assertEquals(response, 0, queue().length());
            assertTrue(codes.isEmpty());
        }
        assertEquals(6, network.callCount());
    }

    @Test
    public void repeatedHttp403RetainsBacklogBeyondMalformedCapAndUploadsAfterRecovery()
            throws Exception {
        List<String> codes = new ArrayList<>();
        OursPrivacyAPI op = newApi((id, code) -> codes.add(code));
        op.setFlushBatchSize(1);
        op.track("offline");
        op.track("following");
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        String original = preferences().getString("event_queue", "[]");
        String headId = queue().getJSONObject(0).getString("distinct_id");

        for (int attempt = 1; attempt <= 6; attempt++) {
            network.failWith(new RemoteService.HttpStatusException(403));
            flush(op);
            assertEquals("403 attempt " + attempt, original,
                    preferences().getString("event_queue", "[]"));
            assertEquals(headId, network.bodyAt(attempt - 1).getJSONArray("data")
                    .getJSONObject(0).getString("distinct_id"));
            assertTrue(codes.isEmpty());
        }
        op.shutdownForTests();
        instances.remove(op);

        OursPrivacyAPI restarted = newApi((id, code) -> codes.add(code));
        restarted.setFlushBatchSize(1);
        assertEquals(List.of("offline", "following"), eventNames(queue()));
        network.respondWith("{\"proxy_request_id\":\"recovered\"}");
        network.respondWith("");
        flush(restarted);
        assertEquals(0, queue().length());
        assertEquals(8, network.callCount());
        assertEquals("offline", network.bodyAt(6).getJSONArray("data")
                .getJSONObject(0).getString("event"));
        assertEquals("following", network.bodyAt(7).getJSONArray("data")
                .getJSONObject(0).getString("event"));
        assertTrue(codes.isEmpty());
    }

    @Test
    public void malformedCapCommitFailureRetainsRowsAndSuppressesRejectionUntilDurableRemoval()
            throws Exception {
        AtomicBoolean failRemoval = new AtomicBoolean();
        List<String> rejectedIds = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        List<String> queuesAtCallback = new ArrayList<>();
        OursPrivacyAPI op = newApi(preferencesWithFailedQueueCommit(failRemoval), (id, code) -> {
            rejectedIds.add(id);
            codes.add(code);
            queuesAtCallback.add(preferences().getString("event_queue", "[]"));
        });
        op.track("malformed_ack");
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        String original = preferences().getString("event_queue", "[]");
        String headId = queue().getJSONObject(0).getString("distinct_id");
        for (int attempt = 1; attempt <= 4; attempt++) {
            network.respondWith("<html>Sign in</html>");
            flush(op);
            assertEquals(original, preferences().getString("event_queue", "[]"));
        }

        failRemoval.set(true);
        network.respondWith("<html>Sign in</html>");
        flush(op);
        assertEquals(original, preferences().getString("event_queue", "[]"));
        assertTrue(rejectedIds.isEmpty());
        assertTrue(codes.isEmpty());

        network.respondWith("<html>Sign in</html>");
        flush(op);
        assertEquals(0, queue().length());
        assertEquals(List.of(headId), rejectedIds);
        assertEquals(List.of("invalid_acknowledgment"), codes);
        assertEquals(0, new JSONArray(queuesAtCallback.get(0)).length());
        op.shutdownForTests();
        instances.remove(op);
        OursPrivacyAPI restarted = newApi((id, code) -> codes.add(code));
        flush(restarted);
        assertEquals(6, network.callCount());
        assertEquals(List.of("invalid_acknowledgment"), codes);
    }

    @Test
    public void malformedCapCallbackOptOutSuppressesRemainingRejectionsAndUploads()
            throws Exception {
        AtomicReference<OursPrivacyAPI> api = new AtomicReference<>();
        List<String> rejectedIds = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        List<String> queuesAtCallback = new ArrayList<>();
        OursPrivacyAPI op = newApi((id, code) -> {
            rejectedIds.add(id);
            codes.add(code);
            queuesAtCallback.add(preferences().getString("event_queue", "[]"));
            api.get().optOutTracking();
        });
        api.set(op);
        op.track("one");
        op.track("two");
        assertTrue(op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
        String headId = queue().getJSONObject(0).getString("distinct_id");
        for (int attempt = 1; attempt <= 4; attempt++) {
            network.respondWith("[]");
            flush(op);
            assertEquals(2, queue().length());
            assertTrue(codes.isEmpty());
        }
        network.respondWith("[]");
        flush(op);
        assertEquals(List.of(headId), rejectedIds);
        assertEquals(List.of("invalid_acknowledgment"), codes);
        assertEquals(0, new JSONArray(queuesAtCallback.get(0)).length());
        assertTrue(op.hasOptedOutTracking());
        op.track("after_opt_out");
        flush(op);
        assertEquals(0, queue().length());
        assertEquals(5, network.callCount());
    }

    private OursPrivacyAPI newApi(IngestRejectionListener listener) {
        return newApi(preferences(), listener);
    }

    private OursPrivacyAPI newApi(SharedPreferences prefs, IngestRejectionListener listener) {
        OursPrivacyAPI op = new OursPrivacyAPI(
                context, null, CompletableFuture.completedFuture(prefs));
        instances.add(op);
        op.initialize(TOKEN, OursPrivacyInitOptions.builder()
                .onIngestRejected(listener).build());
        return op;
    }

    private SharedPreferences preferencesWithFailedQueueCommit(AtomicBoolean fail) {
        SharedPreferences backing = preferences();
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"edit".equals(method.getName())) {
                        return method.invoke(backing, args);
                    }
                    SharedPreferences.Editor delegate = backing.edit();
                    AtomicBoolean queueEdit = new AtomicBoolean();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class},
                            (editorProxy, editorMethod, editorArgs) -> {
                                if ("putString".equals(editorMethod.getName())
                                        && "event_queue".equals(editorArgs[0])) {
                                    queueEdit.set(true);
                                }
                                if ("commit".equals(editorMethod.getName()) && queueEdit.get()
                                        && fail.compareAndSet(true, false)) {
                                    return false;
                                }
                                Object result = editorMethod.invoke(delegate, editorArgs);
                                return result == delegate ? editorProxy : result;
                            });
                });
    }

    private void flush(OursPrivacyAPI op) {
        op.flush();
        assertTrue("worker drained", op.awaitWorkerIdle(IDLE_TIMEOUT_MS));
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(
                "com.oursprivacy.android.OursPrivacy", Context.MODE_PRIVATE);
    }

    private JSONArray queue() throws Exception {
        return new JSONArray(preferences().getString("event_queue", "[]"));
    }

    private static List<String> eventNames(JSONArray events) throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) {
            names.add(events.getJSONObject(i).getString("event"));
        }
        return names;
    }
}
