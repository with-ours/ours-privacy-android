package com.oursprivacy.oursprivacydemo

import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TrackingFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun demoActionsSendEventsToRecorder() {
        assertEquals("e2e-token", BuildConfig.OURSPRIVACY_TOKEN)
        assertTrue(BuildConfig.RECORDER_URL.startsWith("http://10.0.2.2:"))

        compose.onNodeWithText("GDPR").performClick()
        compose.onNodeWithText("Opt In").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Tracking").performClick()
        for (button in listOf("Track event", "Identify", "Track + per-call user props", "Deep link")) {
            compose.onNodeWithText(button).performClick()
            compose.onNodeWithText("OK").performClick()
        }
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Utility").performClick()
        compose.onNodeWithText("Flush").performClick()
        compose.onNodeWithText("OK").performClick()

        val expected = setOf("demo_event", "\$identify", "view_item", "\$opt_in", "\$deep_link_opened")
        var observed = emptySet<String>()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val connection = URL("${BuildConfig.RECORDER_URL}/events").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 1_000
                connection.readTimeout = 1_000
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val events = JSONObject(response).getJSONArray("events")
                observed = (0 until events.length()).map { events.getString(it) }.toSet()
            } finally {
                connection.disconnect()
            }
            if (observed.containsAll(expected)) return
            Thread.sleep(500)
        }
        assertTrue("Recorder missing ${expected - observed}; observed $observed", observed.containsAll(expected))
    }
}
