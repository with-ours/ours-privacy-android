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
    public static final String DISABLE_VIEW_CRAWLER_METADATA_KEY = "com.oursprivacy.android.Config.DisableViewCrawler";

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
        metaData.putInt("com.oursprivacy.android.Config.FlushBatchSize", 5);
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

    private OPConfig mpConfig(final Bundle metaData) {
        return new OPConfig(metaData, InstrumentationRegistry.getInstrumentation().getContext(), null);
    }

    private OursPrivacyAPI oursprivacyApi(final OPConfig config) {
        return new OursPrivacyAPI(InstrumentationRegistry.getInstrumentation().getContext(), new TestUtils.EmptyPreferences(InstrumentationRegistry.getInstrumentation().getContext()), TOKEN, config, false, null,null, true);
    }
}
