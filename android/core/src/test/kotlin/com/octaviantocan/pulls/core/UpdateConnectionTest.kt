package com.octaviantocan.pulls.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateConnectionTest {
    private val headers = mapOf("CF-Access-Client-Id" to "synthetic-id", "CF-Access-Client-Secret" to "synthetic-secret")

    @Test
    fun originIncludesSchemeHostAndEffectivePort() {
        assertEquals("https://example.test/android/pulls.apk", updateUrl("https://example.test", "/android/pulls.apk").toString())
        assertEquals("https://EXAMPLE.test:443/android/pulls.apk", updateUrl("https://example.test", "https://EXAMPLE.test:443/android/pulls.apk").toString())
        for (location in listOf("http://example.test/a.apk", "https://other.test/a.apk", "https://example.test:444/a.apk", "//other.test/a.apk", "https://user:password@example.test/a.apk")) {
            assertFailsWith<IllegalArgumentException> { updateUrl("https://example.test", location) }
        }
    }

    @Test
    fun crossOriginManifestAndRedirectNeverContactForeignEndpoint() {
        val foreignCalls = AtomicInteger()
        val originSecret = AtomicReference<String?>()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val foreign = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        foreign.createContext("/") { exchange ->
            foreignCalls.incrementAndGet()
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        val server = "http://127.0.0.1:${origin.address.port}"
        val foreignUrl = "http://127.0.0.1:${foreign.address.port}/pulls.apk"
        origin.createContext("/redirect") { exchange ->
            originSecret.set(exchange.requestHeaders.getFirst("CF-Access-Client-Secret"))
            exchange.responseHeaders.add("Location", foreignUrl)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        origin.start()
        foreign.start()
        try {
            assertFailsWith<IllegalArgumentException> { openUpdateConnection(server, foreignUrl, headers) }
            assertFailsWith<IllegalArgumentException> { openUpdateConnection(server, "/redirect", headers) }
            assertEquals(0, foreignCalls.get())
            assertEquals("synthetic-secret", originSecret.get())
        } finally {
            origin.stop(0)
            foreign.stop(0)
        }
    }

    @Test
    fun sameOriginRelativeRedirectKeepsCredentialsAndDownloads() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<String?>()
        server.createContext("/nested/start") { exchange ->
            seen.add(exchange.requestHeaders.getFirst("CF-Access-Client-Secret"))
            exchange.responseHeaders.add("Location", "../pulls.apk")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/pulls.apk") { exchange ->
            seen.add(exchange.requestHeaders.getFirst("CF-Access-Client-Secret"))
            val bytes = "synthetic apk".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val connection = openUpdateConnection("http://127.0.0.1:${server.address.port}", "/nested/start", headers)
            try {
                assertEquals("synthetic apk", connection.inputStream.bufferedReader().use { it.readText() })
                assertEquals(listOf<String?>("synthetic-secret", "synthetic-secret"), seen)
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop(0)
        }
    }
}
