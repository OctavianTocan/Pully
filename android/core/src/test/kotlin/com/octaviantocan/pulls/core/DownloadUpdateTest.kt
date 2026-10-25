package com.octaviantocan.pulls.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadUpdateTest {
    private val payload = ByteArray(256 * 1024) { (it % 251).toByte() }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { createContext("/apk", handler); start() }

    private fun withDownload(handler: (HttpExchange) -> Unit, test: (String, File) -> Unit) {
        val server = server(handler)
        val directory = Files.createTempDirectory("update-download-test").toFile()
        try { test("http://127.0.0.1:${server.address.port}", File(directory, "update.apk")) }
        finally { server.stop(0); directory.deleteRecursively() }
    }

    @Test
    fun completeChunkedResponseIsVerifiedBeforeReplacingDestination() = withDownload({ exchange ->
        assertEquals("no-cache", exchange.requestHeaders.getFirst("Cache-Control"))
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { it.write(payload) }
    }) { server, file ->
        file.writeText("previous verified APK")
        val progress = mutableListOf<Pair<Long, Long>>()
        downloadUpdate(server, "/apk", sha256Hex(payload).uppercase(), emptyMap(), file) { read, total ->
            // The public destination is never a partially downloaded APK.
            assertEquals("previous verified APK", file.readText())
            progress += read to total
        }
        assertContentEquals(payload, file.readBytes())
        assertEquals(0L, progress.first().first)
        assertEquals(payload.size.toLong(), progress.last().first)
        assertTrue(progress.all { it.second == -1L })
        assertEquals(listOf(file.name), file.parentFile.list()!!.toList())
    }

    @Test
    fun digestFailureDeletesPartialAndPreservesPreviousVerifiedFile() = withDownload({ exchange ->
        exchange.sendResponseHeaders(200, payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
    }) { server, file ->
        file.writeText("old verified APK")
        assertFailsWith<DigestMismatch> {
            downloadUpdate(server, "/apk", "0".repeat(64), emptyMap(), file) { _, _ -> }
        }
        assertEquals("old verified APK", file.readText())
        assertEquals(listOf(file.name), file.parentFile.list()!!.toList())
    }

    @Test
    fun apkDownloadDoesNotCarryScopedHeadersToForbiddenSameOriginRedirect() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<String?>()
        server.createContext("/download/staging/pulls.apk") { exchange ->
            seen.add(exchange.requestHeaders.getFirst("CF-Access-Client-Secret"))
            exchange.responseHeaders.add("Location", "/api/rpc")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/api/rpc") { exchange ->
            seen.add(exchange.requestHeaders.getFirst("CF-Access-Client-Secret"))
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        val directory = Files.createTempDirectory("update-redirect-test").toFile()
        server.start()
        try {
            val file = File(directory, "update.apk")
            val staging = "https://pully-staging.octaviantocan.com"
            downloadUpdate("http://127.0.0.1:${server.address.port}", "/download/staging/pulls.apk", sha256Hex(payload), emptyMap(), file,
                headersForUrl = { target ->
                    com.octaviantocan.pulls.data.stagingAccessHeaders(staging, target.toURI().rawPath, staging, "fixture-id", "fixture-secret")
                },
            ) { _, _ -> }
            assertEquals(listOf("fixture-secret", null), seen)
            assertContentEquals(payload, file.readBytes())
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun truncatedResponseAndInterruptedConsumerLeaveNoPartialFiles() {
        withDownload({ exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            runCatching { exchange.responseBody.use { it.write(payload, 0, 32) } }
            exchange.close()
        }) { server, file ->
            assertFailsWith<IOException> {
                downloadUpdate(server, "/apk", sha256Hex(payload), emptyMap(), file, UpdateTimeouts(totalMs = 2_000, readMs = 200)) { _, _ -> }
            }
            assertFalse(file.exists())
            assertTrue(file.parentFile.list()!!.isEmpty())
        }
        withDownload({ exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            runCatching { exchange.responseBody.use { it.write(payload) } }
        }) { server, file ->
            assertFailsWith<IOException> {
                downloadUpdate(server, "/apk", sha256Hex(payload), emptyMap(), file) { read, _ -> if (read > 0) throw IOException("Interrupted") }
            }
            assertTrue(file.parentFile.list()!!.isEmpty())
        }
    }

    @Test
    fun continuallyTricklingResponseCannotHoldDownloadingForever() = withDownload({ exchange ->
        exchange.sendResponseHeaders(200, 0)
        runCatching {
            exchange.responseBody.use { output ->
                repeat(200) { output.write(1); output.flush(); Thread.sleep(20) }
            }
        }
    }) { server, file ->
        val started = System.nanoTime()
        assertFailsWith<SocketTimeoutException> {
            downloadUpdate(server, "/apk", sha256Hex(payload), emptyMap(), file, UpdateTimeouts(totalMs = 150, readMs = 1_000)) { _, _ -> }
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertTrue(file.parentFile.list()!!.isEmpty())
    }

    @Test
    fun headersAndAnIdleBodyAreAlsoCoveredByDeadline() {
        for (sendHeaders in listOf(false, true)) {
            val release = CountDownLatch(1)
            withDownload({ exchange ->
                if (sendHeaders) exchange.sendResponseHeaders(200, 0)
                release.await(2, TimeUnit.SECONDS)
                exchange.close()
            }) { server, file ->
                try {
                    assertFailsWith<SocketTimeoutException> {
                        downloadUpdate(server, "/apk", sha256Hex(payload), emptyMap(), file, UpdateTimeouts(totalMs = 100, readMs = 500)) { _, _ -> }
                    }
                    assertTrue(file.parentFile.list()!!.isEmpty())
                } finally { release.countDown() }
            }
        }
    }
}
