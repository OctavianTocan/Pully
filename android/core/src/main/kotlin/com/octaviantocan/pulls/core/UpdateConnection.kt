package com.octaviantocan.pulls.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Resolve an update location without ever sending the server's Access headers to another origin. */
fun updateUrl(server: String, location: String, relativeTo: URL = URL("${server.trimEnd('/')}/")): URL {
    val origin = URL(server)
    val target = URL(relativeTo, location)
    require(origin.protocol in setOf("http", "https") && origin.host.isNotEmpty() && origin.userInfo == null) { "Invalid update server." }
    require(target.protocol.equals(origin.protocol, ignoreCase = true) && target.host.equals(origin.host, ignoreCase = true)
        && effectivePort(target) == effectivePort(origin) && target.userInfo == null) { "Updates must stay on the configured server." }
    return target
}

private fun effectivePort(url: URL): Int = if (url.port == -1) url.defaultPort else url.port

/** Each redirect is checked before a connection is opened or credentials are attached. Caller closes the result. */
fun openUpdateConnection(server: String, location: String, headers: Map<String, String>): HttpURLConnection {
    return openUpdateConnection(server, location, headers, UpdateTimeouts()) {}
}

data class UpdateTimeouts(val totalMs: Long = 10 * 60_000L, val connectMs: Int = 15_000, val readMs: Int = 60_000) {
    init { require(totalMs > 0 && connectMs > 0 && readMs > 0) }
}

private val updateDeadlines = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "update-deadline").apply { isDaemon = true } }

/** The deadline covers redirects, headers and the entire body, including a response that never stops trickling. */
fun <T> withUpdateConnection(
    server: String,
    location: String,
    headers: Map<String, String>,
    timeouts: UpdateTimeouts = UpdateTimeouts(),
    headersForUrl: (URL) -> Map<String, String> = { headers },
    read: (HttpURLConnection) -> T,
): T {
    val current = AtomicReference<HttpURLConnection?>()
    val timedOut = AtomicBoolean(false)
    val timeout = updateDeadlines.schedule({
        timedOut.set(true)
        current.get()?.disconnect()
    }, timeouts.totalMs, TimeUnit.MILLISECONDS)
    try {
        val connection = openUpdateConnection(server, location, headers, timeouts, headersForUrl) {
            current.set(it)
            if (timedOut.get()) throw SocketTimeoutException("Update request timed out.")
        }
        val result = read(connection)
        if (timedOut.get()) throw SocketTimeoutException("Update request timed out.")
        return result
    } catch (failure: Exception) {
        if (timedOut.get()) throw SocketTimeoutException("Update request timed out.").apply { initCause(failure) }
        throw failure
    } finally {
        timeout.cancel(false)
        current.get()?.disconnect()
    }
}

private fun openUpdateConnection(
    server: String,
    location: String,
    headers: Map<String, String>,
    timeouts: UpdateTimeouts,
    headersForUrl: (URL) -> Map<String, String> = { headers },
    opened: (HttpURLConnection) -> Unit,
): HttpURLConnection {
    var url = updateUrl(server, location)
    repeat(6) { attempt ->
        // Resolve credentials afresh before opening each hop; never reuse a previous hop's baked headers.
        val requestHeaders = headersForUrl(url)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeouts.connectMs
            readTimeout = timeouts.readMs
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            instanceFollowRedirects = false
            requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        var handedOff = false
        try {
            opened(connection)
            val status = connection.responseCode
            if (status in 200..299) {
                handedOff = true
                return connection
            }
            if (status in setOf(301, 302, 303, 307, 308)) {
                val next = connection.getHeaderField("Location") ?: error("Update redirect has no location.")
                check(attempt < 5) { "Too many update redirects." }
                url = updateUrl(server, next, url)
            } else {
                // Error bodies may contain reflected request headers; never copy them into logs.
                error("Update request failed: HTTP $status")
            }
        } finally {
            if (!handedOff) connection.disconnect()
        }
    }
    error("Too many update redirects.")
}
