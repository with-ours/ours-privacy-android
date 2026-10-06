package com.oursprivacy.oursprivacydemo

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class TrackingFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun demoActionsSendEventsToRecorder() {
        assertTrue(BuildConfig.OURSPRIVACY_TOKEN.startsWith("e2e-"))
        assertTrue(BuildConfig.RECORDER_URL.startsWith("http://10.0.2.2:"))

        compose.onNodeWithText("GDPR").performClick()
        compose.onNodeWithText("Opt In").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Tracking").performClick()
        for (button in listOf("Stitch visitor link", "Track Schedule", "Book appointment")) {
            compose.onNodeWithText(button).performClick()
            compose.onNodeWithText("OK").performClick()
        }
        for (button in listOf("Track event", "Identify", "Track + per-call user props", "Deep link")) {
            compose.onNodeWithText(button).performClick()
            compose.onNodeWithText("OK").performClick()
        }
        compose.onNodeWithContentDescription("Back").performClick()

        val expected = setOf(
            "demo_event", "\$identify", "view_item", "\$opt_in", "\$deep_link_opened",
            "\$mobile_first_open", "\$mobile_app_open", "\$mobile_session_start",
            "\$mobile_screen_view", "\$mobile_session_engagement", "appointment_booked"
        )
        val coldEvents = waitForRecorderEvents(expected - "\$mobile_session_engagement", 1)
        val coldOpenCount = coldEvents.count { it == "\$mobile_app_open" }
        assertEquals("Expected one app open before background", 1, coldOpenCount)

        SystemClock.sleep(1_200)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(800)
        assertEquals(
            "App opened while backgrounded",
            coldOpenCount,
            readRecorderEvents().count { it == "\$mobile_app_open" }
        )
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithText("Utility").performClick()
        compose.onNodeWithText("Flush").performClick()
        compose.onNodeWithText("OK").performClick()

        val observed = waitForRecorderEvents(expected, coldOpenCount + 1)
        assertEquals(
            "App-open count did not rise exactly once after resume",
            coldOpenCount + 1,
            observed.count { it == "\$mobile_app_open" }
        )
        val recordedEventCount = observed.size

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("GDPR").performClick()
        compose.onNodeWithText("Opt Out").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Tracking").performClick()
        compose.onNodeWithText("Track event").performClick()
        compose.onNodeWithText("OK").performClick()
        SystemClock.sleep(1_000)
        assertEquals(
            "Full opt-out allowed another event",
            recordedEventCount,
            readRecorderEvents().size
        )

        val application = compose.activity.application as DemoApplication
        val sdk = application.sdk
        compose.activityRule.scenario.recreate()
        assertSame(sdk, application.sdk)
        SystemClock.sleep(1_000)
        assertEquals(
            "Opt-out restart allowed another event",
            recordedEventCount,
            readRecorderEvents().size
        )
    }

    private fun waitForRecorderEvents(expected: Set<String>, minimumAppOpens: Int): List<String> {
        var observed = emptyList<String>()
        var lastRecorderError: String? = null
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                observed = readRecorderEvents()
            } catch (error: IOException) {
                lastRecorderError = error.message
            }
            if (observed.containsAll(expected) &&
                observed.count { it == "\$mobile_app_open" } >= minimumAppOpens
            ) break
            Thread.sleep(500)
        }
        assertTrue(
            "Recorder missing ${expected - observed.toSet()}; app opens " +
                "${observed.count { it == "\$mobile_app_open" }}/$minimumAppOpens; " +
                "last error $lastRecorderError",
            observed.containsAll(expected) &&
                observed.count { it == "\$mobile_app_open" } >= minimumAppOpens
        )
        return observed
    }

    private fun readRecorderEvents(): List<String> {
        val connection = URL("${BuildConfig.RECORDER_URL}/events").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val events = JSONObject(response).getJSONArray("events")
            return (0 until events.length()).map { events.getString(it) }
        } finally {
            connection.disconnect()
        }
    }
}
