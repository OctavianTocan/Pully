package com.octaviantocan.pulls.core

import java.net.HttpURLConnection
import java.net.URL

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
    var url = updateUrl(server, location)
    repeat(6) { attempt ->
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        var handedOff = false
        try {
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
