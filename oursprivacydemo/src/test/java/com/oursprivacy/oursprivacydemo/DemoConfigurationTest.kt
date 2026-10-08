package com.oursprivacy.oursprivacydemo

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoConfigurationTest {
    @Test
    fun recorderUrlConfiguresSdkEndpoint() {
        assertEquals(
            "http://10.0.2.2:8765",
            demoInitOptions("http://10.0.2.2:8765").serverURL
        )
    }

    @Test
    fun recorderBuildAppliesSyntheticLinkBeforeFirstOpen() {
        assertEquals(
            "https://example.com/landing?ours_visitor_id=e2e-123-visitor&utm_source=demo&utm_medium=android&gclid=demoGclid",
            demoInitOptions("http://10.0.2.2:8765", "e2e-123").initialURL,
        )
    }
}
