package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcObserver
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.ui.explore.GuestBrowseSession
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import com.octaviantocan.pulls.ui.settings.AccountPhase
import com.octaviantocan.pulls.ui.settings.AccountRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeGuestLinkAdmissionTest {
    @Test
    fun oldAudienceCannotLeaveItsPublicFallbackInAnAlreadyVerifiedReplacement() = runTest {
        val fixture = Admission(this)
        runCurrent()
        assertEquals(AccountPhase.Checking, fixture.runtime.state.phase)
        fixture.inbox.receive("https://github.com/old-owner/repo/pull/12", fixture.audience("https://old.example"))
        fixture.inbox.retireSource()
        fixture.displayInGuest()
        fixture.returnToExisting()
        assertEquals(listOf(Screen.Home), fixture.navigation.stack.toList())

        fixture.finishVerification()
        assertTrue(fixture.inbox.openPending(fixture.audience(), fixture.navigation, { fixture.privateCurrent() },
            { error("A rejected audience cannot close replacement sheets") }, { error("The old link is valid") }))
        assertEquals(listOf(Screen.Home), fixture.navigation.stack.toList())
        assertNull(fixture.inbox.pending)
        assertEquals(listOf("account-status", "account-status", "account-me"), fixture.requests.map(::method))
        assertTrue(fixture.requests.all { it.url.startsWith(fixture.storage.server) })
        assertEquals("fixture-session", fixture.storage.token)
        fixture.owner.close()
    }

    @Test
    fun sameAudienceOpensTheExactResultOnlyAfterRealVerification() = runTest {
        val fixture = Admission(this)
        runCurrent()
        fixture.inbox.receive("https://github.com/octo/repo/pull/12#discussion_r123", fixture.audience(), "brief")
        val fallback = fixture.displayInGuest() as Screen.Review
        fixture.returnToExisting()
        assertEquals(listOf(Screen.Home), fixture.navigation.stack.toList())
        fixture.finishVerification()
        var closedSheets = 0
        assertTrue(fixture.inbox.openPending(fixture.audience(), fixture.navigation, { fixture.privateCurrent() },
            { closedSheets++ }, { error("The retained link must remain valid") }))
        val opened = fixture.navigation.stack.last() as Screen.Review
        assertEquals(fallback.target, opened.target)
        assertNotNull(opened.place)
        assertEquals(fallback.place?.place, opened.place?.place)
        assertEquals("brief", opened.aiResult)
        assertEquals(1, closedSheets)
        assertEquals(2, fixture.navigation.stack.size)
        fixture.owner.close()
    }

    @Test
    fun aNewArrivalWinsOverTheDisplayedFallbackDuringTheRealEntryRoundTrip() = runTest {
        val fixture = Admission(this)
        runCurrent()
        fixture.inbox.receive("https://github.com/old/repo", fixture.audience(), "brief")
        fixture.displayInGuest()
        fixture.inbox.receive("https://github.com/new/repo/pull/42#discussion_r456", fixture.audience(), "new-result")
        val newer = fixture.inbox.pending
        fixture.returnToExisting()
        assertSame(newer, fixture.inbox.pending)
        assertEquals(listOf(Screen.Home), fixture.navigation.stack.toList())
        fixture.finishVerification()
        assertTrue(fixture.inbox.openPending(fixture.audience(), fixture.navigation, { fixture.privateCurrent() }, {},
            { error("The new link must remain valid") }))
        val opened = fixture.navigation.stack.last() as Screen.Review
        assertEquals("new-result", opened.aiResult)
        val expected = sharedLinkScreen(newer!!.text!!, newer.aiResult) as Screen.Review
        assertEquals(expected.target, opened.target)
        assertEquals(expected.place?.place, opened.place?.place)
        fixture.owner.close()
    }

    private class Admission(private val scope: TestScope) {
        val storage = EntryAccountStorage()
        val navigation = AppNavigation()
        val inbox = IncomingLinkInbox()
        val requests = mutableListOf<RpcRequest>()
        private val status = CompletableDeferred<RpcResponse>()
        private var invalidations = 0
        lateinit var runtime: AccountRuntime
        lateinit var privateCurrent: () -> Boolean
        private lateinit var guest: GuestBrowseSession
        private lateinit var handoff: NativeGuestLinkHandoff
        val owner = NativeWorkspaceOwner(NativeEntryState(true), navigation,
            createGuest = { source, stack, current ->
                guest = GuestBrowseSession(RpcTransport { error("A link fallback starts no public request") }, scope.backgroundScope, source, stack)
                handoff = NativeGuestLinkHandoff(inbox, stack) { current() && guest.current }
                object : NativeWorkspace { override fun close() { handoff.clear(); guest.close() } }
            },
            createExisting = { stack, current ->
                privateCurrent = current
                runtime = AccountRuntime(storage, RpcTransport { request ->
                    requests += request
                    when (method(request)) {
                        "account-status" -> status.await()
                        "account-me" -> RpcResponse(200, """{"result":{"email":"reader@example.com"}}""")
                        else -> error("A fallback issued a private target request before admission")
                    }
                }, scope.backgroundScope, { "" }, {
                    invalidations++
                    inbox.retireSource()
                    stack.reset(Screen.Home)
                }, {}, RpcObserver { _, _, _, _ -> })
                runtime.start()
                object : NativeWorkspace { override fun close() = runtime.close() }
            },
            selected = {},
        )

        fun audience(server: String = storage.server): IncomingLinkAudience = incomingLinkAudience(
            RepoConfiguration(server, storage.connection(false)!!.headers), "reader@example.com", true,
        )!!

        suspend fun displayInGuest(): Screen {
            owner.select(NativeEntryMode.Explore)
            val arrival = inbox.pending!!
            val fallback = sharedLinkScreen(arrival.text!!, arrival.aiResult)!!
            guest.openIncoming(fallback, { inbox.pending === arrival }, {}) { handoff.committed(fallback, arrival) }
            assertNull(inbox.pending)
            assertSame(fallback, navigation.stack.last())
            return fallback
        }

        fun returnToExisting() {
            handoff.prepareExistingEntry()
            owner.select(NativeEntryMode.ExistingServer)
        }

        fun finishVerification() {
            val before = invalidations
            status.complete(RpcResponse(200, """{"result":{"hasAccount":true}}"""))
            scope.runCurrent()
            assertEquals(AccountPhase.Ready, runtime.state.phase)
            assertFalse(storage.account().verificationRequired)
            assertEquals(before, invalidations, "An already verified matching account must not hide the route bug with a source reset")
            assertEquals(1, storage.observers.size)
        }
    }

    companion object {
        private fun method(request: RpcRequest): String = Json.parseToJsonElement(request.body).jsonObject.getValue("name").jsonPrimitive.content
    }
}
