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
    @Test
    fun nonRpc429DoesNotInventAnAccountLockoutOrExposeTheResponse() = runTest {
        val args = buildJsonObject { put("password", "private-password") }
        for ((response, kind) in listOf(
            RpcResponse(429, "<html>private-password upstream throttling</html>", contentType = "text/html") to ServerErrorKind.HtmlResponse,
            RpcResponse(429, "{}", contentType = "application/json") to ServerErrorKind.Http,
        )) {
            val error = assertFailsWith<ServerError> { RpcClient("https://server.example", RpcTransport { response }).call("account-sign-in", args) }
            assertEquals(kind, error.kind)
            assertFalse(error.message.orEmpty().contains("private-password"))
            assertFalse(error.message.orEmpty().contains("15 minutes"))
        }
    }

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
            RpcResponse(200, "<html>Access login</html>", "text/html") to ServerErrorKind.HtmlResponse,
            RpcResponse(403, "<html>Denied</html>") to ServerErrorKind.HtmlResponse,
            RpcResponse(401, """{"error":"Session expired"}""") to ServerErrorKind.Unauthorized,
            RpcResponse(403, """{"error":"Account forbidden"}""") to ServerErrorKind.Forbidden,
            RpcResponse(302, "<html>Elsewhere</html>") to ServerErrorKind.Redirect,
            RpcResponse(200, "private response that is not JSON") to ServerErrorKind.InvalidResponse,
            RpcResponse(200, "[]") to ServerErrorKind.InvalidResponse,
            RpcResponse(200, "") to ServerErrorKind.Empty,
            RpcResponse(401, "not an RPC response") to ServerErrorKind.Http,
            RpcResponse(403, "{}") to ServerErrorKind.Http,
            RpcResponse(200, """{"error":{"unexpected":"shape"}}""") to ServerErrorKind.InvalidResponse,
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
    fun htmlNeverClaimsAccountExpiryOrAProxyCauseItCannotKnow() = runTest {
        for (status in listOf(200, 401, 403, 404, 502, 503)) {
            for ((type, body) in listOf(
                "text/html; charset=utf-8" to "private body and credential",
                "application/xhtml+xml" to "private body and credential",
                "application/json" to "\uFEFF  <!DOCTYPE HTML><html>private body and credential</html>",
                "" to " \n<HTML>private body and credential</HTML>",
            )) {
                val failure = assertFailsWith<ServerError> {
                    RpcClient("https://server.example", RpcTransport { RpcResponse(status, body, type) }).call("account-status")
                }
                assertEquals(ServerErrorKind.HtmlResponse, failure.kind)
                assertEquals(status, failure.status)
                assertEquals(RpcResponseFormat.Html, failure.format)
                assertFalse(failure.message.orEmpty().contains("sign-in"))
                assertFalse(failure.message.orEmpty().contains("private body"))
                assertEquals(null, failure.cause)
            }
        }
    }

    @Test
    fun onlyTheFixedCloudflareAccessLoginDestinationIdentifiesAccess() = runTest {
        val destinations = listOf(
            "https://team.cloudflareaccess.com/cdn-cgi/access/login/server.example?token=private-value" to ServerErrorKind.AccessRequired,
            "https://team.cloudflareaccess.com:443/cdn-cgi/access/login/server.example" to ServerErrorKind.AccessRequired,
            "https://team.cloudflareaccess.com.evil.example/cdn-cgi/access/login/server.example" to ServerErrorKind.Redirect,
            "https://team.cloudflareaccess.com@evil.example/cdn-cgi/access/login/" to ServerErrorKind.Redirect,
            "http://team.cloudflareaccess.com/cdn-cgi/access/login/" to ServerErrorKind.Redirect,
            "https://team.cloudflareaccess.com/different-page" to ServerErrorKind.Redirect,
            "https://server.example/login?next=private-value" to ServerErrorKind.Redirect,
        )
        for ((destination, kind) in destinations) {
            val failure = assertFailsWith<ServerError> {
                RpcClient("https://server.example", RpcTransport { RpcResponse(302, "", location = destination) }).call("account-status")
            }
            assertEquals(kind, failure.kind)
            assertFalse(failure.message.orEmpty().contains("private-value"))
            assertFalse(failure.message.orEmpty().contains(destination))
        }
    }

    @Test
    fun neverDisplaysNativeFailureMessagesOrReflectedSignInCredentials() = runTest {
        val unreachable = assertFailsWith<RpcConnectionException> {
            RpcClient("https://server.example", RpcTransport { throw IllegalStateException("https://server.example/?secret=private-password") }).call("account-status")
        }
        assertFalse(unreachable.message.orEmpty().contains("private-password"))
        assertEquals(null, unreachable.cause)
        val failure = assertFailsWith<ServerError> {
            RpcClient("https://server.example", RpcTransport {
                RpcResponse(401, """{"error":"Rejected private-password and private-session"}""")
            }, mapOf("X-Pully-Session" to "private-session"))
                .call("account-sign-in", buildJsonObject { put("password", "private-password") })
        }
        assertEquals(ServerErrorKind.Unauthorized, failure.kind)
        assertFalse(failure.message.orEmpty().contains("private-password"))
        assertFalse(failure.message.orEmpty().contains("private-session"))
        val bounded = assertFailsWith<ServerError> {
            RpcClient("https://server.example", RpcTransport {
                RpcResponse(500, buildJsonObject { put("error", "x".repeat(5000)) }.toString())
            }).call("read")
        }
        assertEquals(1200, bounded.message?.length)
    }

    @Test
    fun reflectedSerializedCredentialsCannotBecomeReadableErrorDetails() = runTest {
        val credential = "private\\quoted\"password\nwith-newline"
        val args = buildJsonObject { put("email", "person@example.com"); put("password", credential) }
        val failure = assertFailsWith<ServerError> {
            RpcClient("https://server.example", RpcTransport {
                RpcResponse(401, buildJsonObject { put("error", "Rejected args $args") }.toString())
            }).call("account-sign-in", args)
        }
        assertEquals("Pully could not sign in. Check your account or choose another, then try again.", failure.message)
        assertFalse(failure.message.orEmpty().contains("private"))
        val secret = "private\\quoted\"header"
        val reflectedHeader = assertFailsWith<ServerError> {
            RpcClient("https://server.example", RpcTransport {
                RpcResponse(400, buildJsonObject { put("error", "Rejected ${JsonPrimitive(secret)}") }.toString())
            }, mapOf("Authorization" to secret)).call("read")
        }
        assertFalse(reflectedHeader.message.orEmpty().contains("private"))
        val nested = assertFailsWith<ServerError> {
            RpcClient("https://server.example", RpcTransport {
                RpcResponse(400, buildJsonObject { put("error", "Rejected $args") }.toString())
            }).call("future-connection", buildJsonObject { put("connection", args) })
        }
        assertEquals("The server could not complete this request. Try again.", nested.message)
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
