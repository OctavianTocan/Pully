package com.octaviantocan.pulls.ui.explore

import com.octaviantocan.pulls.data.ExploreRepository
import com.octaviantocan.pulls.data.PublicBrowseClient
import com.octaviantocan.pulls.data.PublicBrowseProblem
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreStateTest {
    @Test
    fun returningFromARepositoryKeepsSearchAndDoesNotRestartThePublicList() = runTest {
        val h = Harness()
        val state = ExploreRepositoriesState(h.client, backgroundScope)
        assertFalse(state.hasLoaded)
        state.editQuery("mobile")
        state.start()
        assertTrue(state.loading)
        runCurrent()
        assertTrue(state.hasLoaded)
        assertEquals("mobile", state.submittedQuery)
        var opened: ExploreRepository? = null
        state.open(state.repositories.single()) { opened = it }
        assertEquals("public-owner/public-repo", opened?.fullName)
        state.start()
        assertEquals(1, h.requests.size)
        assertEquals("mobile", state.query)
        state.close()
        state.open(opened!!) { error("Retired route opened a repository") }
        state.runIfCurrent { error("Retired route opened sign-in") }
        assertEquals("", state.query)
        assertTrue(state.repositories.isEmpty())
    }

    @Test
    fun aReplacedSearchCannotRestoreItsOldRowsOrErrorAfterTheNewSearch() = runTest {
        val h = Harness()
        val old = CompletableDeferred<RpcResponse>()
        h.answer = { withContext(NonCancellable) { old.await() } }
        val state = ExploreRepositoriesState(h.client, backgroundScope)
        state.editQuery("old")
        state.search()
        runCurrent()
        h.answer = { json(search(listOf(repository(99)))) }
        state.editQuery("new")
        state.search()
        runCurrent()
        assertEquals(listOf(99L), state.repositories.map { it.id })
        old.complete(RpcResponse(500, "private diagnostic"))
        runCurrent()
        assertEquals(listOf(99L), state.repositories.map { it.id })
        assertEquals("new", state.submittedQuery)
        assertNull(state.problem)
        assertFalse(state.loading)
        state.close()
    }

    @Test
    fun failedPaginationKeepsThePublicPageAndRetryUsesItsSameSearchAndPage() = runTest {
        val h = Harness()
        h.answer = { json(search((1L..20L).map(::repository))) }
        val state = ExploreRepositoriesState(h.client, backgroundScope)
        state.editQuery("kotlin")
        state.search()
        runCurrent()
        assertEquals(2, state.nextPage)
        h.answer = { RpcResponse(429, "quota details") }
        state.loadMore()
        runCurrent()
        assertEquals(20, state.repositories.size)
        assertEquals(PublicBrowseProblem.RateLimited, state.problem)
        state.editQuery("an unsubmitted edit")
        h.answer = { json(search(listOf(repository(20), repository(21)))) }
        state.retry()
        runCurrent()
        assertTrue(h.requests.last().url.contains("q=kotlin%20is%3Apublic"))
        assertTrue(h.requests.last().url.endsWith("page=2"))
        assertEquals((1L..21L).toList(), state.repositories.map { it.id })
        assertNull(state.nextPage)
        assertNull(state.problem)
        state.close()
    }

    @Test
    fun retiringTheGuestRejectsPendingResultsAndRetainedActions() = runTest {
        val h = Harness()
        val pending = CompletableDeferred<RpcResponse>()
        h.answer = { pending.await() }
        var current = true
        val state = ExploreRepositoriesState(h.client, backgroundScope, { current })
        state.start()
        runCurrent()
        current = false
        h.generation++
        pending.complete(json(search(listOf(repository(7)))))
        runCurrent()
        state.runIfCurrent { error("A retired guest opened account settings") }
        state.search()
        assertTrue(state.repositories.isEmpty())
        assertNull(state.problem)
        assertEquals(1, h.requests.size)
        state.close()
        assertFalse(state.loading)
    }

    @Test
    fun aReusedRepositoryNameClearsPriorPullsAndRevokesTheirOpenActions() = runTest {
        val h = Harness()
        h.answer = { request -> json(if (request.url.contains("/pulls?")) pulls(20) else repository(42)) }
        val selected = ExploreRepository(42, "public-owner", "public-repo", null, 1000, null)
        val state = ExplorePullsState(selected, h.client, backgroundScope)
        state.start()
        runCurrent()
        assertEquals(20, state.pulls.size)
        val retained = state.pulls.first()
        var opened: String? = null
        state.open(retained) { opened = it }
        assertEquals("https://github.com/public-owner/public-repo/pull/1", opened)
        h.answer = { json(repository(99)) }
        state.loadMore()
        runCurrent()
        assertEquals(PublicBrowseProblem.RepositoryUnavailable, state.problem)
        assertTrue(state.pulls.isEmpty())
        assertNull(state.nextPage)
        assertEquals(3, h.requests.size)
        state.open(retained) { error("A retired repository PR was opened") }
        state.close()
    }

    @Test
    fun anEmptyConnectedRepositoryAndAConnectionFailureRemainDifferentStates() = runTest {
        val h = Harness()
        h.answer = { request -> json(if (request.url.contains("/pulls?")) "[]" else repository(42)) }
        val state = ExplorePullsState(ExploreRepository(42, "public-owner", "public-repo", null, 1000, null), h.client, backgroundScope)
        state.start()
        runCurrent()
        assertTrue(state.hasLoaded)
        assertTrue(state.pulls.isEmpty())
        assertNull(state.problem)
        h.answer = { RpcResponse(200, "<!DOCTYPE html><p>secret diagnostics</p>", "text/html") }
        state.refresh()
        runCurrent()
        assertFalse(state.hasLoaded)
        assertEquals(PublicBrowseProblem.WebPage, state.problem)
        state.close()
        state.runIfCurrent { error("Retired repository consumed Back") }
    }

    private class Harness {
        var generation = 0L
        val requests = mutableListOf<RpcRequest>()
        var answer: suspend (RpcRequest) -> RpcResponse = { json(search(listOf(repository(42)))) }
        val client = PublicBrowseClient(RpcTransport { request -> requests += request; answer(request) }, { generation })
    }
}

private fun json(body: String) = RpcResponse(200, body, "application/json")
private fun search(repositories: List<String>) = """{"items":[${repositories.joinToString(",")}],"incomplete_results":false}"""
private fun repository(id: Long) = """{"id":$id,"owner":{"login":"public-owner"},"name":"public-repo","full_name":"public-owner/public-repo","private":false,"description":"Public project","stargazers_count":1000,"language":"Kotlin"}"""
private fun pulls(count: Int) = (1..count).joinToString(",", prefix = "[", postfix = "]") { number ->
    """{"number":$number,"state":"open","title":"Public change $number","user":{"login":"reader"},"draft":false,"updated_at":"2026-10-21T10:00:00Z"}"""
}
