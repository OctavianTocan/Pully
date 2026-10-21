package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class CheckNotificationsTest {
    private fun check(outcome: String, id: String = "check-1", start: String = "start-1", end: String = "end-1") = ObservedCheck(id, "Build", outcome, start, end)
    private fun snapshot(check: ObservedCheck, head: String = "head-a") = CheckSnapshot(head, listOf(check))

    @Test fun firstEnableBaselinesOldFailuresAndIdenticalPollsStaySilent() {
        val baseline = evaluateChecks(CheckNotificationState(), snapshot(check("failure")))
        assertTrue(baseline.state.initialized)
        assertTrue(baseline.events.isEmpty())
        assertTrue(evaluateChecks(baseline.state, snapshot(check("failure"))).events.isEmpty())
    }

    @Test fun newFailureIsRetriedUntilDeliveredThenNeverRepeated() {
        val baseline = evaluateChecks(CheckNotificationState(), snapshot(check("in_progress"))).state
        val failed = evaluateChecks(baseline, snapshot(check("failure")))
        assertEquals(1, failed.events.size)
        assertEquals(1, evaluateChecks(failed.state, snapshot(check("failure"))).events.size)
        assertTrue(evaluateChecks(failed.state.delivered(failed.events), snapshot(check("failure"))).events.isEmpty())
    }

    @Test fun sameIdRerunAndUnobservedTimestampRerunCanAlertAgain() {
        val baseline = evaluateChecks(CheckNotificationState(), snapshot(check("failure"))).state
        val pending = evaluateChecks(baseline, snapshot(check("in_progress"))).state
        assertEquals(1, evaluateChecks(pending, snapshot(check("failure"))).events.size)
        assertEquals(1, evaluateChecks(baseline, snapshot(check("failure", start = "start-2", end = "end-2"))).events.size)
    }

    @Test fun headAndStableCheckIdentityArePartOfTheOccurrence() {
        val baseline = evaluateChecks(CheckNotificationState(), snapshot(check("failure"))).state
        assertEquals(1, evaluateChecks(baseline, snapshot(check("failure"), "head-b")).events.size)
        assertEquals(1, evaluateChecks(baseline, snapshot(check("failure", id = "check-2"))).events.size)
    }

    @Test fun onlyRecognizedFailuresQualifyAndKeepTheirReason() {
        val baseline = evaluateChecks(CheckNotificationState(), CheckSnapshot("head-a", emptyList())).state
        for (status in listOf("cancelled", "skipped", "neutral", "stale", "success", "pending", "in_progress", "unknown", "")) {
            assertTrue(evaluateChecks(baseline, snapshot(check(status))).events.isEmpty(), status)
        }
        for ((status, reason) in listOf("failure" to CheckFailure.Failed, "timed_out" to CheckFailure.TimedOut, "action_required" to CheckFailure.ActionRequired, "startup_failure" to CheckFailure.StartupFailure)) {
            assertEquals(reason, evaluateChecks(baseline, snapshot(check(status))).events.single().failure)
        }
    }

    @Test fun partialOrUnknownHeadCannotEstablishOrAdvanceABaseline() {
        val empty = CheckNotificationState()
        assertFalse(evaluateChecks(empty, CheckSnapshot("head", listOf(check("failure")), complete = false)).state.initialized)
        assertEquals(empty, evaluateChecks(empty, snapshot(check("failure"), "")).state)
        val baseline = evaluateChecks(empty, snapshot(check("success"))).state
        assertEquals(baseline, evaluateChecks(baseline, CheckSnapshot("head-b", listOf(check("failure")), false)).state)
    }

    @Test fun unknownInterludeCannotInventARerunAndPersistenceKeepsDedupe() {
        val baseline = evaluateChecks(CheckNotificationState(), snapshot(check("failure"))).state
        val unknown = evaluateChecks(baseline, snapshot(check("unknown"))).state
        assertTrue(evaluateChecks(unknown, snapshot(check("failure"))).events.isEmpty())
        val pending = evaluateChecks(baseline, snapshot(check("in_progress"))).state
        val failure = evaluateChecks(pending, snapshot(check("failure")))
        val saved = Json.encodeToString(failure.state.delivered(failure.events))
        val restored = Json.decodeFromString<CheckNotificationState>(saved)
        assertTrue(evaluateChecks(restored, snapshot(check("failure"))).events.isEmpty())
    }

    @Test fun exactLinksRemainOnTheSubscribedPrAndUnsafeLinksFallBack() {
        val base = "https://github.com/a/b/pull/12/checks"
        assertEquals("$base?check_run_id=123", checkNotificationUrl("a", "b", 12, "$base?check_run_id=123"))
        for (url in listOf("https://evil.test/a/b/pull/12/checks", "https://github.com/a/b/pull/13/checks?check_run_id=123", "https://github.com/a/b/actions/runs/1", "$base?check_run_id=1&next=evil", "https://user@github.com/a/b/pull/12/checks")) {
            assertEquals(base, checkNotificationUrl("a", "b", 12, url))
        }
    }

    @Test fun accountServerCredentialAndSubscriptionChangesRejectInflightDelivery() {
        val request = CheckNotificationSource("server-a/account-a", "session-a", 1)
        assertTrue(request.accepts(request, "follow-1", "follow-1"))
        assertFalse(request.accepts(request.copy(key = "server-b/account-a"), "follow-1", "follow-1"))
        assertFalse(request.accepts(request.copy(key = "server-a/account-b"), "follow-1", "follow-1"))
        assertFalse(request.accepts(request.copy(auth = "new-session"), "follow-1", "follow-1"))
        // Sign-out followed by the same source still invalidates the previous request.
        assertFalse(request.accepts(request.copy(epoch = 2), "follow-1", "follow-1"))
        assertFalse(request.accepts(request, "follow-1", null))
        assertFalse(request.accepts(request, "follow-1", "follow-2"))
    }
}
