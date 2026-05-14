package com.oursprivacy.android.opmetrics;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oursprivacy.android.util.Base64Coder;
import com.oursprivacy.android.util.HttpService;
import com.oursprivacy.android.util.ProxyServerInteractor;
import com.oursprivacy.android.util.RemoteService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class OptOutTest {

    private OursPrivacyAPI mOursPrivacyAPI;
    private static final String TOKEN = "Opt Out Test Token";
    final private BlockingQueue<String> mPerformRequestEvents = new LinkedBlockingQueue<>();
    final private BlockingQueue<String> mStoredEvents = new LinkedBlockingQueue<>();
    final private BlockingQueue<String> mStoredPeopleUpdates = new LinkedBlockingQueue<>();
    final private BlockingQueue<String> mStoredAnonymousPeopleUpdates = new LinkedBlockingQueue<>();
    private CountDownLatch mCleanUpCalls = new CountDownLatch(1);

    private OPDbAdapter mMockAdapter;
    private Future<SharedPreferences> mMockReferrerPreferences;
    private AnalyticsMessages mAnalyticsMessages;
    private PersistentIdentity mPersistentIdentity;
    private static final int MAX_TIMEOUT_POLL = 6500;

    @Before
    public void setUp() {
        mMockReferrerPreferences = new TestUtils.EmptyPreferences(InstrumentationRegistry.getInstrumentation().getContext());

        final RemoteService mockPoster = new HttpService() {
            public byte[] performRequest(String endpointUrl, ProxyServerInteractor interactor, Map<String, Object> params, SSLSocketFactory socketFactory) {
                if (params != null) {
                    final String jsonData = Base64Coder.decodeString(params.get("data").toString());
                    assertTrue(params.containsKey("data"));

                    try {
                        JSONArray jsonArray = new JSONArray(jsonData);
                        for (int i = 0; i < jsonArray.length(); i++) {
                            mPerformRequestEvents.put(jsonArray.getJSONObject(i).toString());
                        }
                        return TestUtils.bytes("1\n");
                    } catch (JSONException e) {
                        throw new RuntimeException("Malformed data passed to test mock", e);
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Could not write message to reporting queue for tests.", e);
                    }

                }

                return TestUtils.bytes("{\"automatic_events\": false}");
            }
        };

        mMockAdapter = getMockDBAdapter();
        mAnalyticsMessages = new AnalyticsMessages(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {
            @Override
            protected RemoteService getPoster() {
                return mockPoster;
            }

            @Override
            protected OPDbAdapter makeDbAdapter(Context context) {
                return mMockAdapter;
            }
        };
    }

    @After
    public void tearDown() throws Exception {
        if (mPersistentIdentity != null) {
            mPersistentIdentity.clearPreferences();
            mPersistentIdentity.removeOptOutFlag(TOKEN);
            mPersistentIdentity = null;
        }
        mMockAdapter.deleteDB();
    }

    /**
     * Init OursPrivacy without tracking.
     * <p>
     * Make sure that after initialization no events are stored nor flushed.
     * Check that super properties, unidentified people updates or people distinct ID are
     * not stored in the device.
     *
     * @throws InterruptedException
     */
    @Test
    public void testOptOutDefaultFlag() throws InterruptedException {
        mCleanUpCalls = new CountDownLatch(2); // optOutTrack calls
        mOursPrivacyAPI = new OursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockReferrerPreferences, TOKEN, true, null, true) {
            @Override
            PersistentIdentity getPersistentIdentity(Context context, Future<SharedPreferences> referrerPreferences, String token, String instanceName) {
                mPersistentIdentity = super.getPersistentIdentity(context, referrerPreferences, token, instanceName);
                return mPersistentIdentity;
            }

            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return mAnalyticsMessages;
            }
        };
        mOursPrivacyAPI.flush();
        assertEquals(null, mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(null, mStoredPeopleUpdates.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(null, mStoredAnonymousPeopleUpdates.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertTrue(mCleanUpCalls.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
    }

    /**
     * Check that calls to optInTracking()/optOutTracking() updates hasOptedOutTracking()
     *
     * @throws InterruptedException
     */
    @Test
    public void testHasOptOutTrackingOrNot() throws InterruptedException {
        mCleanUpCalls = new CountDownLatch(4); // optOutTrack calls
        OursPrivacyAPI oursprivacy = new OursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockReferrerPreferences, "TOKEN", true, null, true) {
            @Override
            PersistentIdentity getPersistentIdentity(Context context, Future<SharedPreferences> referrerPreferences, String token, String instanceName) {
                mPersistentIdentity = super.getPersistentIdentity(context, referrerPreferences, token, instanceName);
                return mPersistentIdentity;
            }

            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return mAnalyticsMessages;
            }
        };
        
        oursprivacy.optInTracking();
        assertFalse(oursprivacy.hasOptedOutTracking());
        oursprivacy.optOutTracking();
        assertTrue(oursprivacy.hasOptedOutTracking());
    }

    /**
     * Test People updates when opt out/in:
     * 1. Not identified user: Updates stored in SharedPreferences should be removed after opting out
     * Following updates should be dropped.
     * 2. Identified user: Updates stored in DB should be removed after opting out and never sent
     * to OursPrivacy. Following updates should be dropped as well.
     *
     * @throws InterruptedException
     */
    @Test
    public void testPeopleUpdates() throws InterruptedException, JSONException {
        mCleanUpCalls = new CountDownLatch(2);
        mOursPrivacyAPI = new OursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockReferrerPreferences, TOKEN,false, null, true) {
            @Override
            PersistentIdentity getPersistentIdentity(Context context, Future<SharedPreferences> referrerPreferences, String token, String instanceName) {
                mPersistentIdentity = super.getPersistentIdentity(context, referrerPreferences, token, instanceName);
                return mPersistentIdentity;
            }

            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return mAnalyticsMessages;
            }
        };

        assertEquals("optOutPropertyValue", new JSONObject(mStoredAnonymousPeopleUpdates.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS)).getJSONObject("$set").getString("optOutProperty"));
        assertEquals(0, mStoredAnonymousPeopleUpdates.size());

        mOursPrivacyAPI.optOutTracking();
        assertEquals(true, mStoredAnonymousPeopleUpdates.isEmpty());
        assertTrue(mCleanUpCalls.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));

        mOursPrivacyAPI.optInTracking();
        mOursPrivacyAPI.identify("identity", null);
        for (int i = 0; i < 7; i++) {
            assertNotNull(mStoredPeopleUpdates.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        }
        assertEquals(0, mStoredPeopleUpdates.size());
        mMockAdapter = getMockDBAdapter();

        mCleanUpCalls = new CountDownLatch(2);
        mOursPrivacyAPI.optOutTracking();
        assertTrue(mCleanUpCalls.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        for (int i = 0; i < 2; i++) {
            String test = mStoredPeopleUpdates.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS);
            assertNotNull(test);
        }

        forceFlush();
        for (int i = 0; i < 2; i++) {
            assertNotNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        }
        assertNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
    }

    /**
     * Test that events are dropped when a user opts out. After opting in, an event should be sent.
     *
     * @throws InterruptedException
     */
    @Test
    public void testDropEventsAndOptInEvent() throws InterruptedException {
        mOursPrivacyAPI = new TestUtils.CleanOursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), mMockReferrerPreferences, TOKEN) {
            @Override
            PersistentIdentity getPersistentIdentity(Context context, Future<SharedPreferences> referrerPreferences, String token, String instanceName) {
                mPersistentIdentity = super.getPersistentIdentity(context, referrerPreferences, token, instanceName);
                return mPersistentIdentity;
            }

            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return mAnalyticsMessages;
            }
        };

        for (int i = 0; i < 20; i++) {
            mOursPrivacyAPI.track("An Event");
        }
        for (int i = 0; i < 20; i++) {
            assertEquals("An Event", mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        }

        mCleanUpCalls = new CountDownLatch(2);
        mOursPrivacyAPI.optOutTracking();
        mMockAdapter = getMockDBAdapter();
        assertTrue(mCleanUpCalls.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertNull(mMockAdapter.generateDataString(OPDbAdapter.Table.EVENTS, TOKEN));

        mOursPrivacyAPI.optInTracking();
        assertEquals("$opt_in", mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        forceFlush();
        assertNotNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
    }

    private void forceFlush() {
        mAnalyticsMessages.postToServer(new AnalyticsMessages.OursPrivacyDescription(TOKEN));
    }

    private OPDbAdapter getMockDBAdapter() {
        return new OPDbAdapter(InstrumentationRegistry.getInstrumentation().getContext(), OPConfig.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), null)) {

            @Override
            public void cleanupAllEvents(Table table, String token) {
                if (token.equalsIgnoreCase(TOKEN)) {
                    mCleanUpCalls.countDown();
                    super.cleanupAllEvents(table, token);
                }
            }

            @Override
            public int addJSON(JSONObject j, String token, Table table) {
                int result = 1;
                if (token.equalsIgnoreCase(TOKEN)) {
                    result = super.addJSON(j, token, table);
                    try {
                        if (Table.EVENTS == table) {
                            mStoredEvents.put(j.getString("event"));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Malformed data passed to test mock adapter", e);
                    }
                }

                return result;
            }
        };
    }
}
