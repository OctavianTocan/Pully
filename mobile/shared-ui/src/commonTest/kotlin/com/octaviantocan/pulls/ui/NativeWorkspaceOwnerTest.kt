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
    fun personalRenewalClosesTheOldPublicSessionAndKeepsOneExploreStackWithoutPrivateWork() = runTest {
        val entry = NativeEntryState(false)
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val held = CompletableDeferred<RpcResponse>()
        val sessions = mutableListOf<GuestBrowseSession>()
        val currents = mutableListOf<() -> Boolean>()
        var selected = 0
        val owner = NativeWorkspaceOwner(entry, navigation,
            createGuest = { source, stack, current ->
                assertSame(navigation, stack)
                assertTrue(sessions.none { it.current })
                currents += current
                val session = GuestBrowseSession(RpcTransport { withContext(NonCancellable) { held.await() } }, backgroundScope, source, stack)
                sessions += session
                object : NativeWorkspace {
                    override fun close() { assertFalse(current()); session.close() }
                }
            },
            createExisting = { _, _ -> error("A public account must not construct private services") },
            selected = { selected++ },
        )
        val old = sessions.single()
        old.repositories.editQuery("old personal query")
        old.repositories.start(); runCurrent()
        navigation.stack.add(Screen.Repo("public-owner", "public-repo"))
        val oldEpoch = entry.generation
        owner.renewExplore()
        assertEquals(oldEpoch + 1, entry.generation)
        assertEquals(NativeEntryMode.Explore, entry.mode)
        assertEquals(listOf(Screen.Explore), navigation.stack.toList())
        assertSame(navigation, owner.navigation)
        assertFalse(currents.first()())
        assertTrue(currents.last()())
        assertEquals(0, selected, "Identity renewal must not overwrite a saved entry choice")
        old.repositories.runIfCurrent { error("The old public callback survived renewal") }
        held.complete(RpcResponse(200, """{"items":[],"incomplete_results":false}""", "application/json"))
        runCurrent()
        assertEquals("", old.repositories.query)
        assertTrue(old.repositories.repositories.isEmpty())
        assertFalse(old.current)
        assertTrue(sessions.last().current)
        owner.close()
        owner.renewExplore()
        assertEquals(2, sessions.size)
    }

    @Test
    fun aPersonalChangeDoesNotRenewOrCloseTheSelectedPrivateWorkspace() {
        val entry = NativeEntryState(true)
        val navigation = AppNavigation()
        navigation.nav.settings()
        var closed = false
        val owner = NativeWorkspaceOwner(entry, navigation,
            createGuest = { _, _, _ -> error("Private entry must remain private") },
            createExisting = { _, current -> object : NativeWorkspace {
                override fun close() { assertFalse(current()); closed = true }
            } },
            selected = { error("A personal identity must not select a legacy entry") },
        )
        val original = owner.workspace
        owner.renewExplore()
        assertSame(original, owner.workspace)
        assertEquals(listOf(Screen.Home, Screen.Settings), navigation.stack.toList())
        assertEquals(0L, owner.generation)
        assertFalse(closed)
        owner.close()
    }

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
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
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
