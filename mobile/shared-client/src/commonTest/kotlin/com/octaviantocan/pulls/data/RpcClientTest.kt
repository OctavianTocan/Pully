package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RpcClientTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun usesTheExistingEnvelopeAndConfiguredBasePath() = runTest {
        var captured: RpcRequest? = null
        val client = RpcClient("HTTPS://SERVER.example:443/pully/", RpcTransport {
            captured = it
            RpcResponse(200, """{"result":{"pulls":[]},"future":true}""")
        }, mapOf("X-Pully-Session" to "device-session"))
        val args = buildJsonObject { put("state", "open"); put("query", "café\nquoted: \"") }
        assertEquals(json("""{"pulls":[]}"""), client.call("my-pulls", args))
        assertEquals("https://server.example/pully/api/rpc", captured?.url)
        assertEquals(buildJsonObject { put("name", "my-pulls"); put("args", args) }, json(captured!!.body))
        assertEquals(mapOf("X-Pully-Session" to "device-session"), captured?.headers)
    }

    @Test
    fun preservesNullResultsAndDoesNotMistakeHttpFailureForSuccess() = runTest {
        for (body in listOf("{}", """{"result":null}""")) {
            assertEquals(JsonNull, RpcClient("https://server.example", RpcTransport { RpcResponse(200, body) }).call("read"))
        }
        val error = assertFailsWith<ServerError> {
            RpcClient("https://server.example", RpcTransport { RpcResponse(500, """{"result":"not-success"}""") }).call("read")
        }
        assertEquals(500, error.status)
    }

    @Test
    fun distinguishesAccessAccountRedirectAndInvalidEnvelopeErrors() = runTest {
        val cases = listOf(
            RpcResponse(200, "<html>Access login</html>", "text/html") to ServerErrorKind.AccessRequired,
            RpcResponse(403, "<html>Denied</html>") to ServerErrorKind.AccessRequired,
            RpcResponse(401, """{"error":"Session expired"}""") to ServerErrorKind.Unauthorized,
            RpcResponse(403, """{"error":"Account forbidden"}""") to ServerErrorKind.Forbidden,
            RpcResponse(302, "<html>Elsewhere</html>") to ServerErrorKind.Redirect,
            RpcResponse(200, "private response that is not JSON") to ServerErrorKind.InvalidResponse,
            RpcResponse(200, "[]") to ServerErrorKind.InvalidResponse,
            RpcResponse(200, "") to ServerErrorKind.Empty,
        )
        for ((response, expected) in cases) {
            val error = assertFailsWith<ServerError> {
                RpcClient("https://server.example", RpcTransport { response }).call("read")
            }
            assertEquals(expected, error.kind)
            assertEquals(response.status, error.status)
            assertFalse(error.message.orEmpty().contains("private response"))
        }
        val error = assertFailsWith<ServerError> {
            RpcClient("https://server.example", RpcTransport { RpcResponse(200, """{"error":"The head changed"}""") }).call("pr-action")
        }
        assertEquals("The head changed", error.message)
    }

    @Test
    fun rejectsCredentialUrlsAndInsecureCredentialHeadersBeforeTransport() = runTest {
        var sent = false
        val transport = RpcTransport { sent = true; RpcResponse(200, "{}") }
        for (server in listOf(
            "https://user:password@server.example", "https://server.example?next=other", "https://server.example/#fragment",
            "https://server.example\\@other.example", "https://server.example%2f.other.example", "https://server.example:0", "https://server.example:+443",
        )) assertFailsWith<IllegalArgumentException> { RpcClient(server, transport).call("read") }
        assertFailsWith<IllegalArgumentException> {
            RpcClient("http://server.example", transport, mapOf("X-Pully-Session" to "private")).call("read")
        }
        assertFailsWith<IllegalArgumentException> { RpcClient("https://server.example", transport, mapOf("Host" to "other.example")).call("read") }
        assertFailsWith<IllegalArgumentException> { RpcClient("https://server.example", transport, mapOf("X-Header" to "value\r\nHost: other.example")).call("read") }
        assertFalse(sent)
    }

    @Test
    fun eventsRetainTheirSeparateExistingEnvelope() = runTest {
        var request: RpcRequest? = null
        val body = """{"events":[{"name":"rpc.call"}]}"""
        val status = RpcClient("https://server.example", RpcTransport {
            request = it
            RpcResponse(204, "")
        }).postEvents(body)
        assertEquals(204, status)
        assertEquals("https://server.example/api/events", request?.url)
        assertEquals(body, request?.body)
    }

    @Test
    fun cancellationPropagatesWithoutSendingServerTaskCancel() = runTest {
        val methods = mutableListOf<String>()
        var detached = false
        val repo = Repo({ RepoConfiguration("https://server.example") }, RpcTransport {
            methods += json(it.body).jsonObject.getValue("name").let { name -> (name as JsonPrimitive).content }
            try { awaitCancellation() } finally { detached = true }
        })
        val observer = launch { repo.waitTask("task-1", 3) }
        runCurrent()
        observer.cancelAndJoin()
        assertTrue(detached)
        assertEquals(listOf("ai-task-wait"), methods)
    }

    @Test
    fun anUnresponsiveTransportHitsTheSharedTotalDeadline() = runTest {
        var detached = false
        val client = RpcClient("https://server.example", RpcTransport {
            try { awaitCancellation() } finally { detached = true }
        })
        assertFailsWith<RpcTimeoutException> { client.call("ai-task-wait") }
        assertTrue(detached)
        assertEquals(90_000L, testScheduler.currentTime)
    }

    @Test
    fun aCallersShorterDeadlineStillDetachesInsteadOfBecomingANetworkFailure() = runTest {
        var detached = false
        val client = RpcClient("https://server.example", RpcTransport {
            try { awaitCancellation() } finally { detached = true }
        })
        assertEquals(null, withTimeoutOrNull(10) { client.call("ai-task-wait") })
        assertTrue(detached)
        assertEquals(10L, testScheduler.currentTime)
    }

    @Test
    fun accountChangesRejectLateSuccessAndDoNotExposeCredentialCacheKeys() = runTest {
        var config = RepoConfiguration("https://server.example", mapOf("X-Pully-Session" to "private-token"), generation = 1)
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var observed = false
        val repo = Repo({ config }, RpcTransport {
            waiting.complete(Unit)
            release.await()
            RpcResponse(200, """{"result":{"email":"previous-user"}}""")
        }, RpcObserver { _, _, _, _ -> observed = true })
        val source = repo.source()
        val request = async { repo.accountEmail() }
        waiting.await()
        config = RepoConfiguration("https://server.example", mapOf("X-Pully-Session" to "new-token"), generation = 2)
        release.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
        assertFalse(repo.isCurrent(source))
        assertFalse(source.toString().contains("private-token"))
        assertFalse(observed)
    }
}
