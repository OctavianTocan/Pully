package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.ui.explore.GuestBrowseSession
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeGuestLinkHandoffTest {
    @Test
    fun displayedFallbackRetainsItsExactPlaceAndResultThroughPrivateSourceReset() = runTest {
        val inbox = IncomingLinkInbox()
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val session = GuestBrowseSession(RpcTransport { error("Fallback cannot issue a network request") }, backgroundScope, NativeEntryState(false).guestSource()!!, navigation)
        val handoff = NativeGuestLinkHandoff(inbox, navigation) { session.current }
        inbox.receive("https://github.com/octo/repo/pull/12#discussion_r123", null, "brief")
        val arrival = inbox.pending!!
        val fallback = sharedLinkScreen(arrival.text!!, arrival.aiResult)!!
        session.openIncoming(fallback, { inbox.pending === arrival }, {}) { handoff.committed(fallback, arrival) }
        assertNull(inbox.pending)
        handoff.prepareExistingEntry()
        val waiting = inbox.pending!!
        session.close()
        navigation.reset(Screen.Home) // Native AccountRuntime invalidation retires its private route owner.
        val audience = incomingLinkAudience(RepoConfiguration("https://private.example"), "reader@example.com", true)!!
        assertTrue(inbox.openPending(audience, navigation, { true }, {}, { error("The exact link must remain valid") }))
        val review = navigation.stack.last() as Screen.Review
        val before = fallback as Screen.Review
        assertNotNull(before.place)
        assertEquals(before.target, review.target)
        assertEquals(before.place?.place, review.place?.place)
        assertEquals("brief", review.aiResult)
        assertEquals(arrival.text, waiting.text)
        assertEquals(2, navigation.stack.size)
        assertNull(inbox.pending)
    }

    @Test
    fun anotherPendingArrivalOrAPoppedFallbackCannotBeReplacedByTheOldHandoff() {
        val inbox = IncomingLinkInbox()
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        var current = true
        val handoff = NativeGuestLinkHandoff(inbox, navigation) { current }
        inbox.receive("https://github.com/octo/repo", null)
        val arrival = inbox.pending!!
        val fallback = sharedLinkScreen(arrival.text!!)!!
        navigation.stack.add(fallback)
        handoff.committed(fallback, arrival)
        inbox.receive("https://github.com/newer/repo", null)
        val newer = inbox.pending
        handoff.prepareExistingEntry()
        assertSame(newer, inbox.pending)
        inbox.consume(newer!!)
        navigation.nav.back()
        handoff.prepareExistingEntry()
        assertNull(inbox.pending)
        navigation.stack.add((fallback as Screen.Repo).copy())
        handoff.prepareExistingEntry()
        assertNull(inbox.pending)
        current = false
        handoff.committed(navigation.stack.last(), arrival)
        handoff.prepareExistingEntry()
        assertNull(inbox.pending)
    }

    @Test
    fun privateAudienceIsStillRejectedBeforeTheReplacementSourcesSheetsClose() = runTest {
        val inbox = IncomingLinkInbox()
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        fun audience(server: String) = incomingLinkAudience(RepoConfiguration(server), "reader@example.com", true)!!
        val handoff = NativeGuestLinkHandoff(inbox, navigation) { true }
        inbox.receive("https://github.com/octo/repo", audience("https://old.example"))
        val arrival = inbox.pending!!
        val screen = sharedLinkScreen(arrival.text!!)!!
        navigation.stack.add(screen)
        handoff.committed(screen, arrival)
        handoff.prepareExistingEntry()
        navigation.reset(Screen.Home)
        navigation.nav.settings()
        assertTrue(inbox.openPending(audience("https://new.example"), navigation, { true }, { error("Old audience closed current sheets") }, { error("Valid but retired intent is not malformed") }))
        assertEquals(listOf(Screen.Home, Screen.Settings), navigation.stack.toList())
        assertNull(inbox.pending)
    }

    @Test
    fun equalAlreadyDisplayedRouteConsumesTheArrivalAndKeepsItsActualStackIdentity() {
        val inbox = IncomingLinkInbox()
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val displayed = Screen.Repo("octo", "repo")
        navigation.stack.add(displayed)
        val handoff = NativeGuestLinkHandoff(inbox, navigation) { true }
        inbox.receive("https://github.com/octo/repo", null)
        val arrival = inbox.pending!!
        val parsed = sharedLinkScreen(arrival.text!!)!!
        assertNotSame(displayed, parsed)
        handoff.committed(parsed, arrival)
        assertNull(inbox.pending)
        assertSame(displayed, navigation.stack.last())
        handoff.prepareExistingEntry()
        assertEquals(arrival.text, inbox.pending?.text)
    }
}
