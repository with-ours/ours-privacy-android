package com.oursprivacy.android.opmetrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MobileSessionTest {
    private static final String TOKEN = "mobile-session-test-token";
    private SharedPreferences preferences;
    private PersistentIdentity identity;
    private FakeClock clock;

    @Before
    public void setUp() {
        preferences = ApplicationProvider.getApplicationContext()
                .getSharedPreferences("mobile-session-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        clock = new FakeClock(1_780_000_000_000L);
        identity = new PersistentIdentity(CompletableFuture.completedFuture(preferences),
                clock::wallMillis);
    }

    @Test
    public void firstEligibleForegroundEmitsThreeFactsOnce() {
        MobileSession session = session("1.0", "10");

        List<MobileSession.MobileFact> first = session.foreground(true);
        assertEquals(List.of("$mobile_first_open", "$mobile_app_open",
                "$mobile_session_start"), names(first));
        assertTrue(session.foreground(true).isEmpty());
    }

    @Test
    public void firstOpenWaitsForQueueAcceptanceAcrossRecreation() {
        MobileSession first = session("1.0", "10");
        first.foreground(true);

        MobileSession recreated = recreated("1.0", "10");
        assertEquals(List.of("$mobile_app_open"),
                names(recreated.foreground(true)));
        assertEquals("$mobile_first_open", recreated.pendingFacts().get(0).eventName());
        acceptFirstOpen(recreated);

        MobileSession accepted = recreated("1.0", "10");
        assertEquals(List.of("$mobile_app_open"), names(accepted.foreground(true)));
    }

    @Test
    public void snapshotHasExactCanonicalDefaultsAndNoTopLevelTime() throws Exception {
        clock.wall = 1_759_708_795_000L;
        MobileSession session = session("2.1", "42");
        MobileSession.MobileFact first = session.foreground(true).get(0);
        JSONObject defaults = first.snapshot().defaultProperties();

        assertEquals(7, defaults.length());
        assertEquals(first.snapshot().sid(), defaults.getString("sid"));
        assertEquals("2025-10-05T23:59:55.000Z",
                defaults.getString("mobile_session_started_at"));
        assertEquals("2025-10-05T23:59:55.000Z", defaults.getString("mobile_occurred_at"));
        assertEquals("android", defaults.getString("mobile_platform"));
        assertEquals(1, defaults.getInt("mobile_contract_version"));
        assertEquals("2.1", defaults.getString("app_version"));
        assertEquals("42", defaults.getString("app_build"));
        assertFalse(defaults.has("time"));
        assertEquals(0, first.eventProperties().length());
        first.eventProperties().put("screen_name", "mutated");
        assertEquals(0, first.eventProperties().length());
    }

    @Test
    public void manualSnapshotStartsSessionWithoutAutomaticFactsOrFirstOpenConsumption() {
        MobileSession session = session("1.0", "10");
        MobileSession.MobileSnapshot manual = session.snapshot();
        assertFalse(manual.sid().isEmpty());
        assertEquals(0, session.foreground(false).size());
        assertTrue(session.background().isEmpty());
        assertEquals(List.of("$mobile_first_open", "$mobile_app_open",
                        "$mobile_session_start"),
                names(session.foreground(true)));
        assertEquals(manual.sid(), session.snapshot().sid());
    }

    @Test
    public void screenCreatedSessionGetsOneStartAtFirstEligibleForeground() {
        MobileSession session = session("1.0", "10");
        String sid = session.screen("Schedule").get(0).snapshot().sid();

        List<MobileSession.MobileFact> facts = session.foreground(true);
        assertEquals(List.of("$mobile_first_open", "$mobile_app_open",
                "$mobile_session_start"), names(facts));
        assertEquals(sid, facts.get(2).snapshot().sid());
        acceptFirstOpen(session);
        session.background();
        assertEquals(List.of("$mobile_app_open"),
                names(recreated("1.0", "10").foreground(true)));
    }

    @Test
    public void foregroundReturnAtTwentyNineMinutesFiftyNineSecondsKeepsSession() {
        MobileSession session = session("1.0", "10");
        String original = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        clock.advance(10_000);
        assertEquals(List.of("$mobile_session_engagement"), names(session.background()));
        clock.advance(29 * 60_000 + 59_000);

        List<MobileSession.MobileFact> facts = session.foreground(true);
        assertEquals(List.of("$mobile_app_open"), names(facts));
        assertEquals(original, facts.get(0).snapshot().sid());
    }

    @Test
    public void foregroundReturnAtThirtyMinutesEndsOldSessionAndStartsNewOne() {
        MobileSession session = session("1.0", "10");
        String original = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        session.background();
        clock.advance(30 * 60_000);

        List<MobileSession.MobileFact> facts = session.foreground(true);
        assertEquals(List.of("$mobile_session_end", "$mobile_app_open",
                "$mobile_session_start"), names(facts));
        assertEquals(original, facts.get(0).snapshot().sid());
        assertNotEquals(original, facts.get(1).snapshot().sid());
        assertEquals(facts.get(1).snapshot().sid(), facts.get(2).snapshot().sid());
    }

    @Test
    public void twoActivityResumesAndBackgroundsProduceOneEngagementDelta() throws Exception {
        MobileSession session = session("1.0", "10");
        session.foreground(true);
        assertTrue(session.foreground(true).isEmpty());
        clock.advance(10_000);

        List<MobileSession.MobileFact> firstBackground = session.background();
        assertEquals(List.of("$mobile_session_engagement"), names(firstBackground));
        assertEquals(10_000, firstBackground.get(0).eventProperties()
                .getLong("engagement_duration_ms"));
        assertTrue(session.background().isEmpty());
    }

    @Test
    public void checkpointEmitsAtCumulativeThresholdAndNeverOverlapsDeltas() throws Exception {
        MobileSession session = session("1.0", "10");
        session.foreground(true);
        clock.advance(9_999);
        assertTrue(session.checkpoint().isEmpty());
        clock.advance(1);
        List<MobileSession.MobileFact> threshold = session.checkpoint();
        assertEquals(10_000, threshold.get(0).eventProperties()
                .getLong("engagement_duration_ms"));
        assertTrue(session.checkpoint().isEmpty());
        clock.advance(2_000);
        assertEquals(2_000, session.checkpoint().get(0).eventProperties()
                .getLong("engagement_duration_ms"));
        clock.advance(1_000);
        assertEquals(1_000, session.background().get(0).eventProperties()
                .getLong("engagement_duration_ms"));
    }

    @Test
    public void foregroundPeriodsAccumulateTowardTenSecondThreshold() throws Exception {
        MobileSession session = session("1.0", "10");
        session.foreground(true);
        acceptFirstOpen(session);
        clock.advance(4_000);
        assertEquals(4_000, session.background().get(0).eventProperties()
                .getLong("engagement_duration_ms"));
        clock.advance(1_000);
        session.foreground(true);
        clock.advance(5_999);
        assertTrue(session.checkpoint().isEmpty());
        clock.advance(1);
        assertEquals(6_000, session.checkpoint().get(0).eventProperties()
                .getLong("engagement_duration_ms"));
        assertTrue(session.background().isEmpty());
    }

    @Test
    public void screenTransitionAttributesPriorTimeAndPreservesScreenAcrossCallbacks()
            throws Exception {
        MobileSession session = session("1.0", "10");
        session.foreground(true);
        assertEquals(List.of("$mobile_screen_view"), names(session.screen("Schedule")));
        clock.advance(4_000);

        List<MobileSession.MobileFact> change = session.screen("Visits");
        assertEquals(List.of("$mobile_session_engagement", "$mobile_screen_view"), names(change));
        assertEquals("Schedule", change.get(0).eventProperties().getString("screen_name"));
        assertEquals(4_000, change.get(0).eventProperties().getLong("engagement_duration_ms"));
        assertEquals("Visits", change.get(1).eventProperties().getString("screen_name"));
        assertTrue(session.screen("Visits").isEmpty());
        clock.advance(3_000);
        assertEquals("Visits", session.background().get(0).eventProperties()
                .getString("screen_name"));
    }

    @Test
    public void screenAfterThirtyMinutesUsesNewSessionAtSnapshotBoundary() {
        MobileSession session = session("1.0", "10");
        String oldSid = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        session.background();
        clock.advance(30 * 60_000);

        List<MobileSession.MobileFact> facts = session.screen("Schedule");
        assertEquals(List.of("$mobile_session_end", "$mobile_screen_view"), names(facts));
        assertEquals(oldSid, facts.get(0).snapshot().sid());
        assertNotEquals(oldSid, facts.get(1).snapshot().sid());
        assertEquals(facts.get(1).snapshot().sid(), session.snapshot().sid());
    }

    @Test
    public void manualSnapshotAfterTimeoutRetainsEndFactForPreviousSession() {
        MobileSession session = session("1.0", "10");
        String oldSid = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        session.background();
        clock.advance(30 * 60_000);

        MobileSession.MobileSnapshot manual = session.snapshot();
        assertNotEquals(oldSid, manual.sid());
        List<MobileSession.MobileFact> pending = session.pendingFacts();
        assertEquals("$mobile_session_end", pending.get(pending.size() - 1).eventName());
        assertEquals(oldSid, pending.get(pending.size() - 1).snapshot().sid());
    }

    @Test
    public void versionChangeAfterRecreationEmitsUpdateOnceWithPriorValues() throws Exception {
        MobileSession first = session("1.0", "10");
        first.foreground(true);
        acceptFirstOpen(first);
        first.background();
        clock.advance(1_000);
        MobileSession updated = recreated("2.0", "20");

        List<MobileSession.MobileFact> facts = updated.foreground(true);
        assertEquals(List.of("$mobile_app_update", "$mobile_app_open"), names(facts));
        JSONObject properties = facts.get(0).eventProperties();
        assertEquals("1.0", properties.getString("previous_app_version"));
        assertEquals("10", properties.getString("previous_app_build"));
        updated.background();
        assertEquals(List.of("$mobile_app_open"),
                names(recreated("2.0", "20").foreground(true)));
    }

    @Test
    public void processRecreationKeepsSessionAndOriginalUtcStartAcrossMidnight()
            throws Exception {
        clock.wall = 1_759_708_795_000L;
        MobileSession first = session("1.0", "10");
        String sid = first.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(first);
        clock.advance(10_000);
        first.background();
        clock.advance(1_000);

        List<MobileSession.MobileFact> reopened = recreated("1.0", "10").foreground(true);
        assertEquals(List.of("$mobile_app_open"), names(reopened));
        assertEquals(sid, reopened.get(0).snapshot().sid());
        assertEquals("2025-10-05T23:59:55.000Z", reopened.get(0).snapshot()
                .defaultProperties().getString("mobile_session_started_at"));
        assertEquals("2025-10-06T00:00:06.000Z", reopened.get(0).snapshot()
                .defaultProperties().getString("mobile_occurred_at"));
    }

    @Test
    public void recreationWithoutBackgroundRetainsSessionButDoesNotInventEngagement() {
        MobileSession first = session("1.0", "10");
        String sid = first.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(first);
        clock.advance(5_000);

        MobileSession reopened = recreated("1.0", "10");
        assertEquals(List.of("$mobile_app_open"), names(reopened.foreground(true)));
        assertEquals(sid, reopened.snapshot().sid());
        clock.advance(2_000);
        assertEquals(List.of("$mobile_session_engagement"), names(reopened.background()));
    }

    @Test
    public void optOutDoesNotConsumeFirstOpenAndOptInStartsFreshSession() {
        MobileSession session = session("1.0", "10");
        session.disable();
        assertTrue(session.foreground(true).isEmpty());
        assertTrue(session.snapshot() == null);
        session.enable();
        String sid = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        session.disable();
        session.enable();
        List<MobileSession.MobileFact> reopened = session.foreground(true);
        assertEquals(List.of("$mobile_app_open", "$mobile_session_start"), names(reopened));
        assertNotEquals(sid, reopened.get(0).snapshot().sid());
    }

    @Test
    public void optOutBeforeFirstOpenAcceptanceKeepsFirstOpenEligible() {
        MobileSession session = session("1.0", "10");
        session.foreground(true);
        session.disable();
        assertEquals(0, identity.getMobileState(TOKEN).pendingFacts.length());
        session.enable();

        assertEquals(List.of("$mobile_first_open", "$mobile_app_open",
                "$mobile_session_start"), names(session.foreground(true)));
    }

    @Test
    public void resetClearsPersistedSessionButPreservesFirstOpenAcceptance() {
        MobileSession session = session("1.0", "10");
        String sid = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        identity.reset();

        List<MobileSession.MobileFact> facts = recreated("1.0", "10").foreground(true);
        assertEquals(List.of("$mobile_app_open", "$mobile_session_start"), names(facts));
        assertNotEquals(sid, facts.get(0).snapshot().sid());
    }

    @Test
    public void persistedOptOutSuppressesFactsAndOptInStartsNewSession() {
        MobileSession session = session("1.0", "10");
        String sid = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        identity.optOutAndClear();

        MobileSession optedOut = recreated("1.0", "10");
        assertTrue(optedOut.foreground(true).isEmpty());
        assertTrue(optedOut.snapshot() == null);
        identity.setOptOut(false);
        optedOut.enable();
        List<MobileSession.MobileFact> facts = optedOut.foreground(true);
        assertEquals(List.of("$mobile_app_open", "$mobile_session_start"), names(facts));
        assertNotEquals(sid, facts.get(0).snapshot().sid());
    }

    @Test
    public void identityRotationWhileForegroundClosesOldSessionWithoutAnotherAppOpen()
            throws Exception {
        MobileSession session = session("1.0", "10");
        String sid = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        clock.advance(3_000);
        List<MobileSession.MobileFact> rotated = session.rotate();
        List<MobileSession.MobileFact> continued = session.continueAfterIdentityChange();

        assertEquals(List.of("$mobile_session_engagement", "$mobile_session_end"), names(rotated));
        assertEquals(List.of("$mobile_session_start"), names(continued));
        assertEquals(3_000, rotated.get(0).eventProperties().getLong("engagement_duration_ms"));
        assertEquals(sid, rotated.get(1).snapshot().sid());
        assertNotEquals(sid, continued.get(0).snapshot().sid());
        clock.advance(2_000);
        assertEquals(2_000, session.background().get(0).eventProperties()
                .getLong("engagement_duration_ms"));
    }

    @Test
    public void resetPreservesRotatedForegroundBoundaryAndBindsNewStartToNewVisitor()
            throws Exception {
        MobileSession session = session("1.0", "10");
        session.foreground(true);
        acceptFirstOpen(session);
        String oldVisitor = identity.getVisitorId();
        clock.advance(3_000);

        List<MobileSession.MobileFact> beforeReset = session.rotate();
        assertEquals(List.of("$mobile_session_engagement", "$mobile_session_end"),
                names(beforeReset));
        String replacementSid = session.snapshot().sid();
        identity.resetPreservingMobileSession(TOKEN);
        List<MobileSession.MobileFact> afterReset = session.continueAfterIdentityChange();

        assertEquals(List.of("$mobile_session_start"), names(afterReset));
        assertEquals(oldVisitor, beforeReset.get(1).visitorId());
        assertNotEquals(oldVisitor, afterReset.get(0).visitorId());
        assertEquals(replacementSid, afterReset.get(0).snapshot().sid());
        assertEquals(replacementSid, recreated("1.0", "10").snapshot().sid());
        assertEquals(oldVisitor, recreated("1.0", "10").pendingFacts().get(0).visitorId());
    }

    @Test
    public void elapsedClockMeasuresEngagementWhenWallClockMovesBackward() throws Exception {
        MobileSession session = session("1.0", "10");
        MobileSession.MobileSnapshot initial = session.foreground(true).get(0).snapshot();
        clock.elapsed += 10_000;
        clock.wall -= 60_000;

        MobileSession.MobileFact engagement = session.background().get(0);
        assertEquals(10_000, engagement.eventProperties().getLong("engagement_duration_ms"));
        assertTrue(engagement.snapshot().occurredAtMillis() >= initial.startedAtMillis());
    }

    @Test
    public void wallClockJumpWhileForegroundDoesNotExpireSession() throws Exception {
        MobileSession session = session("1.0", "10");
        String sid = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        clock.wall += 31 * 60_000;
        clock.elapsed += 5_000;

        List<MobileSession.MobileFact> facts = session.background();
        assertEquals(List.of("$mobile_session_engagement"), names(facts));
        assertEquals(sid, facts.get(0).snapshot().sid());
        assertEquals(5_000, facts.get(0).eventProperties().getLong("engagement_duration_ms"));
    }

    @Test
    public void largeWallRollbackRotatesBeforeManualSnapshotAndDefersFutureFacts()
            throws Exception {
        MobileSession session = session("1.0", "10");
        String oldSid = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        clock.wall -= 10 * 60_000;
        clock.elapsed += 1_000;

        MobileSession.MobileSnapshot snapshot = session.snapshot();
        assertNotEquals(oldSid, snapshot.sid());
        assertEquals(clock.wall, snapshot.startedAtMillis());
        assertEquals(clock.wall, snapshot.occurredAtMillis());
        assertTrue(session.pendingFacts().isEmpty());
        clock.advance(1_000);
        List<MobileSession.MobileFact> background = session.background();
        assertEquals(List.of("$mobile_session_engagement"), names(background));
        assertEquals(1_000, background.get(0).eventProperties()
                .getLong("engagement_duration_ms"));
        assertEquals(snapshot.sid(), background.get(0).snapshot().sid());
    }

    @Test
    public void largeWallRollbackBeforeForegroundUsesCurrentUtcStart() {
        MobileSession session = session("1.0", "10");
        String oldSid = session.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(session);
        session.background();
        clock.wall -= 10 * 60_000;

        List<MobileSession.MobileFact> reopened = session.foreground(true);
        assertEquals(List.of("$mobile_app_open", "$mobile_session_start"), names(reopened));
        assertNotEquals(oldSid, reopened.get(0).snapshot().sid());
        assertEquals(clock.wall, reopened.get(0).snapshot().occurredAtMillis());
    }

    @Test
    public void largeWallRollbackOnBackgroundRetainsPriorForegroundEngagement()
            throws Exception {
        MobileSession session = session("1.0", "10");
        String oldSid = session.foreground(true).get(0).snapshot().sid();
        String visitor = identity.getVisitorId();
        clock.elapsed += 12_000;
        clock.wall -= 10 * 60_000;

        List<MobileSession.MobileFact> facts = session.background();
        assertEquals(List.of("$mobile_session_engagement", "$mobile_session_start"),
                names(facts));
        assertEquals(12_000, facts.get(0).eventProperties()
                .getLong("engagement_duration_ms"));
        assertEquals(oldSid, facts.get(0).snapshot().sid());
        assertEquals(visitor, facts.get(0).visitorId());
        assertNotEquals(oldSid, facts.get(1).snapshot().sid());
        assertTrue(session.background().isEmpty());
        clock.advance(6 * 60_000);
        assertEquals(facts.get(0).id(), session.pendingFacts().get(3).id());
    }

    @Test
    public void capturedPauseRetainsRollbackGuardWhenLastActiveIsFuture()
            throws Exception {
        MobileSession session = session("1.0", "10");
        String oldSid = session.foreground(true).get(0).snapshot().sid();
        clock.advance(20 * 60_000);
        session.checkpoint();
        clock.advance(12_000);
        clock.wall -= 10 * 60_000;

        MobileSession.TimePoint pause = session.capturePausePoint();
        List<MobileSession.MobileFact> facts = session.background(pause);

        assertEquals(List.of("$mobile_session_engagement", "$mobile_session_start"),
                names(facts));
        assertEquals(12_000, facts.get(0).eventProperties()
                .getLong("engagement_duration_ms"));
        assertEquals(oldSid, facts.get(0).snapshot().sid());
        assertNotEquals(oldSid, facts.get(1).snapshot().sid());
    }

    @Test
    public void largeWallRollbackOnIdentityRotationRetainsPriorForegroundEngagement()
            throws Exception {
        MobileSession session = session("1.0", "10");
        String oldSid = session.foreground(true).get(0).snapshot().sid();
        clock.elapsed += 12_000;
        clock.wall -= 10 * 60_000;

        List<MobileSession.MobileFact> facts = session.rotate();
        assertEquals(List.of("$mobile_session_engagement", "$mobile_session_end"),
                names(facts));
        assertEquals(12_000, facts.get(0).eventProperties()
                .getLong("engagement_duration_ms"));
        assertEquals(oldSid, facts.get(0).snapshot().sid());
        assertNotEquals(oldSid, session.snapshot().sid());
    }

    @Test
    public void futurePendingFactHoldsLaterFactsUntilEarlierFactsCanQueue() {
        MobileSession session = session("1.0", "10");
        session.foreground(true);
        String firstId = session.pendingFacts().get(0).id();
        clock.wall -= 10 * 60_000;

        session.snapshot();
        assertTrue(session.pendingFacts().isEmpty());
        clock.advance(5 * 60_000);
        assertEquals(firstId, session.pendingFacts().get(0).id());
    }

    @Test
    public void factFetchedBeforeRollbackCannotQueueUntilItsCapturedTimeIsEligible()
            throws Exception {
        MobileSession session = session("1.0", "10");
        session.foreground(true);
        MobileSession.MobileFact fetched = session.pendingFacts().get(0);
        long occurredAt = fetched.snapshot().occurredAtMillis();
        clock.wall -= 10 * 60_000;

        assertTrue(session.pendingFacts().isEmpty());
        assertFalse(identity.enqueueMobileFact(TOKEN, fetched.id(), queued(fetched)));
        assertEquals(0, identity.getQueueSize());
        assertFalse(identity.getMobileState(TOKEN).firstOpenAccepted);
        assertEquals(fetched.id(), identity.getMobileState(TOKEN).pendingFacts
                .getJSONObject(0).getString("id"));

        clock.advance(6 * 60_000);
        assertTrue(identity.enqueueMobileFact(TOKEN, fetched.id(), queued(fetched)));
        assertTrue(identity.getMobileState(TOKEN).firstOpenAccepted);
        JSONObject queued = identity.getQueueSnapshot().getJSONObject(0);
        assertEquals(fetched.id(), queued.getString("distinct_id"));
        assertEquals(MobileSession.utc(occurredAt), queued.getJSONObject("defaultProperties")
                .getString("mobile_occurred_at"));
    }

    @Test
    public void tokenScopesPersistedSessionAndFirstOpenState() {
        MobileSession first = session("1.0", "10");
        String firstSid = first.foreground(true).get(0).snapshot().sid();
        acceptFirstOpen(first);

        MobileSession other = new MobileSession(identity, "different-token", "1.0", "10", clock);
        List<MobileSession.MobileFact> otherFacts = other.foreground(true);
        assertEquals(List.of("$mobile_first_open", "$mobile_app_open",
                "$mobile_session_start"), names(otherFacts));
        assertNotEquals(firstSid, otherFacts.get(0).snapshot().sid());
    }

    @Test
    public void unqueuedStartAndUpdateFactsReplayWithStableIdsAfterRecreation() {
        MobileSession first = session("1.0", "10");
        first.foreground(true);
        assertEquals(List.of("$mobile_first_open", "$mobile_app_open",
                "$mobile_session_start"), names(first.pendingFacts()));
        String firstId = first.pendingFacts().get(0).id();
        String visitor = first.pendingFacts().get(0).visitorId();

        MobileSession updated = recreated("2.0", "20");
        assertEquals(firstId, updated.pendingFacts().get(0).id());
        assertEquals(visitor, updated.pendingFacts().get(0).visitorId());
        updated.foreground(true);
        assertEquals(List.of("$mobile_first_open", "$mobile_app_open",
                "$mobile_session_start", "$mobile_app_update", "$mobile_app_open"),
                names(updated.pendingFacts()));
        MobileSession retry = recreated("2.0", "20");
        assertEquals("$mobile_app_update", retry.pendingFacts().get(3).eventName());
        assertEquals(updated.pendingFacts().get(3).id(), retry.pendingFacts().get(3).id());
    }

    @Test
    public void firstOpenQueueMoveCommitsAcceptanceAndCannotQueueTwice() throws Exception {
        MobileSession session = session("1.0", "10");
        session.foreground(true);
        MobileSession.MobileFact first = session.pendingFacts().get(0);
        assertFalse(identity.getMobileState(TOKEN).firstOpenAccepted);

        assertTrue(identity.enqueueMobileFact(TOKEN, first.id(), queued(first)));
        assertTrue(identity.getMobileState(TOKEN).firstOpenAccepted);
        assertEquals(1, identity.getQueueSize());
        assertEquals(first.id(), identity.getQueueSnapshot().getJSONObject(0)
                .getString("distinct_id"));
        assertFalse(identity.enqueueMobileFact(TOKEN, first.id(), queued(first)));
        assertEquals(1, identity.getQueueSize());
        assertEquals(List.of("$mobile_app_open", "$mobile_session_start"),
                names(recreated("1.0", "10").pendingFacts()));
    }

    @Test
    public void failedQueueCommitLeavesFirstOpenPendingForRetry() {
        AtomicBoolean failCommit = new AtomicBoolean();
        SharedPreferences wrapped = spy(preferences);
        doAnswer(invocation -> {
            SharedPreferences.Editor delegate = preferences.edit();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        Object result = method.invoke(delegate, args);
                        if ("commit".equals(method.getName()) && failCommit.get()) return false;
                        return result == delegate ? proxy : result;
                    });
        }).when(wrapped).edit();
        PersistentIdentity writer = new PersistentIdentity(
                CompletableFuture.completedFuture(wrapped));
        MobileSession session = new MobileSession(writer, TOKEN, "1.0", "10", clock);
        session.foreground(true);
        MobileSession.MobileFact first = session.pendingFacts().get(0);

        failCommit.set(true);
        assertThrows(IllegalStateException.class,
                () -> writer.enqueueMobileFact(TOKEN, first.id(), queued(first)));
        PersistentIdentity restored = new PersistentIdentity(
                CompletableFuture.completedFuture(preferences));
        assertFalse(restored.getMobileState(TOKEN).firstOpenAccepted);
        assertEquals(0, restored.getQueueSize());
        assertEquals(first.id(), new MobileSession(restored, TOKEN, "1.0", "10", clock)
                .pendingFacts().get(0).id());

        failCommit.set(false);
        assertTrue(writer.enqueueMobileFact(TOKEN, first.id(), queued(first)));
        PersistentIdentity accepted = new PersistentIdentity(
                CompletableFuture.completedFuture(preferences));
        assertEquals(1, accepted.getQueueSnapshot().length());
        assertTrue(accepted.getMobileState(TOKEN).firstOpenAccepted);
    }

    private void acceptFirstOpen(MobileSession session) {
        for (MobileSession.MobileFact fact : session.pendingFacts()) {
            assertTrue(identity.enqueueMobileFact(TOKEN, fact.id(), queued(fact)));
            if ("$mobile_first_open".equals(fact.eventName())) {
                return;
            }
        }
        throw new AssertionError("missing pending first-open");
    }

    private static JSONObject queued(MobileSession.MobileFact fact) {
        try {
            return new JSONObject()
                    .put("event", fact.eventName())
                    .put("visitor_id", fact.visitorId())
                    .put("distinct_id", fact.id())
                    .put("eventProperties", fact.eventProperties())
                    .put("userProperties", JSONObject.NULL)
                    .put("defaultProperties", fact.snapshot().defaultProperties());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private MobileSession session(String version, String build) {
        return new MobileSession(identity, TOKEN, version, build, clock);
    }

    private MobileSession recreated(String version, String build) {
        identity = new PersistentIdentity(CompletableFuture.completedFuture(preferences),
                clock::wallMillis);
        return session(version, build);
    }

    private static List<String> names(List<MobileSession.MobileFact> facts) {
        List<String> names = new ArrayList<>();
        for (MobileSession.MobileFact fact : facts) {
            names.add(fact.eventName());
        }
        return names;
    }

    private static final class FakeClock implements MobileSession.Clock {
        long wall;
        long elapsed;

        FakeClock(long start) {
            wall = start;
            elapsed = 100_000L;
        }

        @Override
        public long wallMillis() {
            return wall;
        }

        @Override
        public long elapsedMillis() {
            return elapsed;
        }

        void advance(long duration) {
            wall += duration;
            elapsed += duration;
        }
    }
}
