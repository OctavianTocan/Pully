package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.LinkPlaces
import com.octaviantocan.pulls.data.RepoConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IncomingLinkInboxTest {
    private val inbox = IncomingLinkInbox()
    private val navigation = AppNavigation()
    private fun audience(server: String = "https://server.example", account: String? = "a@example.test", access: String = "one", session: String = "session") =
        requireNotNull(incomingLinkAudience(RepoConfiguration(server, mapOf("CF-Access-Client-Secret" to access, "X-Pully-Session" to session)), account, account != null))
    private suspend fun open(audience: IncomingLinkAudience = audience(), current: () -> Boolean = { true }, close: suspend () -> Unit = {}, invalid: () -> Unit = {}) =
        inbox.openPending(audience, navigation, current, close, invalid)

    @Test fun signedOutIntentWaitsThenOpensOverHomeAfterSameSourceSignIn() = runTest {
        inbox.receive("https://github.com/octo/repo/pull/12", audience(account = null))
        val arrival = inbox.pending
        assertFalse(open(current = { false }))
        assertSame(arrival, inbox.pending)
        assertTrue(open())
        assertEquals(listOf(Screen.Home, Screen.Review(OpenTarget.Pull("octo", "repo", 12, "github.com"), from = "link")), navigation.stack.toList())
        assertNull(inbox.pending)
        navigation.nav.back()
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
    }

    @Test fun anotherEndpointAccessPolicyOrAccountCannotReceiveTheOldIntent() = runTest {
        navigation.nav.settings()
        var sheetsClosed = 0
        listOf(audience(server = "https://other.example"), audience(access = "two"), audience(account = "b@example.test")).forEach { replacement ->
            inbox.receive("https://server.example/-/abc123", audience())
            assertTrue(open(replacement, close = { sheetsClosed++ }))
            assertNull(inbox.pending)
            assertEquals(listOf(Screen.Home, Screen.Settings), navigation.stack.toList())
            assertEquals(0, sheetsClosed)
        }
    }

    @Test fun renewedSessionStillReceivesTheExplicitIntent() = runTest {
        inbox.receive("https://github.com/octo/repo/pull/12", audience(session = "old"))
        assertTrue(open(audience(session = "new")))
        assertTrue(navigation.stack.last() is Screen.Review)
    }

    @Test fun sameTargetReopensWithANewEventAndResultInsteadOfDuplicatingTheRoute() = runTest {
        navigation.nav.openTarget(OpenTarget.Pull("octo", "repo", 12, "github.com"))
        val route = navigation.stack.last()
        inbox.receive("https://github.com/octo/repo/pull/12", audience(), "brief")
        open()
        val first = assertNotNull(inbox.reopened)
        assertEquals("brief", first.aiResult)
        assertEquals(2, navigation.stack.size)
        assertSame(route, navigation.stack.last())
        inbox.receive("https://github.com/octo/repo/pull/12", audience(), "brief")
        open()
        assertNotSame(first, inbox.reopened)
    }

    @Test fun closingSheetsCannotDeliverIntoAReplacementOwnerOrConsumeANewerArrival() = runTest {
        inbox.receive("https://github.com/octo/repo/pull/12", audience())
        var active = true
        assertFalse(open(current = { active }, close = { active = false }))
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
        assertNotNull(inbox.pending)
        active = true
        assertFalse(open(close = { inbox.receive("https://github.com/octo/repo/pull/13", audience()) }))
        assertTrue(assertNotNull(assertNotNull(inbox.pending).text).endsWith("/13"))
        assertTrue(open())
        assertEquals("octo/repo/13", (navigation.stack.last() as Screen.Review).target.serverTarget)
    }

    @Test fun suspendingSheetExitRechecksTheSourceAndPreservesANewerArrival() = runTest {
        var active = true
        val closing = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        inbox.receive("https://github.com/octo/repo/pull/12", audience())
        val drain = launch {
            assertFalse(open(current = { active }, close = { closing.complete(Unit); closed.await() }))
        }
        closing.await()
        active = false
        inbox.receive("https://github.com/octo/repo/pull/13", audience())
        val newer = inbox.pending
        closed.complete(Unit)
        drain.join()
        assertSame(newer, inbox.pending)
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
        active = true
        assertTrue(open(current = { active }))
        assertEquals("octo/repo/13", (navigation.stack.last() as Screen.Review).target.serverTarget)
    }

    @Test fun repositoryClosesSettingsAndAnInvalidLinkReportsOnceWithoutNavigation() = runTest {
        navigation.nav.settings()
        var closed = 0
        inbox.receive("https://github.com/octo/repo", audience())
        assertTrue(open(close = { closed++ }))
        assertEquals(listOf(Screen.Home, Screen.Repo("octo", "repo")), navigation.stack.toList())
        assertEquals(1, closed)
        var failures = 0
        inbox.receive("https://github.com/octo/repo/issues/12", audience())
        assertTrue(open(invalid = { failures++ }))
        assertFalse(open(invalid = { failures++ }))
        assertEquals(1, failures)
        assertEquals(2, navigation.stack.size)
    }

    @Test fun anIntentReceivedBeforeConfigurationCanBeExplicitlyOpenedAfterSetup() = runTest {
        inbox.receive("https://github.com/octo/repo/commit/abcdef123", null)
        assertTrue(open())
        assertTrue((navigation.stack.last() as Screen.Review).target is OpenTarget.Commit)
        inbox.clear()
        assertNull(inbox.pending)
        assertNull(inbox.reopened)
    }

    @Test fun explicitDestinationsRetainTheirQueryFragmentFileSideAndLine() {
        val anchor = LinkPlaces.fileAnchor("src/main.kt")
        val destinations = listOf(
            "https://github.com/octo/repo/pull/12/files/abcdef1..abcdef2#diff-${anchor}L42" to LinkPlace.Files(anchor, 42, left = true, commits = "abcdef1..abcdef2"),
            "https://github.com/octo/repo/pull/12/checks?check_run_id=77" to LinkPlace.Checks(77),
            "https://github.com/octo/repo/pull/12#discussion_r91" to LinkPlace.CodeComment(91),
            "https://github.com/octo/repo/pull/12#issuecomment-92" to LinkPlace.Comment(92),
            "https://github.com/octo/repo/pull/12/commits/abcdef1#diff-${anchor}R18" to LinkPlace.Commits("abcdef1", LinkPlace.Files(anchor, 18)),
        )
        destinations.forEach { (url, expected) ->
            val destination = sharedLinkScreen("Please read $url", "brief") as Screen.Review
            assertEquals(expected, destination.place?.place)
            assertEquals("brief", destination.aiResult)
        }
    }

    @Test fun nativeRejectionsReportOnceWithoutRetainingARejectedUrl() = runTest {
        var invalid = 0
        inbox.reject(audience())
        assertNull(assertNotNull(inbox.pending).text)
        assertTrue(open(invalid = { invalid++ }))
        assertFalse(open(invalid = { invalid++ }))
        assertEquals(1, invalid)
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
    }

    @Test fun sourceRetirementKeepsOnlyAudienceCheckedPendingIntents() = runTest {
        navigation.nav.openTarget(OpenTarget.Pull("octo", "repo", 12, "github.com"))
        inbox.receive("https://github.com/octo/repo/pull/12", audience(account = "Reader@Example.test"))
        assertTrue(open(audience(account = "reader@example.test")))
        assertNotNull(inbox.reopened)
        inbox.receive("https://github.com/octo/repo/pull/13", audience(account = null))
        val pending = inbox.pending
        inbox.retireSource()
        assertNull(inbox.reopened)
        assertSame(pending, inbox.pending)
        assertTrue(open(audience(account = "reader@example.test")))
        assertEquals("octo/repo/13", (navigation.stack.last() as Screen.Review).target.serverTarget)
    }
}
