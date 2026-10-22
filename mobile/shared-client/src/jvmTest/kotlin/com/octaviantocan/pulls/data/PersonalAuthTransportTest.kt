package com.octaviantocan.pulls.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpCookie
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersonalAuthTransportTest {
    @Test
    fun neverUsesGlobalCookiesOrAuthenticatorsAndDoesNotKeepResponseCookies() = runTest {
        val previousCookies = CookieHandler.getDefault()
        val previousAuthenticator = Authenticator.getDefault()
        val authorizations = mutableListOf<String?>()
        val cookies = mutableListOf<String?>()
        val prompted = AtomicInteger()
        try {
            val jar = CookieManager()
            CookieHandler.setDefault(jar)
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    prompted.incrementAndGet()
                    return PasswordAuthentication("operator-fixture", "fixture-password".toCharArray())
                }
            })
            server({ exchange ->
                synchronized(cookies) { cookies += exchange.requestHeaders.getFirst("Cookie"); authorizations += exchange.requestHeaders.getFirst("Authorization") }
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"fixture\"")
                exchange.responseHeaders.add("Set-Cookie", "legacy-response=fixture; Path=/")
                exchange.reply(401, "{}")
            }) { origin ->
                jar.cookieStore.add(URI(origin), HttpCookie("legacy-owner", "fixture-session").apply { path = "/" })
                assertTrue(jar.get(URI("$origin/probe"), emptyMap())["Cookie"].orEmpty().any { it.contains("legacy-owner") })
                val transport = JvmPersonalAuthTransport()
                repeat(2) {
                    assertEquals(401, transport.execute(RpcRequest("$origin/probe", emptyMap(), "", method = RpcMethod.Get)).status)
                }
                assertEquals(listOf<String?>(null, null), cookies)
                assertEquals(listOf<String?>(null, null), authorizations)
                assertEquals(0, prompted.get())
            }
        } finally {
            CookieHandler.setDefault(previousCookies)
            Authenticator.setDefault(previousAuthenticator)
        }
    }

    @Test
    fun doesNotFollowRedirectsAndEnforcesTheActualStreamingBudget() = runTest {
        val reached = AtomicInteger()
        server({ exchange ->
            when (exchange.requestURI.path) {
                "/redirect" -> { exchange.responseHeaders.add("Location", "/untrusted"); exchange.reply(302, "") }
                "/large" -> exchange.reply(200, "x".repeat(2048), chunked = true)
                else -> { reached.incrementAndGet(); exchange.reply(200, "{}") }
            }
        }) { origin ->
            val transport = JvmPersonalAuthTransport()
            val redirect = transport.execute(RpcRequest("$origin/redirect", emptyMap(), "", method = RpcMethod.Get))
            assertEquals(302, redirect.status)
            assertEquals(0, reached.get())
            assertFailsWith<Exception> {
                transport.execute(RpcRequest("$origin/large", emptyMap(), "", method = RpcMethod.Get, responseLimit = 64))
            }
        }
    }

    @Test
    fun cancellingARealInFlightBodyReleasesItsNativeCall() = runTest {
        val entered = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Boolean>()
        val release = CountDownLatch(1)
        try {
            server({ exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
                entered.complete(Unit)
                if (release.await(5, TimeUnit.SECONDS)) {
                    try {
                        repeat(256) { exchange.responseBody.write(ByteArray(16 * 1024)); exchange.responseBody.flush() }
                        disconnected.complete(false)
                    } catch (_: IOException) { disconnected.complete(true) }
                }
            }) { origin ->
                val transport = JvmPersonalAuthTransport()
                val request = async { transport.execute(RpcRequest("$origin/held", emptyMap(), "", method = RpcMethod.Get)) }
                entered.await()
                withTimeout(1000) { request.cancelAndJoin() }
                assertTrue(request.isCancelled)
                release.countDown()
                // The server must observe the actual socket closure, not just a cancelled observer.
                assertTrue(withContext(Dispatchers.Default) { withTimeout(3000) { disconnected.await() } })
            }
        } finally { release.countDown() }
    }

    private suspend fun server(handler: (HttpExchange) -> Unit, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newCachedThreadPool()
        server.executor = workers
        server.createContext("/") { exchange -> try { handler(exchange) } finally { exchange.close() } }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") }
        finally { server.stop(0); workers.shutdownNow() }
    }

    private fun HttpExchange.reply(status: Int, body: String, chunked: Boolean = false) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
