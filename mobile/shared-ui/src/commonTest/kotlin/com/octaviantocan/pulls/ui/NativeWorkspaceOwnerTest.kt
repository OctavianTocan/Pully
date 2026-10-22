package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.data.*
import com.octaviantocan.pulls.ui.explore.GuestBrowseSession
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import com.octaviantocan.pulls.ui.settings.AccountRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeWorkspaceOwnerTest {
    @Test
    fun freshPublicFactoryRunsNoPrivateAccountObserverOrRpcAndUsesTheSuppliedStack() = runTest {
        val storage = EntryAccountStorage()
        val entry = NativeEntryState(false, NativeEntryMode.ExistingServer)
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val requests = mutableListOf<RpcRequest>()
        val transport = RpcTransport { request ->
            requests += request
            assertEquals(RpcMethod.Get, request.method)
            assertTrue(request.url.startsWith("https://api.github.com/"))
            assertTrue(request.headers.keys.none { it.equals("Authorization", true) || it.equals("X-Pully-Session", true) || it.startsWith("CF-Access", true) })
            RpcResponse(200, """{"items":[],"incomplete_results":false}""", "application/json")
        }
        lateinit var session: GuestBrowseSession
        var privateCreated = 0
        val owner = NativeWorkspaceOwner(entry, navigation,
            createGuest = { source, stack, current ->
                assertSame(navigation, stack)
                assertTrue(current())
                session = GuestBrowseSession(transport, backgroundScope, source, stack)
                object : NativeWorkspace { override fun close() = session.close() }
            },
            createExisting = { _, _ -> privateCreated++; error("Fresh public startup constructed private services") },
            selected = {},
        )
        session.foreground(); runCurrent()
        assertEquals(1, requests.size)
        assertEquals(0, privateCreated)
        assertTrue(storage.observers.isEmpty())
        assertSame(navigation, owner.navigation)
        owner.close()
        session.foreground(); runCurrent()
        assertFalse(session.current)
        assertEquals(1, requests.size)
    }

    @Test
    fun explicitExploreClosesTheActualAccountAndSettlesBeforeRevokingItsCallbacks() = runTest {
        val storage = EntryAccountStorage()
        val entry = NativeEntryState(true)
        val navigation = AppNavigation()
        val requests = mutableListOf<RpcRequest>()
        val order = mutableListOf<String>()
        lateinit var runtime: AccountRuntime
        lateinit var privateCurrent: () -> Boolean
        val owner = NativeWorkspaceOwner(entry, navigation,
            createGuest = { source, stack, current ->
                assertSame(navigation, stack)
                assertTrue(storage.observers.isEmpty())
                assertFalse(privateCurrent())
                val session = GuestBrowseSession(RpcTransport { error("No public request was started") }, backgroundScope, source, stack)
                order += "guest"
                object : NativeWorkspace { override fun close() = session.close() }
            },
            createExisting = { stack, current ->
                assertSame(navigation, stack)
                privateCurrent = current
                runtime = AccountRuntime(storage, RpcTransport { request ->
                    requests += request
                    if (request.body.contains("account-status")) RpcResponse(200, """{"result":{"hasAccount":true}}""")
                    else RpcResponse(200, """{"result":{"email":"reader@example.com"}}""")
                }, backgroundScope, { "" }, {}, {}, RpcObserver { _, _, _, _ -> })
                runtime.start()
                object : NativeWorkspace {
                    override fun beforeLeave() { assertTrue(current()); order += "settled" }
                    override fun close() { assertFalse(current()); order += "closed"; runtime.close() }
                }
            },
            selected = { assertEquals(listOf(Screen.Explore), navigation.stack.toList()); order += "selected" },
        )
        runCurrent()
        assertEquals(2, requests.size)
        assertEquals(1, storage.observers.size)
        navigation.nav.settings()
        owner.select(NativeEntryMode.Explore)
        assertEquals(listOf("settled", "closed", "selected", "guest"), order)
        assertEquals("fixture-session", storage.token)
        assertFalse(privateCurrent())
        runtime.refresh(); runCurrent()
        assertEquals(2, requests.size)
        owner.close(); owner.close()
        assertTrue(storage.observers.isEmpty())
    }

    @Test
    fun entryRoundTripRejectsLatePublicResultsAndOldCallbacksWithoutReplacingNavigation() = runTest {
        val entry = NativeEntryState(false)
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val response = CompletableDeferred<RpcResponse>()
        var first: GuestBrowseSession? = null
        val sessions = mutableListOf<GuestBrowseSession>()
        val current = mutableListOf<() -> Boolean>()
        val owner = NativeWorkspaceOwner(entry, navigation,
            createGuest = { source, stack, valid ->
                assertSame(navigation, stack)
                current += valid
                val session = GuestBrowseSession(RpcTransport { withContext(NonCancellable) { response.await() } }, backgroundScope, source, stack)
                sessions += session
                if (first == null) first = session
                object : NativeWorkspace { override fun close() = session.close() }
            },
            createExisting = { stack, _ ->
                assertSame(navigation, stack)
                object : NativeWorkspace { override fun close() {} }
            },
            selected = {},
        )
        val old = first!!
        old.repositories.editQuery("old query")
        old.repositories.start(); runCurrent()
        val fallback = Screen.Repo("public-owner", "public-repo")
        navigation.stack.add(fallback)
        owner.select(NativeEntryMode.ExistingServer)
        assertEquals(listOf(Screen.Home, fallback), navigation.stack.toList())
        owner.select(NativeEntryMode.Explore)
        assertEquals(listOf(Screen.Explore), navigation.stack.toList())
        assertSame(navigation, owner.navigation)
        assertFalse(current.first()())
        assertTrue(current.last()())
        response.complete(RpcResponse(200, """{"items":[],"incomplete_results":false}""", "application/json"))
        runCurrent()
        old.repositories.runIfCurrent { error("Retired callback opened the replacement entry") }
        assertEquals("", old.repositories.query)
        assertTrue(old.repositories.repositories.isEmpty())
        assertFalse(old.current)
        assertTrue(sessions.last().current)
        owner.close()
        assertTrue(current.none { it() })
    }
}
