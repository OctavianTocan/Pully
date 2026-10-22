package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.ui.home.NativeSharedLinkHandoff
import com.octaviantocan.pulls.ui.home.ShareImportSource
import com.octaviantocan.pulls.ui.home.SharedLinkImport
import com.octaviantocan.pulls.ui.home.encodeSharedLink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeShareActivationTest {
    private val audience = requireNotNull(incomingLinkAudience(RepoConfiguration("https://server.example", emptyMap()), "reader@example.test", true))
    private fun payload(number: Int) = requireNotNull(encodeSharedLink("https://github.com/octo/repo/pull/$number"))

    @Test fun initialBackgroundReplayDoesNotForgetAColdLaunchDestination() {
        val inbox = IncomingLinkInbox()
        inbox.receive("https://github.com/octo/repo/pull/22", audience)
        val explicit = inbox.pending
        val native = inbox.activationObserver()
        native.stopped()
        native.started()
        native.resumed()
        inbox.receiveShared(payload(11), audience)
        assertSame(explicit, inbox.pending)
    }

    @Test fun repeatedBackgroundReportsPreserveAnExplicitArrivalBeforeTheNextResume() {
        val inbox = IncomingLinkInbox()
        val native = inbox.activationObserver()
        native.resumed()
        native.stopped()
        inbox.receive("https://github.com/octo/repo/pull/22", audience)
        val explicit = inbox.pending
        native.stopped()
        native.started()
        native.resumed()
        inbox.receiveShared(payload(11), audience)
        assertSame(explicit, inbox.pending)
    }

    @Test fun aLaterActualForegroundActivationCanImportItsLegitimateShare() = runTest {
        val inbox = IncomingLinkInbox()
        val navigation = AppNavigation()
        val native = inbox.activationObserver()
        native.resumed()
        inbox.receive("https://github.com/octo/repo/pull/22", audience)
        assertTrue(inbox.openPending(audience, navigation, { true }, {}, { error("A valid explicit URL must open.") }))
        native.stopped()
        native.started()
        native.resumed()
        inbox.receiveShared(payload(11), audience)
        assertTrue(inbox.openPending(audience, navigation, { true }, {}, { error("A valid canonical share must open.") }))
        assertEquals("octo/repo/11", (navigation.stack.last() as Screen.Review).target.serverTarget)
        assertEquals(3, navigation.stack.size)
    }

    @Test fun anExplicitRejectionRemainsOneErrorWhenAnOlderInvalidShareIsDiscarded() = runTest {
        val inbox = IncomingLinkInbox()
        val navigation = AppNavigation()
        inbox.reject(audience)
        val explicit = inbox.pending
        inbox.receiveShared("not canonical data", audience)
        assertSame(explicit, inbox.pending)
        var invalid = 0
        assertTrue(inbox.openPending(audience, navigation, { true }, {}, { invalid++ }))
        assertEquals(1, invalid)
        assertNull(inbox.pending)
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
    }

    @Test fun anExplicitArrivalDuringNativeTakeWinsBeforeTheSharedCallbackPublishes() {
        val inbox = IncomingLinkInbox()
        val source = ShareImportSource("a".repeat(64), "https://server.example", 1, 1, 1)
        val native = object : NativeSharedLinkHandoff {
            override fun take(namespace: String, server: String): String {
                inbox.receive("https://github.com/octo/repo/pull/22", audience)
                return payload(11)
            }
            override fun revoke() = error("Import cannot replace source authority.")
        }
        SharedLinkImport(native, { source }) { inbox.receiveShared(it, audience) }.sync()
        assertTrue(requireNotNull(inbox.pending).text!!.endsWith("/22"))
    }
}
