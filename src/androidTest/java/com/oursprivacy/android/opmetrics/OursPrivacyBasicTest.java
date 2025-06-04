package com.oursprivacy.android.opmetrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oursprivacy.android.BuildConfig;
import com.oursprivacy.android.util.Base64Coder;
import com.oursprivacy.android.util.HttpService;
import com.oursprivacy.android.util.ProxyServerInteractor;
import com.oursprivacy.android.util.RemoteService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.hamcrest.CoreMatchers.*;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class OursPrivacyBasicTest {

    @Before
    public void setUp() throws Exception {
        mMockPreferences = new TestUtils.EmptyPreferences(InstrumentationRegistry.getInstrumentation().getContext());
        AnalyticsMessages messages = AnalyticsMessages.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null));
        messages.hardKill();
        Thread.sleep(2000);

        try {
            SystemInformation systemInformation = SystemInformation.getInstance(InstrumentationRegistry.getInstrumentation().getContext());

            final StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("&properties=");
            JSONObject properties = new JSONObject();
            properties.putOpt("$android_lib_version", OPConfig.VERSION);
            properties.putOpt("$android_app_version", systemInformation.getAppVersionName());
            properties.putOpt("$android_version", Build.VERSION.RELEASE);
            properties.putOpt("$android_app_release", systemInformation.getAppVersionCode());
            properties.putOpt("$android_device_model", Build.MODEL);
            queryBuilder.append(URLEncoder.encode(properties.toString(), "utf-8"));
            mAppProperties = queryBuilder.toString();
        } catch (Exception e) {}
    } // end of setUp() method definition

    @Test
    public void testVersionsMatch() {
        assertEquals(BuildConfig.OURSPRIVACY_VERSION, OPConfig.VERSION);
    }

    @Test
    public void testGeneratedDistinctId() {
        String fakeToken = UUID.randomUUID().toString();
        OursPrivacyAPI oursprivacy = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, fakeToken);
        String generatedId1 = oursprivacy.getDistinctId();
        assertThat(generatedId1, startsWith("$device:"));
        assertEquals(generatedId1, "$device:" + oursprivacy.getAnonymousId());

        oursprivacy.reset();
        String generatedId2 = oursprivacy.getDistinctId();
        assertThat(generatedId2, startsWith("$device:"));
        assertEquals(generatedId2, "$device:" + oursprivacy.getAnonymousId());
        assertNotEquals(generatedId1, generatedId2);
    }

    @Test
    public void testDeleteDB() {
        Map<String, String> beforeMap = new HashMap<String, String>();
        beforeMap.put("added", "before");
        JSONObject before = new JSONObject(beforeMap);

        Map<String, String> afterMap = new HashMap<String,String>();
        afterMap.put("added", "after");
        JSONObject after = new JSONObject(afterMap);

        OPDbAdapter adapter = new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), "DeleteTestDB", OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null));
        adapter.addJSON(before, "ATOKEN", OPDbAdapter.Table.EVENTS);
        adapter.deleteDB();

        ArrayList<JSONObject> emptyEventsData = adapter.generateDataString(OPDbAdapter.Table.EVENTS, "ATOKEN");
        assertTrue(emptyEventsData.isEmpty());

        adapter.addJSON(after, "ATOKEN", OPDbAdapter.Table.EVENTS);

        try {
            ArrayList<JSONObject> someEventsData = adapter.generateDataString(OPDbAdapter.Table.EVENTS, "ATOKEN");
            JSONArray someEvents = new JSONArray(someEventsData);
            assertEquals(someEvents.length(), 1);
            assertEquals(someEvents.getJSONObject(0).get("added"), "after");
        } catch (JSONException e) {
            fail("Unexpected JSON or lack thereof in MPDbAdapter test");
        }
    }

    @Test
    public void testLooperDestruction() {
        final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();

        final OPDbAdapter explodingDb = new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public int addJSON(JSONObject message, String token, OPDbAdapter.Table table) {
                messages.add(message);
                throw new RuntimeException("BANG!");
            }
        };

        final AnalyticsMessages explodingMessages = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            // This will throw inside of our worker thread.
            @Override
            public OPDbAdapter makeDbAdapter(Context context) {
                return explodingDb;
            }
        };
        OursPrivacyAPI oursprivacy = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "TEST TOKEN testLooperDisaster") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return explodingMessages;
            }
        };

        try {
            oursprivacy.reset();
            assertFalse(explodingMessages.isDead());

            oursprivacy.track("event1", null);
            JSONObject found = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("should found", found);

            Thread.sleep(1000);
            assertTrue(explodingMessages.isDead());

            oursprivacy.track("event2", null);
            JSONObject shouldntFind = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertNull(shouldntFind);
            assertTrue(explodingMessages.isDead());
        } catch (InterruptedException e) {
            fail("Unexpected interruption");
        }
    }

    @Test
    public void testEventOperations() throws JSONException {
        final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();

        final OPDbAdapter eventOperationsAdapter = new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public int addJSON(JSONObject message, String token, OPDbAdapter.Table table) {
                messages.add(message);

                return 1;
            }
        };

        final AnalyticsMessages eventOperationsMessages = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            // This will throw inside of our worker thread.
            @Override
            public OPDbAdapter makeDbAdapter(Context context) {
                return eventOperationsAdapter;
            }
        };

        OursPrivacyAPI oursprivacy = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "Test event operations") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return eventOperationsMessages;
            }
        };

        JSONObject jsonObj1 = new JSONObject();
        JSONObject jsonObj2 = new JSONObject();
        JSONObject jsonObj3 = new JSONObject();
        JSONObject jsonObj4 = new JSONObject();
        JSONObject jsonObj5 = new JSONObject();

        Map<String, Object> mapObj1 = new HashMap<>();
        Map<String, Object> mapObj2 = new HashMap<>();
        Map<String, Object> mapObj3 = new HashMap<>();
        Map<String, Object> mapObj4 = new HashMap<>();
        Map<String, Object> mapObj5 = new HashMap<>();

        jsonObj1.put("TRACK JSON STRING", "TRACK JSON STRING VALUE");
        jsonObj2.put("TRACK JSON INT", 1);
        jsonObj3.put("TRACK JSON STRING ONCE", "TRACK JSON STRING ONCE VALUE");
        jsonObj4.put("TRACK JSON STRING ONCE", "SHOULD NOT SEE ME");
        jsonObj5.put("TRACK JSON NULL", JSONObject.NULL);


        mapObj1.put("TRACK MAP STRING", "TRACK MAP STRING VALUE");
        mapObj2.put("TRACK MAP INT", 1);
        mapObj3.put("TRACK MAP STRING ONCE", "TRACK MAP STRING ONCE VALUE");
        mapObj4.put("TRACK MAP STRING ONCE", "SHOULD NOT SEE ME");
        mapObj5.put("TRACK MAP CUSTOM OBJECT", oursprivacy);

        try {
            JSONObject message;
            JSONObject properties;

            oursprivacy.track("event1", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event1", message.getString("event"));

            oursprivacy.track("event2", jsonObj1);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event2", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(jsonObj1.getString("TRACK JSON STRING"), properties.getString("TRACK JSON STRING"));

            oursprivacy.trackMap("event3", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event3", message.getString("event"));

            oursprivacy.trackMap("event4", mapObj1);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event4", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(mapObj1.get("TRACK MAP STRING"), properties.getString("TRACK MAP STRING"));

            oursprivacy.registerSuperProperties(jsonObj2);
            oursprivacy.registerSuperPropertiesOnce(jsonObj3);
            oursprivacy.registerSuperPropertiesOnce(jsonObj4);
            oursprivacy.registerSuperPropertiesMap(mapObj2);
            oursprivacy.registerSuperPropertiesOnceMap(mapObj3);
            oursprivacy.registerSuperPropertiesOnceMap(mapObj4);

            oursprivacy.track("event5", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event5", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(jsonObj2.getInt("TRACK JSON INT"), properties.getInt("TRACK JSON INT"));
            assertEquals(jsonObj3.getString("TRACK JSON STRING ONCE"), properties.getString("TRACK JSON STRING ONCE"));
            assertEquals(mapObj2.get("TRACK MAP INT"), properties.getInt("TRACK MAP INT"));
            assertEquals(mapObj3.get("TRACK MAP STRING ONCE"), properties.getString("TRACK MAP STRING ONCE"));

            oursprivacy.unregisterSuperProperty("TRACK JSON INT");
            oursprivacy.track("event6", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event6", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertFalse(properties.has("TRACK JSON INT"));

            oursprivacy.clearSuperProperties();
            oursprivacy.track("event7", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event7", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertFalse(properties.has("TRACK JSON STRING ONCE"));

            oursprivacy.track("event8", jsonObj5);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event8", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(jsonObj5.get("TRACK JSON NULL"), properties.get("TRACK JSON NULL"));

            oursprivacy.trackMap("event contains custom object", mapObj5);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event contains custom object", message.getString("event"));
        } catch (InterruptedException e) {
            fail("Unexpected interruption");
        }
    }

    @Test
    public void testIdentifyAfterSet() throws InterruptedException, JSONException {
        String token = "TEST TOKEN testIdentifyAfterSet";
        final List<AnalyticsMessages.OursPrivacyDescription> messages = new ArrayList<AnalyticsMessages.OursPrivacyDescription>();
        final BlockingQueue<JSONObject> anonymousUpdates = new LinkedBlockingQueue();
        final BlockingQueue<JSONObject> peopleUpdates = new LinkedBlockingQueue();

        final OPDbAdapter mockAdapter = new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public int addJSON(JSONObject j, String token, Table table) {
                return super.addJSON(j, token, table);
            }
        };
        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {

            @Override
            protected OPDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }
        };

        OursPrivacyAPI oursprivacy = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, token) {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };

        assertEquals(0L, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$add").getLong("the prop"));
        assertEquals(1, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$append").get("the prop"));
        assertEquals(2, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$set").get("the prop"));
        assertEquals(3L, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$add").getLong("the prop"));
        assertEquals(5, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$append").get("the prop"));
        assertNull(anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));
        assertNull(peopleUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

        String deviceId = oursprivacy.getAnonymousId();
        oursprivacy.identify("Personal Identity", null);

        assertEquals("prop value identified", peopleUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$set").getString("the prop identified"));
        assertNull(peopleUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));
        assertNull(anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

    }

    @Test
    public void testIdentifyAfterSetToAnonymousId() throws InterruptedException, JSONException {
        String token = "TEST TOKEN testIdentifyAfterSet";
        final List<AnalyticsMessages.OursPrivacyDescription> messages = new ArrayList<AnalyticsMessages.OursPrivacyDescription>();
        final BlockingQueue<JSONObject> anonymousUpdates = new LinkedBlockingQueue();
        final BlockingQueue<JSONObject> peopleUpdates = new LinkedBlockingQueue();

        final OPDbAdapter mockAdapter = new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public int addJSON(JSONObject j, String token, Table table) {
                return super.addJSON(j, token, table);
            }
        };
        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {

            @Override
            protected OPDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }
        };

        OursPrivacyAPI oursprivacy = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, token) {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };

        assertEquals(0L, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$add").getLong("the prop"));
        assertEquals(1, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$append").get("the prop"));
        assertEquals(2, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$set").get("the prop"));
        assertEquals(3L, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$add").getLong("the prop"));
        assertEquals(5, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$append").get("the prop"));
        assertNull(anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));
        assertNull(peopleUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

        String deviceId = oursprivacy.getAnonymousId();
        oursprivacy.identify(oursprivacy.getDistinctId(), null);
        assertNull(oursprivacy.getUserId());

        assertEquals("prop value identified", peopleUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$set").getString("the prop identified"));
        assertNull(peopleUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));
        assertNull(anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

    }

    @Test
    public void testIdentifyAndGetDistinctId() {
        OursPrivacyAPI metrics = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "Identify Test Token");

        String generatedId = metrics.getDistinctId();
        assertThat(generatedId, startsWith("$device:"));
        assertEquals(generatedId, "$device:" + metrics.getAnonymousId());

        assertNull(metrics.getUserId());

        metrics.identify("Events Id", null);
        assertEquals("Events Id", metrics.getDistinctId());
        assertEquals("Events Id", metrics.getUserId());
    }

    @Test
    public void testIdentifyToCurrentAnonymousDistinctId() {
        OursPrivacyAPI metrics = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "Identify Test Token");

        String generatedId = metrics.getDistinctId();
        assertThat(generatedId, startsWith("$device:"));
        assertEquals(generatedId, "$device:" + metrics.getAnonymousId());

        assertNull(metrics.getUserId());

        metrics.identify(metrics.getDistinctId(), null);
        assertEquals(generatedId, metrics.getDistinctId());
        assertNull(metrics.getUserId());
    }

    @Test
    public void testIdentifyAndCheckUserIDAndDeviceID() {
        OursPrivacyAPI metrics = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "Identify Test Token");

        String generatedId = metrics.getAnonymousId();
        assertNotNull(metrics.getAnonymousId());
        String eventsDistinctId = metrics.getDistinctId();
        assertEquals("$device:" + generatedId, eventsDistinctId);
        assertNull(metrics.getUserId());

        metrics.identify("Distinct Id", null);
        assertEquals("Distinct Id", metrics.getDistinctId());
        assertEquals(generatedId, metrics.getAnonymousId());

        // once its reset we will only have generated id but user id should be null
        metrics.reset();
        String generatedId2 = metrics.getAnonymousId();
        assertNotNull(generatedId2);
        assertNotSame(generatedId, generatedId2);
        assertEquals("$device:" + generatedId2, metrics.getDistinctId());
        assertNull(metrics.getUserId());
    }

    @Test
    public void testMessageQueuing() {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<String>();
        final SynchronizedReference<Boolean> isIdentifiedRef = new SynchronizedReference<Boolean>();
        isIdentifiedRef.set(false);

        final OPDbAdapter mockAdapter = new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public int addJSON(JSONObject message, String token, OPDbAdapter.Table table) {
                try {
                    messages.put("TABLE " + table.getName());
                    messages.put(message.toString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                return super.addJSON(message, token, table);
            }
        };
        mockAdapter.cleanupEvents(Long.MAX_VALUE, OPDbAdapter.Table.EVENTS);

        final RemoteService mockPoster = new HttpService() {
            public byte[] performRequest(String endpointUrl, ProxyServerInteractor interactor, Map<String, Object> params, SSLSocketFactory socketFactory) {
                final boolean isIdentified = isIdentifiedRef.get();
                assertTrue(params.containsKey("data"));
                final String decoded = Base64Coder.decodeString(params.get("data").toString());

                try {
                    messages.put("SENT FLUSH " + endpointUrl);
                    messages.put(decoded);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                return TestUtils.bytes("1\n");
            }
        };


        final OPConfig mockConfig = new OPConfig(new Bundle(), InstrumentationRegistry.getInstrumentation().getContext(), null) {
            @Override
            public int getFlushInterval() {
                return -1;
            }

            @Override
            public int getBulkUploadLimit() {
                return 40;
            }

            @Override
            public String getEventsEndpoint() {
                return "EVENTS_ENDPOINT";
            }

            @Override
            public boolean getDisableAppOpenEvent() { return true; }
        };

        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), mockConfig) {
            @Override
            protected OPDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }


            @Override
            protected RemoteService getPoster() {
                return mockPoster;
            }
        };

        OursPrivacyAPI metrics = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "Test Message Queuing") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        };

        metrics.identify("EVENTS ID", null);

        // Test filling up the message queue
        for (int i=0; i < mockConfig.getBulkUploadLimit() - 2; i++) {
            metrics.track("frequent event", null);
        }

        metrics.track("final event", null);
        String expectedJSONMessage = "<No message actually received>";

        try {
            String messageTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE " + OPDbAdapter.Table.EVENTS.getName(), messageTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject message = new JSONObject(expectedJSONMessage);
            assertEquals("$identify", message.getString("event"));

            for (int i=0; i < mockConfig.getBulkUploadLimit() - 2; i++) {
                messageTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
                assertEquals("TABLE " + OPDbAdapter.Table.EVENTS.getName(), messageTable);

                expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
                message = new JSONObject(expectedJSONMessage);
                assertEquals("frequent event", message.getString("event"));
            }

            messageTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE " + OPDbAdapter.Table.EVENTS.getName(), messageTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            message = new JSONObject(expectedJSONMessage);
            assertEquals("final event", message.getString("event"));

            String messageFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH EVENTS_ENDPOINT", messageFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray bigFlush = new JSONArray(expectedJSONMessage);
            assertEquals(mockConfig.getBulkUploadLimit(), bigFlush.length());

            metrics.track("next wave", null);
            metrics.flush();

            String nextWaveTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE " + OPDbAdapter.Table.EVENTS.getName(), nextWaveTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject nextWaveMessage = new JSONObject(expectedJSONMessage);
            assertEquals("next wave", nextWaveMessage.getString("event"));

            String manualFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH EVENTS_ENDPOINT", manualFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray nextWave = new JSONArray(expectedJSONMessage);
            assertEquals(1, nextWave.length());

            JSONObject nextWaveEvent = nextWave.getJSONObject(0);
            assertEquals("next wave", nextWaveEvent.getString("event"));

            isIdentifiedRef.set(true);
            metrics.identify("PEOPLE ID", null);
            metrics.flush();

            String peopleTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE " + OPDbAdapter.Table.EVENTS.getName(), peopleTable);
            messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject peopleMessage = new JSONObject(expectedJSONMessage);

            assertEquals("PEOPLE ID", peopleMessage.getString("$distinct_id"));
            assertEquals("yup", peopleMessage.getJSONObject("$set").getString("prop"));

            messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            String peopleFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH PEOPLE_ENDPOINT", peopleFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray peopleSent = new JSONArray(expectedJSONMessage);
            assertEquals(1, peopleSent.length());

            metrics.flush();

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject groupsMessage = new JSONObject(expectedJSONMessage);

            assertEquals("testKey", groupsMessage.getString("$group_key"));
            assertEquals("testID", groupsMessage.getString("$group_id"));
            assertEquals("yup", groupsMessage.getJSONObject("$set").getString("prop"));

            String groupsFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH GROUPS_ENDPOINT", groupsFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray groupsSent = new JSONArray(expectedJSONMessage);
            assertEquals(1, groupsSent.length());
        } catch (InterruptedException e) {
            fail("Expected a log message about oursprivacy communication but did not receive it.");
        } catch (JSONException e) {
            fail("Expected a JSON object message and got something silly instead: " + expectedJSONMessage);
        }
    }

    @Test
    public void testTrackCharge() {
        final List<AnalyticsMessages.PeopleDescription> messages = new ArrayList<>();
        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic()) {
                    throw new RuntimeException("Should not be called during this test");
                }
            }
        };

        class ListeningAPI extends TestUtils.CleanOursPrivacyAPI {
            public ListeningAPI(Context c, Future<SharedPreferences> referrerPrefs, String token) {
                super(c, referrerPrefs, token);
            }

            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        }

        OursPrivacyAPI api = new ListeningAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "TRACKCHARGE TEST TOKEN");

        JSONObject props;
        try {
            props = new JSONObject("{'$time':'Should override', 'Orange':'Banana'}");
        } catch (JSONException e) {
            throw new RuntimeException("Can't construct fixture for trackCharge test");
        }

        assertEquals(messages.size(), 1);

        JSONObject message = messages.get(0).getMessage();

        try {
            JSONObject append = message.getJSONObject("$append");
            JSONObject newTransaction = append.getJSONObject("$transactions");
            assertEquals(newTransaction.optString("Orange"), "Banana");
            assertEquals(newTransaction.optString("$time"), "Should override");
            assertEquals(newTransaction.optDouble("$amount"), 2.13, 0);
        } catch (JSONException e) {
            fail("Transaction message had unexpected layout:\n" + message.toString());
        }
    }

    @Test
    public void testTrackWithSavedDistinctId(){
        final String savedDistinctID = "saved_distinct_id";
        final List<Object> messages = new ArrayList<Object>();
        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public void eventsMessage(EventDescription heard) {
                  if (!heard.isAutomatic() && !heard.getEventName().equals("$identify")) {
                    messages.add(heard);
                }
            }
        };

        class TestOursPrivacyAPI extends OursPrivacyAPI {
            public TestOursPrivacyAPI(Context c, Future<SharedPreferences> prefs, String token) {
                super(c, prefs, token, false, null, true);
            }

            @Override
            /* package */ PersistentIdentity getPersistentIdentity(final Context context, final Future<SharedPreferences> referrerPreferences, final String token, final String instanceName) {
                String instanceKey = instanceName != null ? instanceName : token;
                final String oursprivacyPrefsName = "com.oursprivacy.android.opmetrics.OursPrivacy";
                final SharedPreferences mpSharedPrefs = context.getSharedPreferences(oursprivacyPrefsName, Context.MODE_PRIVATE);
                mpSharedPrefs.edit().clear().putBoolean(token, true).putBoolean("has_launched", true).commit();
                final String prefsName = "com.oursprivacy.android.opmetrics.OursPrivacyAPI_" + instanceKey;
                final SharedPreferences loadstorePrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
                loadstorePrefs.edit().clear().putString("events_distinct_id", savedDistinctID).putString("people_distinct_id", savedDistinctID).commit();
                return super.getPersistentIdentity(context, referrerPreferences, token, instanceName);
                }

            @Override
            /* package */ boolean sendAppOpen() {
                return false;
            }

            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        }

        TestOursPrivacyAPI opMetrics = new TestOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "SAME TOKEN");
        assertEquals(opMetrics.getDistinctId(), savedDistinctID);
        opMetrics.identify("new_user", null);

        opMetrics.track("eventname", null);

        assertEquals(2, messages.size());

        AnalyticsMessages.EventDescription eventMessage = (AnalyticsMessages.EventDescription) messages.get(0);
        JSONObject peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(1)).getMessage();

        try {
            JSONObject eventProps = eventMessage.getProperties();
            String deviceId = eventProps.getString("$device_id");
            assertEquals(savedDistinctID, deviceId);
            boolean hadPersistedDistinctId = eventProps.getBoolean("$had_persisted_distinct_id");
            assertEquals(true, hadPersistedDistinctId);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            String deviceId = peopleMessage.getString("$device_id");
            boolean hadPersistedDistinctId = peopleMessage.getBoolean("$had_persisted_distinct_id");
            assertEquals(savedDistinctID, deviceId);
            assertEquals(true, hadPersistedDistinctId);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }
        messages.clear();
    }

    @Test
    public void testSetAddRemoveGroup(){
        final List<Object> messages = new ArrayList<Object>();
        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic() &&
                        !heard.getEventName().equals("$identify") &&
                        !heard.getEventName().equals("Integration")) {
                    messages.add(heard);
                }
            }
        };

        class TestOursPrivacyAPI extends OursPrivacyAPI {
            public TestOursPrivacyAPI(Context c, Future<SharedPreferences> prefs, String token) {
                super(c, prefs, token, false, null, true);
            }

            @Override
                /* package */ boolean sendAppOpen() {
                return false;
            }

            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        }

        TestOursPrivacyAPI opMetrics = new TestOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "SAME TOKEN");
        opMetrics.identify("new_user", null);

        int groupID = 42;
        opMetrics.track("eventname", null);

        assertEquals(2, messages.size());

        JSONObject peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(0)).getMessage();
        AnalyticsMessages.EventDescription eventMessage = (AnalyticsMessages.EventDescription) messages.get(1);

        try {
            JSONObject eventProps = eventMessage.getProperties();
            JSONArray groupIDs = eventProps.getJSONArray("group_key");
            assertEquals((new JSONArray()).put(groupID), groupIDs);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            JSONObject setMessage = peopleMessage.getJSONObject("$set");
            assertEquals((new JSONArray()).put(groupID), setMessage.getJSONArray("group_key"));
        } catch (JSONException e) {
            fail("People message has an unexpected shape " + e);
        }

        messages.clear();

        int groupID2 = 77;
        opMetrics.track("eventname", null);
        JSONArray expectedGroupIDs = new JSONArray();
        expectedGroupIDs.put(groupID);
        expectedGroupIDs.put(groupID2);

        assertEquals(2, messages.size());
        peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(0)).getMessage();
        eventMessage = (AnalyticsMessages.EventDescription) messages.get(1);

        try {
            JSONObject eventProps = eventMessage.getProperties();
            JSONArray groupIDs = eventProps.getJSONArray("group_key");
            assertEquals(expectedGroupIDs, groupIDs);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            JSONObject unionMessage = peopleMessage.getJSONObject("$union");
            assertEquals((new JSONArray()).put(groupID2), unionMessage.getJSONArray("group_key"));
        } catch (JSONException e) {
            fail("People message has an unexpected shape " + e);
        }

        messages.clear();
        opMetrics.track("eventname", null);

        assertEquals(2, messages.size());
        peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(0)).getMessage();
        eventMessage = (AnalyticsMessages.EventDescription) messages.get(1);

        try {
            JSONObject eventProps = eventMessage.getProperties();
            JSONArray groupIDs = eventProps.getJSONArray("group_key");
            assertEquals((new JSONArray()).put(groupID), groupIDs);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            JSONObject removeMessage = peopleMessage.getJSONObject("$remove");
            assertEquals(groupID2, removeMessage.getInt("group_key"));
        } catch (JSONException e) {
            fail("People message has an unexpected shape " + e);
        }

        messages.clear();
        opMetrics.track("eventname", null);

        assertEquals(2, messages.size());
        peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(0)).getMessage();
        eventMessage = (AnalyticsMessages.EventDescription) messages.get(1);

        JSONObject eventProps = eventMessage.getProperties();
        assertFalse(eventProps.has("group_key"));

        try {
            JSONArray unsetMessage = peopleMessage.getJSONArray("$unset");
            assertEquals(1, unsetMessage.length());
            assertEquals("group_key", unsetMessage.get(0));
        } catch (JSONException e) {
            fail("People message has an unexpected shape " + e);
        }

        messages.clear();
    }

    @Test
    public void testIdentifyCall() throws JSONException {
        String newDistinctId = "New distinct ID";
        final List<AnalyticsMessages.EventDescription> messages = new ArrayList<AnalyticsMessages.EventDescription>();
        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic()) {
                    messages.add(heard);
                }
            }
        };

        OursPrivacyAPI metrics = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "Test Identify Call") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };
        String oldDistinctId = metrics.getDistinctId();
        metrics.identify(newDistinctId, null);
        metrics.identify(newDistinctId, null);
        metrics.identify(newDistinctId, null);

        assertEquals(messages.size(), 1);
        AnalyticsMessages.EventDescription identifyEventDescription = messages.get(0);
        assertEquals(identifyEventDescription.getEventName(), "$identify");
        String newDistinctIdIdentifyTrack = identifyEventDescription.getProperties().getString("distinct_id");
        String anonDistinctIdIdentifyTrack = identifyEventDescription.getProperties().getString("$anon_distinct_id");

        assertEquals(newDistinctIdIdentifyTrack, newDistinctId);
        assertEquals(anonDistinctIdIdentifyTrack, oldDistinctId);
        assertEquals(messages.size(), 1);
    }

    @Test
    public void testIdentifyResetCall() throws JSONException {
        String newDistinctId = "New distinct ID";
        final List<AnalyticsMessages.EventDescription> messages = new ArrayList<AnalyticsMessages.EventDescription>();
        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic()) {
                    messages.add(heard);
                }
            }
        };

        OursPrivacyAPI metrics = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "Test Identify Call") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };
        ArrayList<String> oldDistinctIds = new ArrayList<>();
        oldDistinctIds.add(metrics.getDistinctId());
        metrics.identify(newDistinctId + "0", null);
        metrics.reset();

        assertThat(oldDistinctIds, not(hasItem(metrics.getDistinctId())));
        oldDistinctIds.add(metrics.getDistinctId());
        metrics.identify(newDistinctId + "1", null);
        metrics.reset();

        assertThat(oldDistinctIds, not(hasItem(metrics.getDistinctId())));
        oldDistinctIds.add(metrics.getDistinctId());
        metrics.identify(newDistinctId + "2", null);

        assertEquals(messages.size(), 3);
        for (int i=0; i < 3; i++) {
            AnalyticsMessages.EventDescription identifyEventDescription = messages.get(i);
            assertEquals(identifyEventDescription.getEventName(), "$identify");
            String newDistinctIdIdentifyTrack = identifyEventDescription.getProperties().getString("distinct_id");
            String anonDistinctIdIdentifyTrack = identifyEventDescription.getProperties().getString("$anon_distinct_id");

            assertEquals(newDistinctIdIdentifyTrack, newDistinctId + i);
            assertEquals(anonDistinctIdIdentifyTrack, oldDistinctIds.get(i));
        }
    }

    @Test
    public void testPersistence() {
        OursPrivacyAPI metricsOne = new OursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "SAME TOKEN", false, null, true);
        metricsOne.reset();

        JSONObject props;
        try {
            props = new JSONObject("{ 'a' : 'value of a', 'b' : 'value of b' }");
        } catch (JSONException e) {
            throw new RuntimeException("Can't construct fixture for super properties test.");
        }

        metricsOne.clearSuperProperties();
        metricsOne.registerSuperProperties(props);
        metricsOne.identify("Expected Events Identity", null);

        // We exploit the fact that any metrics object with the same token
        // will get their values from the same persistent store.

        final List<Object> messages = new ArrayList<Object>();
        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic()) {
                    messages.add(heard);
                }
            }
        };

        class ListeningAPI extends OursPrivacyAPI {
            public ListeningAPI(Context c, Future<SharedPreferences> prefs, String token) {
                super(c, prefs, token, false, null, true);
            }

            @Override
        /* package */ PersistentIdentity getPersistentIdentity(final Context context, final Future<SharedPreferences> referrerPreferences, final String token, final String instanceName) {
                String instanceKey = instanceName != null ? instanceName : token;
            final String oursprivacyPrefsName = "com.oursprivacy.android.opmetrics.OursPrivacy";
                final SharedPreferences mpSharedPrefs = context.getSharedPreferences(oursprivacyPrefsName, Context.MODE_PRIVATE);
                mpSharedPrefs.edit().clear().putBoolean(instanceKey, true).putBoolean("has_launched", true).commit();

                return super.getPersistentIdentity(context, referrerPreferences, token, instanceName);
            }

            @Override
            /* package */ boolean sendAppOpen() {
                return false;
            }

            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        }

        OursPrivacyAPI differentToken = new ListeningAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "DIFFERENT TOKEN");

        differentToken.track("other event", null);

        assertEquals(2, messages.size());

        AnalyticsMessages.EventDescription eventMessage = (AnalyticsMessages.EventDescription) messages.get(0);

        try {
            JSONObject eventProps = eventMessage.getProperties();
            String sentId = eventProps.getString("distinct_id");
            String sentA = eventProps.optString("a");
            String sentB = eventProps.optString("b");

            assertFalse("Expected Events Identity".equals(sentId));
            assertEquals("", sentA);
            assertEquals("", sentB);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        messages.clear();

        OursPrivacyAPI metricsTwo = new ListeningAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "SAME TOKEN");

        metricsTwo.track("eventname", null);

        assertEquals(2, messages.size());

        eventMessage = (AnalyticsMessages.EventDescription) messages.get(0);
        JSONObject peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(1)).getMessage();

        try {
            JSONObject eventProps = eventMessage.getProperties();
            String sentId = eventProps.getString("distinct_id");
            String sentA = eventProps.getString("a");
            String sentB = eventProps.getString("b");

            assertEquals("Expected Events Identity", sentId);
            assertEquals("value of a", sentA);
            assertEquals("value of b", sentB);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            String sentId = peopleMessage.getString("$distinct_id");
            assertEquals("Expected Events Identity", sentId);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape: " + peopleMessage.toString());
        }
    }

    @Test
    public void testTrackInThread() throws InterruptedException, JSONException {
        class TestThread extends Thread {
            final BlockingQueue<JSONObject> mMessages;

            public TestThread(BlockingQueue<JSONObject> messages) {
                this.mMessages = messages;
            }

            @Override
            public void run() {

                final OPDbAdapter dbMock = new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
                    @Override
                    public int addJSON(JSONObject message, String token, OPDbAdapter.Table table) {
                        mMessages.add(message);

                        return 1;
                    }
                };

                final AnalyticsMessages analyticsMessages = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
                    @Override
                    public OPDbAdapter makeDbAdapter(Context context) {
                        return dbMock;
                    }
                };

                OursPrivacyAPI oursprivacy = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "TEST TOKEN") {
                    @Override
                    protected AnalyticsMessages getAnalyticsMessages() {
                        return analyticsMessages;
                    }
                };
                oursprivacy.reset();
                oursprivacy.track("test in thread", new JSONObject());
            }
        }

        //////////////////////////////

        final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();
        TestThread testThread = new TestThread(messages);
        testThread.start();
        JSONObject found = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(found);
        assertEquals(found.getString("event"), "test in thread");
        assertTrue(found.getJSONObject("properties").has("$bluetooth_version"));
    }

    @Test
    public void testAlias() {
        final RemoteService mockPoster = new HttpService() {
            public byte[] performRequest(String endpointUrl, ProxyServerInteractor interactor, Map<String, Object> params, SSLSocketFactory socketFactory) {
                try {
                    assertTrue(params.containsKey("data"));
                    final String jsonData = Base64Coder.decodeString(params.get("data").toString());
                    JSONArray msg = new JSONArray(jsonData);
                    JSONObject event = msg.getJSONObject(0);
                    JSONObject properties = event.getJSONObject("properties");

                    assertEquals(event.getString("event"), "$create_alias");
                    assertEquals(properties.getString("distinct_id"), "old id");
                    assertEquals(properties.getString("alias"), "new id");
                } catch (JSONException e) {
                    throw new RuntimeException("Malformed data passed to test mock", e);
                }
                return TestUtils.bytes("1\n");
            }
        };

        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            protected RemoteService getPoster() {
                return mockPoster;
            }
        };

        OursPrivacyAPI metrics = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "Test Message Queuing") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        };

        // Check that we post the alias immediately
        metrics.identify("old id", null);
        metrics.alias("new id", "old id");
    }

    @Test
    public void testMultiInstancesWithInstanceName() throws InterruptedException, JSONException {
        final BlockingQueue<JSONObject> anonymousUpdates = new LinkedBlockingQueue<JSONObject>();
        final BlockingQueue<JSONObject> identifiedUpdates = new LinkedBlockingQueue<JSONObject>();

        final OPDbAdapter mockAdapter = new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public int addJSON(JSONObject j, String token, Table table) {
                return super.addJSON(j, token, table);
            }
        };

        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            protected OPDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }
        };

        OursPrivacyAPI oursprivacy1 = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "testAnonymousPeopleUpdates", "instance1") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };
        OursPrivacyAPI oursprivacy2 = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "testAnonymousPeopleUpdates", "instance2") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };
    }

    @Test
    public void testEventTiming() throws InterruptedException {
        final int MAX_TIMEOUT_POLL = 6500;
        Future<SharedPreferences> mMockReferrerPreferences;
        final BlockingQueue<String> mStoredEvents = new LinkedBlockingQueue<>();
        mMockReferrerPreferences = new TestUtils.EmptyPreferences(InstrumentationRegistry.getInstrumentation().getContext());
        OursPrivacyAPI mOursPrivacyAPI = new OursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockReferrerPreferences, "TESTTOKEN", false, null, true) {
            @Override
            PersistentIdentity getPersistentIdentity(Context context, Future<SharedPreferences> referrerPreferences, String token, String instanceName) {
                mPersistentIdentity = super.getPersistentIdentity(context, referrerPreferences, token, instanceName);
                return mPersistentIdentity;
            }

        };

        mOursPrivacyAPI.timeEvent("Time Event");
        assertEquals(1, mPersistentIdentity.getTimeEvents().size());

        mOursPrivacyAPI.track("Time Event");
        assertEquals(0, mPersistentIdentity.getTimeEvents().size());
        mOursPrivacyAPI.timeEvent("Time Event1");
        mOursPrivacyAPI.timeEvent("Time Event2");
        assertEquals(2, mPersistentIdentity.getTimeEvents().size());
        mOursPrivacyAPI.clearTimedEvents();
        assertEquals(0, mPersistentIdentity.getTimeEvents().size());
        mOursPrivacyAPI.timeEvent("Time Event3");
        mOursPrivacyAPI.timeEvent("Time Event4");
        mOursPrivacyAPI.clearTimedEvent("Time Event3");
        assertEquals(1, mPersistentIdentity.getTimeEvents().size());
        assertTrue(mPersistentIdentity.getTimeEvents().containsKey("Time Event4"));
        assertFalse(mPersistentIdentity.getTimeEvents().containsKey("Time Event3"));
        mOursPrivacyAPI.clearTimedEvent(null);
        assertEquals(1, mPersistentIdentity.getTimeEvents().size());
    }


    @Test
    public void testSessionMetadata() throws InterruptedException, JSONException {
        final BlockingQueue<JSONObject> storedJsons = new LinkedBlockingQueue<>();
        final BlockingQueue<AnalyticsMessages.EventDescription> eventsMessages = new LinkedBlockingQueue<>();
        final BlockingQueue<AnalyticsMessages.PeopleDescription> peopleMessages = new LinkedBlockingQueue<>();
        final OPDbAdapter mockAdapter = new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {

            @Override
            public int addJSON(JSONObject j, String token, Table table) {
                storedJsons.add(j);
                return super.addJSON(j, token, table);
            }
        };
        final AnalyticsMessages listener = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            public void eventsMessage(EventDescription eventDescription) {
                if (!eventDescription.isAutomatic()) {
                    eventsMessages.add(eventDescription);
                    super.eventsMessage(eventDescription);
                }
            }

            @Override
            protected OPDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }
        };
        OursPrivacyAPI metrics = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockPreferences, "Test Session Metadata") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }

            @Override
            protected void track(String eventName, JSONObject properties, boolean isAutomaticEvent) {
                if (!isAutomaticEvent) {
                    super.track(eventName, properties, isAutomaticEvent);
                }
            }
        };

        metrics.track("First Event");
        metrics.track("Second Event");
        metrics.track("Third Event");
        metrics.track("Fourth Event");

        metrics.identify("OursPrivacy", null);

        for (int i = 0; i < 4; i++) {
            JSONObject sessionMetadata = eventsMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getSessionMetadata();
            assertTrue(sessionMetadata.has("$mp_event_id"));
            assertTrue(sessionMetadata.has("$mp_session_id"));
            assertTrue(sessionMetadata.has("$mp_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$mp_session_seq_id"));
        }
        eventsMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getSessionMetadata();
        assertNull(eventsMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

        for (int i = 0; i < 3; i++) {
            JSONObject sessionMetadata = peopleMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getMessage().getJSONObject("$mp_metadata");
            assertTrue(sessionMetadata.has("$mp_event_id"));
            assertTrue(sessionMetadata.has("$mp_session_id"));
            assertTrue(sessionMetadata.has("$mp_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$mp_session_seq_id"));
        }
        assertNull(peopleMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

        for (int i = 0; i < 4; i++) {
            JSONObject sessionMetadata = storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$mp_metadata");
            assertTrue(sessionMetadata.has("$mp_event_id"));
            assertTrue(sessionMetadata.has("$mp_session_id"));
            assertTrue(sessionMetadata.has("$mp_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$mp_session_seq_id"));
        }
        storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$mp_metadata");

        for (int i = 0; i < 3; i++) {
            JSONObject sessionMetadata = storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$mp_metadata");
            assertTrue(sessionMetadata.has("$mp_event_id"));
            assertTrue(sessionMetadata.has("$mp_session_id"));
            assertTrue(sessionMetadata.has("$mp_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$mp_session_seq_id"));
        }
        assertNull(storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));
    }

    private Future<SharedPreferences> mMockPreferences;

    private static final int POLL_WAIT_SECONDS = 10;

    private String mAppProperties;

    private PersistentIdentity mPersistentIdentity;
}
