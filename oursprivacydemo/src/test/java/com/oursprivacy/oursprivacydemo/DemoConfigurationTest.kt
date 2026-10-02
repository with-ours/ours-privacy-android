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
}
