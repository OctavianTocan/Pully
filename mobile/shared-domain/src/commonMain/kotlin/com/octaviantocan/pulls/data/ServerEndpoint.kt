package com.octaviantocan.pulls.data

/** A configured HTTP base, with no credentials, query or fragment. Requests append fixed API paths. */
class ServerEndpoint private constructor(val base: String, val scheme: String, val host: String, val port: Int) {
    fun api(path: String): String {
        require(path == "rpc" || path == "events")
        return "$base/api/$path"
    }

    companion object {
        fun parse(raw: String): ServerEndpoint {
            val text = raw.trim().trimEnd('/')
            require(text.none { it.isWhitespace() || it.code < 32 || it == '\\' }) { "Invalid server address." }
            val match = Regex("""^(https?)://([^/?#]+)(/[^?#]*)?$""", RegexOption.IGNORE_CASE).matchEntire(text)
                ?: throw IllegalArgumentException("Use an HTTP or HTTPS server address without a query or fragment.")
            val scheme = match.groupValues[1].lowercase()
            val authority = match.groupValues[2]
            require('@' !in authority && '%' !in authority) { "The server address must not contain credentials." }
            val ipv6 = authority.startsWith('[')
            val host: String
            val suffix: String
            if (ipv6) {
                val end = authority.indexOf(']')
                require(end > 1) { "Invalid server host." }
                host = authority.substring(1, end).lowercase()
                require(':' in host && host.all { it in "0123456789abcdef:." }) { "Invalid server host." }
                suffix = authority.substring(end + 1)
            } else {
                host = authority.substringBefore(':').lowercase()
                require(host.isNotEmpty() && host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) { "Invalid server host." }
                suffix = authority.removePrefix(authority.substringBefore(':'))
            }
            val port = if (suffix.isEmpty()) {
                if (scheme == "https") 443 else 80
            } else {
                require(suffix.startsWith(':')) { "Invalid server port." }
                require(suffix.drop(1).isNotEmpty() && suffix.drop(1).all { it in '0'..'9' }) { "Invalid server port." }
                suffix.drop(1).toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("Invalid server port.")
            }
            val hostText = if (ipv6) "[$host]" else host
            val portText = if (port == (if (scheme == "https") 443 else 80)) "" else ":$port"
            return ServerEndpoint("$scheme://$hostText$portText${match.groupValues[3]}", scheme, host, port)
        }
    }
}

/** Password/session transmission is HTTPS, or the existing explicit local development hosts. */
fun carriesSecrets(url: String): Boolean = runCatching {
    val endpoint = ServerEndpoint.parse(url)
    endpoint.scheme == "https" || endpoint.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
}.getOrDefault(false)
