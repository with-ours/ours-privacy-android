package com.oursprivacy.oursprivacydemo

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TrackingFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun trackingActionsSendEventsToRecorder() {
        assertEquals("e2e-token", BuildConfig.OURSPRIVACY_TOKEN)
        assertTrue(BuildConfig.RECORDER_URL.startsWith("http://10.0.2.2:"))

        compose.onNodeWithText("Tracking").performClick()
        for (button in listOf("Track event", "Identify", "Track + per-call user props")) {
            compose.onNodeWithText(button).performClick()
            compose.onNodeWithText("OK").performClick()
        }
        // Flush is asynchronous, so the last request needs time to reach the recorder before instrumentation exits.
        Thread.sleep(5_000)
    }
}
