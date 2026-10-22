package com.octaviantocan.pulls.ui.explore

import com.octaviantocan.pulls.data.RpcMethod
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.Screen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GuestBrowseSessionTest {
    @Test
    fun repositoryAndBackShareTheRootStackAndKeepThePublicSearch() = runTest {
        val h = PublicFixture()
        val entry = NativeEntryState(false)
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val session = GuestBrowseSession(h, backgroundScope, entry.guestSource()!!, navigation)
        session.repositories.editQuery("kotlin")
        session.repositories.start()
        runCurrent()
        val repository = session.repositories.repositories.single()
        session.openRepository(repository.copy(id = 99))
        assertEquals(listOf(Screen.Explore), navigation.stack.toList())
        session.openRepository(repository)
        val page = navigation.stack.last() as Screen.PublicRepo
        val pulls = session.pulls(page)
        pulls.start()
        runCurrent()
        assertEquals(listOf(1L), pulls.pulls.map { it.number })
        assertSame(pulls, session.pulls(page))
        navigation.nav.back()
        session.keepRoutes()
        assertEquals(listOf(Screen.Explore), navigation.stack.toList())
        assertEquals("kotlin", session.repositories.query)
        session.repositories.start()
        assertEquals(3, h.requests.size)
        assertTrue(pulls.pulls.isEmpty())
        assertTrue(h.requests.all { it.method == RpcMethod.Get && it.url.startsWith("https://api.github.com/") })
        assertTrue(h.requests.all { request -> request.body.isEmpty() && request.headers.keys.none { it.equals("Authorization", true) || it.equals("X-Pully-Session", true) || it.startsWith("CF-Access", true) } })
        session.close()
    }

    @Test
    fun poppedPageKeepsOnlyItsInertExitSnapshotAndRejectsLatePagination() = runTest {
        val h = PublicFixture()
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val session = GuestBrowseSession(h, backgroundScope, NativeEntryState(false).guestSource()!!, navigation)
        session.repositories.start()
        runCurrent()
        session.openRepository(session.repositories.repositories.single())
        val page = navigation.stack.last() as Screen.PublicRepo
        val pulls = session.pulls(page)
        h.pullBody = (1..20).joinToString(",", "[", "]") { h.pull(it) }
        pulls.start()
        runCurrent()
        session.retain(page)
        val pending = CompletableDeferred<RpcResponse>()
        h.delayedPulls = pending
        pulls.loadMore()
        runCurrent()
        navigation.nav.back()
        session.keepRoutes()
        pulls.open(pulls.pulls.first()) { error("Popped PR row opened GitHub") }
        session.runFrom(page) { error("Popped page changed the active route") }
        pending.complete(h.json("[${h.pull(21)}]"))
        runCurrent()
        assertEquals((1L..20L).toList(), pulls.pulls.map { it.number })
        session.release(page)
        assertTrue(pulls.pulls.isEmpty())
        session.close()
    }

    @Test
    fun privateEntryRetiresPendingPublicResultsBeforeCompositionDisposesTheSession() = runTest {
        val h = PublicFixture()
        val entry = NativeEntryState(false)
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val session = GuestBrowseSession(h, backgroundScope, entry.guestSource()!!, navigation)
        val pending = CompletableDeferred<RpcResponse>()
        h.delayedSearch = pending
        session.repositories.editQuery("old-guest-search")
        session.repositories.start()
        runCurrent()
        entry.select(NativeEntryMode.ExistingServer)
        navigation.reset(Screen.Home)
        assertFalse(session.current)
        assertEquals("", session.repositories.query)
        session.runFrom(Screen.Home) { error("Retired source opened a private workspace") }
        pending.complete(h.json("{\"items\":[${h.repository}],\"incomplete_results\":false}"))
        runCurrent()
        assertTrue(session.repositories.repositories.isEmpty())
        entry.select(NativeEntryMode.Explore)
        navigation.reset(Screen.Explore)
        assertFalse(session.current)
        session.repositories.runIfCurrent { error("A new guest entry revived old callbacks") }
        session.close()
        assertEquals("", session.repositories.query)
        assertTrue(session.repositories.repositories.isEmpty())
        assertEquals(listOf(Screen.Explore), navigation.stack.toList())
    }

    @Test
    fun foregroundOnlyReloadsTheActivePublicRouteAndRetiredSessionsDoNothing() = runTest {
        val h = PublicFixture()
        val entry = NativeEntryState(false)
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val session = GuestBrowseSession(h, backgroundScope, entry.guestSource()!!, navigation)
        session.foreground()
        session.repositories.start()
        runCurrent()
        assertEquals(1, h.requests.size)
        session.openRepository(session.repositories.repositories.single())
        val page = navigation.stack.last() as Screen.PublicRepo
        val pulls = session.pulls(page)
        pulls.start()
        runCurrent()
        session.foreground()
        runCurrent()
        assertEquals(5, h.requests.size)
        assertEquals(1, h.requests.count { "/search/" in it.url })
        navigation.nav.back()
        session.foreground()
        runCurrent()
        assertEquals(6, h.requests.size)
        assertEquals(2, h.requests.count { "/search/" in it.url })
        entry.select(NativeEntryMode.ExistingServer)
        session.foreground()
        runCurrent()
        assertEquals(6, h.requests.size)
        assertTrue(session.repositories.repositories.isEmpty())
        session.close()
    }

    private class PublicFixture : RpcTransport {
        val requests = mutableListOf<RpcRequest>()
        val repository = """{"id":42,"owner":{"login":"public-owner"},"name":"public-repo","full_name":"public-owner/public-repo","private":false,"description":null,"stargazers_count":123,"language":"Kotlin"}"""
        var pullBody = "[${pull(1)}]"
        var delayedSearch: CompletableDeferred<RpcResponse>? = null
        var delayedPulls: CompletableDeferred<RpcResponse>? = null
        fun pull(number: Int) = """{"number":$number,"title":"Public PR $number","state":"open","user":{"login":"contributor"},"draft":false,"updated_at":"2026-10-21T00:00:00Z"}"""
        fun json(body: String) = RpcResponse(200, body, contentType = "application/json")
        override suspend fun execute(request: RpcRequest): RpcResponse {
            requests += request
            return when {
                "/search/" in request.url -> delayedSearch?.let { withContext(NonCancellable) { it.await() } } ?: json("{\"items\":[$repository],\"incomplete_results\":false}")
                "/pulls?" in request.url -> delayedPulls?.let { withContext(NonCancellable) { it.await() } } ?: json(pullBody)
                else -> json(repository)
            }
        }
    }
}
