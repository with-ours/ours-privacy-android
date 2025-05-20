package com.oursprivacy.android.opmetrics;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class OPConfigTest {

    public static final String TOKEN = "TOKEN";
    public static final String DISABLE_VIEW_CRAWLER_METADATA_KEY = "com.oursprivacy.android.MPConfig.DisableViewCrawler";

    @Test
    public void testSetUseIpAddressForGeolocation() {
        final Bundle metaData = new Bundle();
        metaData.putString("com.oursprivacy.android.MPConfig.EventsEndpoint", "https://api.oursprivacy.com/api/v1/track/?ip=1");
        metaData.putString("com.oursprivacy.android.MPConfig.EventsEndpoint", "https://api.oursprivacy.com/api/v1/track/?ip=1");

        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
    }

    @Test
    public void testSetUseIpAddressForGeolocationOverwrite() {
        final Bundle metaData = new Bundle();
        metaData.putString("com.oursprivacy.android.MPConfig.EventsEndpoint", "https://api.oursprivacy.com/api/v1/track/?ip=1");
        metaData.putString("com.oursprivacy.android.MPConfig.PeopleEndpoint", "https://api.oursprivacy.com/api/v1/engage/?ip=1");

        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        assertEquals("https://api.oursprivacy.com/api/v1/track/?ip=1", config.getEventsEndpoint());

        oursprivacyAPI.setUseIpAddressForGeolocation(false);
        assertEquals("https://api.oursprivacy.com/api/v1/track/?ip=0", config.getEventsEndpoint());

        final Bundle metaData2 = new Bundle();
        metaData2.putString("com.oursprivacy.android.MPConfig.EventsEndpoint", "https://api.oursprivacy.com/api/v1/track/?ip=0");
        metaData2.putString("com.oursprivacy.android.MPConfig.PeopleEndpoint", "https://api.oursprivacy.com/api/v1/engage/?ip=0");

        OPConfig config2 = mpConfig(metaData2);
        final OursPrivacyAPI oursprivacyAPI2 = oursprivacyApi(config2);
        assertEquals("https://api.oursprivacy.com/api/v1/track/?ip=0", config2.getEventsEndpoint());

        oursprivacyAPI2.setUseIpAddressForGeolocation(true);
        assertEquals("https://api.oursprivacy.com/api/v1/track/?ip=1", config2.getEventsEndpoint());
    }

    @Test
    public void testEndPointAndGeoSettingBothReadFromConfigTrue() {
        final Bundle metaData = new Bundle();
        metaData.putString("com.oursprivacy.android.MPConfig.EventsEndpoint", "https://api.oursprivacy.com/api/v1/track/");
        metaData.putString("com.oursprivacy.android.MPConfig.PeopleEndpoint", "https://api.oursprivacy.com/api/v1/engage/");
        metaData.putString("com.oursprivacy.android.MPConfig.GroupsEndpoint", "https://api.oursprivacy.com/api/v1/groups/");
        metaData.putBoolean("com.oursprivacy.android.MPConfig.UseIpAddressForGeolocation", true);

        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        assertEquals("https://api.oursprivacy.com/api/v1/track/?ip=1", config.getEventsEndpoint());
    }

    public void testSetServerURL() throws Exception {
        final Bundle metaData = new Bundle();
        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        // default OursPrivacy endpoint
        assertEquals("https://api.oursprivacy.com/api/v1/track/?ip=1", config.getEventsEndpoint());

        oursprivacyAPI.setServerURL("https://api-eu.oursprivacy.com");
        assertEquals("https://api-eu.oursprivacy.com/track/?ip=1", config.getEventsEndpoint());
    }

    @Test
    public void testEndPointAndGeoSettingBothReadFromConfigFalse() {
        final Bundle metaData = new Bundle();
        metaData.putString("com.oursprivacy.android.MPConfig.EventsEndpoint", "https://api.oursprivacy.com/api/v1/track/");
        metaData.putString("com.oursprivacy.android.MPConfig.PeopleEndpoint", "https://api.oursprivacy.com/api/v1/engage/");
        metaData.putString("com.oursprivacy.android.MPConfig.GroupsEndpoint", "https://api.oursprivacy.com/api/v1/groups/");
        metaData.putBoolean("com.oursprivacy.android.MPConfig.UseIpAddressForGeolocation", false);

        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        assertEquals("https://api.oursprivacy.com/api/v1/track/?ip=0", config.getEventsEndpoint());
    }

    @Test
    public void testEndPointAndGeoSettingBothReadFromConfigFalseOverwrite() {
        final Bundle metaData = new Bundle();
        metaData.putString("com.oursprivacy.android.MPConfig.EventsEndpoint", "https://api.oursprivacy.com/api/v1/track/?ip=1");
        metaData.putString("com.oursprivacy.android.MPConfig.PeopleEndpoint", "https://api.oursprivacy.com/api/v1/engage/?ip=1");
        metaData.putString("com.oursprivacy.android.MPConfig.GroupsEndpoint", "https://api.oursprivacy.com/api/v1/groups/?ip=1");
        metaData.putBoolean("com.oursprivacy.android.MPConfig.UseIpAddressForGeolocation", false);

        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        assertEquals("https://api.oursprivacy.com/api/v1/track/?ip=0", config.getEventsEndpoint());
    }

    @Test
    public void testSetEnableLogging() throws Exception {
        final Bundle metaData = new Bundle();
        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        oursprivacyAPI.setEnableLogging(true);
        assertTrue(config.DEBUG);
        oursprivacyAPI.setEnableLogging(false);
        assertFalse(config.DEBUG);
    }


    @Test
    public void testSetFlushBatchSize() {
        final Bundle metaData = new Bundle();
        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        oursprivacyAPI.setFlushBatchSize(10);
        assertEquals(10, config.getFlushBatchSize());
        oursprivacyAPI.setFlushBatchSize(100);
        assertEquals(100, config.getFlushBatchSize());
    }

    @Test
    public void testSetFlushBatchSize2() {
        final Bundle metaData = new Bundle();
        metaData.putInt("com.oursprivacy.android.MPConfig.FlushBatchSize", 5);
        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        assertEquals(5, oursprivacyAPI.getFlushBatchSize());
    }

    @Test
    public void testSetFlushBatchSizeMulptipleConfigs() {
        String fakeToken = UUID.randomUUID().toString();
        OursPrivacyAPI oursprivacy1 = OursPrivacyAPI.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), fakeToken, false);
        oursprivacy1.setFlushBatchSize(10);
        assertEquals(10, oursprivacy1.getFlushBatchSize());

        String fakeToken2 = UUID.randomUUID().toString();
        OursPrivacyAPI oursprivacy2 = OursPrivacyAPI.getInstance(InstrumentationRegistry.getInstrumentation().getContext(), fakeToken2, false);
        oursprivacy2.setFlushBatchSize(20);
        assertEquals(20, oursprivacy2.getFlushBatchSize());
        // oursprivacy2 should not overwrite the settings to oursprivacy1
        assertEquals(10, oursprivacy1.getFlushBatchSize());
    }


    @Test
    public void testSetMaximumDatabaseLimit() {
        final Bundle metaData = new Bundle();
        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        oursprivacyAPI.setMaximumDatabaseLimit(10000);
        assertEquals(10000, config.getMaximumDatabaseLimit());
    }

    @Test
    public void testSetMaximumDatabaseLimit2() {
        final Bundle metaData = new Bundle();
        metaData.putInt("com.oursprivacy.android.MPConfig.MaximumDatabaseLimit", 100000000);
        OPConfig config = mpConfig(metaData);
        final OursPrivacyAPI oursprivacyAPI = oursprivacyApi(config);
        assertEquals(100000000, oursprivacyAPI.getMaximumDatabaseLimit());
    }

    @Test
    public void testShouldGzipRequestPayload() {
        final Bundle metaData = new Bundle();
        metaData.putBoolean("com.oursprivacy.android.MPConfig.GzipRequestPayload", true);
        OPConfig OPConfig = mpConfig(metaData);
        assertTrue(OPConfig.shouldGzipRequestPayload());

        OPConfig.setShouldGzipRequestPayload(false);
        assertFalse(OPConfig.shouldGzipRequestPayload());

        OPConfig.setShouldGzipRequestPayload(true);
        assertTrue(OPConfig.shouldGzipRequestPayload());

        // assert false by default
        OPConfig OPConfig2 = mpConfig(new Bundle());
        assertFalse(OPConfig2.shouldGzipRequestPayload());

        OursPrivacyAPI oursprivacyAPI = oursprivacyApi(OPConfig);

        assertTrue(oursprivacyAPI.shouldGzipRequestPayload());

        oursprivacyAPI.setShouldGzipRequestPayload(false);
        assertFalse(oursprivacyAPI.shouldGzipRequestPayload());

    }

    private OPConfig mpConfig(final Bundle metaData) {
        return new OPConfig(metaData, InstrumentationRegistry.getInstrumentation().getContext(), null);
    }

    private OursPrivacyAPI oursprivacyApi(final OPConfig config) {
        return new OursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), new TestUtils.EmptyPreferences(InstrumentationRegistry.getInstrumentation().getContext()), TOKEN, config, false, null,null, true);
    }
}
