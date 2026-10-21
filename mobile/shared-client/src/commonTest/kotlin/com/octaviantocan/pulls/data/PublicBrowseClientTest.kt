package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PublicBrowseClientTest {
    @Test
    fun popularUsesFixedTokenlessPublicRestWithoutAPullyServerOrAccount() = runTest {
        val h = Harness()
        val page = h.client.repositories()
        assertEquals(listOf("public-owner/public-repo"), page.repositories.map { it.fullName })
        assertNull(page.nextPage)
        val request = h.requests.single()
        assertEquals(RpcMethod.Get, request.method)
        assertEquals("", request.body)
        assertEquals("https://api.github.com/search/repositories?q=stars%3A%3E100%20is%3Apublic%20archived%3Afalse&sort=stars&order=desc&per_page=20&page=1", request.url)
        assertEquals(setOf("Accept", "User-Agent", "X-GitHub-Api-Version"), request.headers.keys)
        assertEquals(2 * 1024 * 1024, request.responseLimit)
        assertEquals(30_000L, request.timeoutMs)
    }

    @Test
    fun privateAndMalformedSearchResultsCannotBecomePublicRepositories() = runTest {
        val h = Harness()
        h.answer = { response(search(JsonArray(listOf(repo(), buildJsonObject { put("private", true); put("name", "owner-private-secret") })))) }
        assertEquals(1, h.client.repositories().repositories.size)
        h.answer = { response("""{"items":[{"private":"false"}],"incomplete_results":false}""") }
        assertEquals(PublicBrowseProblem.InvalidResponse, assertFailsWith<PublicBrowseError> { h.client.repositories() }.problem)
        val stringId = repo(id = JsonPrimitive("42"))
        h.answer = { response(search(JsonArray(listOf(stringId)))) }
        assertEquals(PublicBrowseProblem.InvalidResponse, assertFailsWith<PublicBrowseError> { h.client.repositories() }.problem)
    }

    @Test
    fun aRepositoryMustBePublicBeforeAnyPullRequestRequest() = runTest {
        val h = Harness()
        h.answer = { request ->
            response(if (request.url.endsWith("/public-repo")) repo().toString() else """[{"number":7,"state":"open","title":"Public change","user":{"login":"reader"},"draft":true,"updated_at":"2026-10-21T10:00:00Z"}]""")
        }
        val page = h.client.pulls("public-owner", "public-repo")
        assertEquals("https://github.com/public-owner/public-repo/pull/7", page.pulls.single().url)
        assertTrue(page.pulls.single().draft)
        assertTrue(h.requests.first().url.endsWith("/repos/public-owner/public-repo"))
        assertEquals(2, h.requests.size)
        h.answer = { response(repo(privateRepo = true).toString()) }
        assertEquals(PublicBrowseProblem.RepositoryUnavailable, assertFailsWith<PublicBrowseError> { h.client.pulls("public-owner", "public-repo") }.problem)
        assertEquals(3, h.requests.size)
    }

    @Test
    fun invalidSearchQualifierPathsAndPagesNeverReachTheNetwork() = runTest {
        val h = Harness()
        for ((query, page) in listOf("x is:private" to 1, "a\nsecret" to 1, "x" to 0, "x" to 11, "x".repeat(81) to 1)) {
            assertEquals(PublicBrowseProblem.InvalidSearch, assertFailsWith<PublicBrowseError> { h.client.repositories(query, page) }.problem)
        }
        for ((owner, repo) in listOf(".." to "repo", "owner" to "a/b", "owner" to "repo?token=secret")) {
            assertFailsWith<PublicBrowseError> { h.client.pulls(owner, repo) }
        }
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun aSelectedRepositoryCannotTurnIntoADifferentRepositoryThatReusesItsName() = runTest {
        val h = Harness()
        h.answer = { response(repo(id = JsonPrimitive(99)).toString()) }
        assertEquals(PublicBrowseProblem.RepositoryUnavailable, assertFailsWith<PublicBrowseError> {
            h.client.pulls("public-owner", "public-repo", repositoryId = 42)
        }.problem)
        assertEquals(1, h.requests.size)
        assertFalse(h.requests.single().url.contains("/pulls"))
    }

    @Test
    fun failuresNeverEchoBodiesOrConvertAnonymousRejectionIntoAccountSignIn() = runTest {
        val h = Harness()
        for ((response, expected) in listOf(
            RpcResponse(200, "<!doctype html><p>private-secret</p>") to PublicBrowseProblem.WebPage,
            RpcResponse(200, "private-secret invalid-json") to PublicBrowseProblem.InvalidResponse,
            RpcResponse(401, """{"message":"private-secret"}""") to PublicBrowseProblem.Unavailable,
            RpcResponse(403, "private-secret") to PublicBrowseProblem.Unavailable,
            RpcResponse(403, "private-secret", rateLimitRemaining = 0) to PublicBrowseProblem.RateLimited,
            RpcResponse(403, "private-secret", retryAfterSeconds = 120) to PublicBrowseProblem.RateLimited,
            RpcResponse(429, "private-secret") to PublicBrowseProblem.RateLimited,
            RpcResponse(302, "private-secret", location = "https://evil.invalid/?secret=private-secret") to PublicBrowseProblem.Redirect,
            RpcResponse(404, "private-secret") to PublicBrowseProblem.RepositoryUnavailable,
        )) {
            h.answer = { response }
            val error = assertFailsWith<PublicBrowseError> { h.client.repositories() }
            assertEquals(expected, error.problem)
            assertFalse(error.message.orEmpty().contains("private-secret"))
            assertNull(error.cause)
        }
    }

    @Test
    fun retiringTheGuestSourceRejectsPendingRepliesAndRetainedCallbacks() = runTest {
        val h = Harness()
        val source = h.client.source()
        val pending = CompletableDeferred<RpcResponse>()
        h.answer = { pending.await() }
        val opening = async { h.client.repositories(source = source) }
        runCurrent()
        h.generation++
        pending.complete(response(search()))
        assertFailsWith<CancellationException> { opening.await() }
        assertFailsWith<CancellationException> { h.client.repositories(source = source) }
        assertEquals(1, h.requests.size)
    }

    @Test
    fun cancellationClosesTheActiveTransportAndNativeDetailsStayPrivate() = runTest {
        val h = Harness()
        var detached = false
        h.answer = { try { awaitCancellation() } finally { detached = true } }
        val opening = async { h.client.repositories() }
        runCurrent()
        opening.cancelAndJoin()
        assertTrue(detached)
        h.answer = { error("secret host query headers should stay private") }
        val error = assertFailsWith<PublicBrowseError> { h.client.repositories() }
        assertEquals(PublicBrowseProblem.Unreachable, error.problem)
        assertFalse(error.message.orEmpty().contains("secret"))
        assertNull(error.cause)
    }

    @Test
    fun completePagesKeepTheBoundedContinuationAndEncodedSearch() = runTest {
        val h = Harness()
        h.answer = { response(search(JsonArray(List(20) { repo(id = JsonPrimitive(it + 1)) }))) }
        assertEquals(2, h.client.repositories("native mobile", 1).nextPage)
        assertTrue(h.requests.single().url.contains("q=native%20mobile%20is%3Apublic"))
        assertNull(h.client.repositories("native mobile", 10).nextPage)
    }

    private class Harness {
        var generation = 0L
        val requests = mutableListOf<RpcRequest>()
        var answer: suspend (RpcRequest) -> RpcResponse = { response(search()) }
        val client = PublicBrowseClient(RpcTransport { request -> requests += request; answer(request) }, { generation })
    }
}

private fun response(body: String) = RpcResponse(200, body, "application/json")
private fun search(items: JsonArray = JsonArray(listOf(repo()))): String = buildJsonObject { put("items", items); put("incomplete_results", false) }.toString()
private fun repo(privateRepo: Boolean = false, id: JsonPrimitive = JsonPrimitive(42)): JsonObject = Json.parseToJsonElement("""{"id":$id,"owner":{"login":"public-owner"},"name":"public-repo","full_name":"public-owner/public-repo","private":$privateRepo,"description":"A public project","stargazers_count":1000,"language":"Rust"}""") as JsonObject
