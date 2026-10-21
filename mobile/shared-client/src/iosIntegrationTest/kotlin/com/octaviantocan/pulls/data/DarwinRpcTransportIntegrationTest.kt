package com.octaviantocan.pulls.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Real NSURLSession requests; this source set is only in the explicit integration test binary. */
@OptIn(ExperimentalForeignApi::class)
class DarwinRpcTransportIntegrationTest {
    private val base: String
        get() = (NSProcessInfo.processInfo.environment["PULLY_RPC_FIXTURE_URL"] as? String)
            ?.takeIf { Regex("http://127\\.0\\.0\\.1:[0-9]+").matches(it) }
            ?: error("Missing loopback fixture. Run ios/scripts/rpc-fixture.py and pass -Ppully.rpcFixtureUrl.")

    @Test
    fun sendsActualRpcEnvelopeAndConfiguredHeaders() = runBlocking {
        val args = buildJsonObject { put("text", "café 🦉"); put("count", 3) }
        val answer = RpcClient(base, DarwinRpcTransport(), mapOf(
            "X-Pully-Session" to "fixture-session",
            "X-Fixture-Access" to "fixture-access",
        )).call("fixture-echo", args).jsonObject
        assertEquals("POST", answer.s("method"))
        assertEquals("/api/rpc", answer.s("path"))
        assertEquals("application/json", answer.s("contentType"))
        assertEquals("fixture-session", answer.s("session"))
        assertEquals("fixture-access", answer.s("access"))
        assertEquals(buildJsonObject { put("name", "fixture-echo"); put("args", args) }, answer["envelope"])
    }

    @Test
    fun preservesHttpStatusAndAccessContentType(): Unit = runBlocking {
        val transport = DarwinRpcTransport()
        val raw = transport.execute(RpcRequest("$base/status/api/rpc", emptyMap(), "{}"))
        assertEquals(403, raw.status)
        assertTrue(raw.contentType.startsWith("application/json"))
        assertEquals("fixture forbidden", Json.parseToJsonElement(raw.body).jsonObject.s("error"))
        val forbidden = assertFailsWith<ServerError> { RpcClient("$base/status", transport).call("fixture-status") }
        assertEquals(403, forbidden.status)
        assertEquals(ServerErrorKind.Forbidden, forbidden.kind)
        val html = transport.execute(RpcRequest("$base/access/api/rpc", emptyMap(), "{}"))
        assertEquals("text/html", html.contentType)
        val access = assertFailsWith<ServerError> { RpcClient("$base/access", transport).call("fixture-status") }
        assertEquals(ServerErrorKind.AccessRequired, access.kind)
    }

    @Test
    fun refusesRedirectWithoutForwardingCredentialsOrBody() = runBlocking {
        val failure = assertFailsWith<ServerError> {
            RpcClient("$base/redirect", DarwinRpcTransport(), mapOf("X-Pully-Session" to "fixture-private-session"))
                .call("fixture-redirect", buildJsonObject { put("text", "fixture-private-body") })
        }
        assertEquals(307, failure.status)
        assertEquals(ServerErrorKind.Redirect, failure.kind)
        assertEquals(JsonPrimitive(0), state("redirect")["targetHits"])
    }

    @Test
    fun cancellingBlockedRequestClosesNativeConnection() = runBlocking {
        withTimeout(10_000) {
            val call = async { DarwinRpcTransport().execute(RpcRequest("$base/hold/cancel/api/rpc", emptyMap(), "{}")) }
            try {
                awaitState("cancel", "arrived")
                withTimeout(2_000) { call.cancelAndJoin() }
                assertTrue(call.isCancelled)
                awaitState("cancel", "disconnected")
            } finally {
                call.cancelAndJoin()
            }
        }
    }

    @Test
    fun nativeDeadlineClosesBlockedRequest(): Unit = runBlocking {
        val elapsed = TimeSource.Monotonic.markNow()
        withTimeout(5_000) {
            assertFailsWith<IllegalStateException> {
                DarwinRpcTransport().execute(RpcRequest(
                    "$base/hold/deadline/api/rpc", emptyMap(), "{}", readTimeoutMs = 500, timeoutMs = 500,
                ))
            }
        }
        assertTrue(elapsed.elapsedNow().inWholeMilliseconds < 5_000)
        withTimeout(5_000) { awaitState("deadline", "disconnected") }
    }

    @Test
    fun rejectsOversizeContentLengthWithAtMostOneFixtureByte(): Unit = runBlocking {
        withTimeout(5_000) {
            val failure = assertFailsWith<IllegalStateException> {
                DarwinRpcTransport().execute(RpcRequest("$base/oversize/api/rpc", emptyMap(), "{}"))
            }
            // A coroutine timeout is also an IllegalStateException; it is never the size guard.
            if (failure is CancellationException) throw failure
            assertEquals("The server response is too large.", failure.message)
            awaitState("oversize", "disconnected")
            val sent = checkNotNull(state("oversize").i("bodyBytesSent"))
            assertTrue(sent in 0..1, "The size fixture must not stream a large body: $sent bytes")
        }
    }

    private suspend fun state(id: String): JsonObject = RpcClient(base, DarwinRpcTransport())
        .call("fixture-state", buildJsonObject { put("id", id) }).jsonObject

    private suspend fun awaitState(id: String, field: String) {
        while (state(id)[field]?.jsonPrimitive?.content != "true") delay(25)
    }
}
