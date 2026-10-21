package com.octaviantocan.pulls.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmRpcTransportTest {
    private class Server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Server.executor
            createContext("/", handler)
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    @Test
    fun sendsTheActualJsonPostAndReadsUtf8Response() = runBlocking {
        var body = ""
        var type = ""
        Server { exchange ->
            body = exchange.requestBody.use { it.readBytes().decodeToString() }
            type = exchange.requestHeaders.getFirst("Content-Type")
            val bytes = """{"result":"café"}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }.use { server ->
            assertEquals(JsonPrimitive("café"), RpcClient(server.url, JvmRpcTransport()).call("repo-info"))
        }
        assertEquals("application/json", type)
        assertTrue(body.contains("\"name\":\"repo-info\""))
    }

    @Test
    fun refusesCrossOriginRedirectWithoutForwardingHeadersOrBody() = runBlocking {
        val leaked = AtomicInteger()
        Server { exchange -> leaked.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }.use { target ->
            Server { exchange ->
                exchange.requestBody.close()
                exchange.responseHeaders.add("Location", "${target.url}/stolen")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            }.use { source ->
                val failure = assertFailsWith<ServerError> {
                    RpcClient(source.url, JvmRpcTransport(), mapOf("X-Pully-Session" to "private-session")).call("account-me")
                }
                assertEquals(ServerErrorKind.Redirect, failure.kind)
                assertEquals(0, leaked.get())
            }
        }
    }

    @Test
    fun retainsAccessRedirectMetadataWithoutFollowingOrExposingItsUrl() = runBlocking {
        Server { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Location", "https://fixture.cloudflareaccess.com/cdn-cgi/access/login/server.example?secret=fixture-private")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }.use { server ->
            val failure = assertFailsWith<ServerError> { RpcClient(server.url, JvmRpcTransport()).call("account-status") }
            assertEquals(302, failure.status)
            assertEquals(ServerErrorKind.AccessRequired, failure.kind)
            assertTrue(!failure.message.orEmpty().contains("fixture-private"))
        }
    }

    @Test
    fun cancelsABlockedRequestPromptly() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        Server { exchange ->
            exchange.requestBody.close()
            arrived.complete(Unit)
            release.await(3, TimeUnit.SECONDS)
            exchange.close()
        }.use { server ->
            val call = async { JvmRpcTransport().execute(RpcRequest("${server.url}/api/rpc", emptyMap(), "{}")) }
            try {
                withTimeout(2000) { arrived.await() }
                withTimeout(1000) { call.cancelAndJoin() }
                assertTrue(call.isCancelled)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun aServerThatSendsNothingHitsTheReadTimeout(): Unit = runBlocking {
        val release = CountDownLatch(1)
        Server { exchange ->
            exchange.requestBody.close()
            release.await(3, TimeUnit.SECONDS)
            exchange.close()
        }.use { server ->
            try {
                withTimeout(2000) {
                    assertFailsWith<SocketTimeoutException> {
                        JvmRpcTransport().execute(RpcRequest("${server.url}/api/rpc", emptyMap(), "{}", readTimeoutMs = 100))
                    }
                }
            } finally { release.countDown() }
        }
    }
}
