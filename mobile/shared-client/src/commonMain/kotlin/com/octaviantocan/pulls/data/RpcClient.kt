package com.octaviantocan.pulls.data

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The existing phone RPC envelope, independent of a native networking or settings API. */
class RpcClient(server: String, private val transport: RpcTransport, headers: Map<String, String> = emptyMap()) {
    private val endpoint = ServerEndpoint.parse(server)
    private val headers = headers.toMap().also {
        require(it.isEmpty() || carriesSecrets(server)) { "Pully sends credentials only over HTTPS or to a local development host." }
    }

    suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
        val request = request("rpc", buildJsonObject { put("name", name); put("args", args) }.toString())
        val response = execute(request)
        val status = response.status
        val text = response.body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val format = responseFormat(response.contentType, text)
        fun failure(kind: ServerErrorKind, message: String): Nothing = throw ServerError(status, message, kind = kind, format = format)
        if (status in 300..399) {
            if (isAccessLogin(response.location)) failure(ServerErrorKind.AccessRequired, "This server requires Access authorization. Check Server settings.")
            failure(ServerErrorKind.Redirect, "The server redirected the request. Check its address in Server settings.")
        }
        if (format == RpcResponseFormat.Html)
            failure(ServerErrorKind.HtmlResponse, "The server returned a web page instead of Pully data. Try again or check Server settings.")
        if (text.isBlank()) failure(ServerErrorKind.Empty, "The server returned nothing. Try again.")
        val parsed = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: failure(if (status in 200..299) ServerErrorKind.InvalidResponse else ServerErrorKind.Http, "Pully could not read the server's response. Try again or check Server settings.")
        val error = parsed["error"]?.takeUnless { it == JsonNull }
        if (error != null || status !in 200..299) {
            val message = (error as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (error != null && message == null) failure(ServerErrorKind.InvalidResponse, "Pully could not read the server's response. Try again.")
            failure(if (message == null) ServerErrorKind.Http else when (status) {
                401 -> ServerErrorKind.Unauthorized
                403 -> ServerErrorKind.Forbidden
                else -> ServerErrorKind.Server
            }, message?.let { safeMessage(it, args) } ?: "The server could not complete this request. Try again.")
        }
        return parsed["result"] ?: JsonNull
    }

    /** Existing first-party analytics envelope; retry policy continues to use its HTTP status. */
    suspend fun postEvents(body: String): Int {
        val request = request("events", body)
        return execute(request).status
    }

    private fun request(path: String, body: String) = RpcRequest(endpoint.api(path), headers, body)

    private suspend fun execute(request: RpcRequest): RpcResponse = try {
        withTimeout(request.timeoutMs) { transport.execute(request) }
    } catch (timeout: TimeoutCancellationException) {
        // A caller's cancellation still propagates; our own network deadline is a recoverable load failure.
        currentCoroutineContext().ensureActive()
        throw RpcTimeoutException()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Native exception messages may include addresses, query strings or credentials.
        throw RpcConnectionException()
    }

    private fun safeMessage(message: String, args: JsonObject): String {
        if (message.trimStart().startsWith('<')) return "The server could not complete this request. Try again."
        var safe = message
        val secrets = headers.values + args.filterKeys { it in setOf("password", "idToken", "session", "apiKey", "accessSecret") }
            .values.mapNotNull { (it as? JsonPrimitive)?.content }
        secrets.filter { it.isNotEmpty() }.forEach { safe = safe.replace(it, "[redacted]") }
        return safe.take(1200)
    }
}

private fun isAccessLogin(location: String): Boolean = Regex(
    """https://[a-z0-9-]+\.cloudflareaccess\.com(?::443)?/cdn-cgi/access/login(?:/[^?#]*)?(?:[?#].*)?""",
    RegexOption.IGNORE_CASE,
).matches(location)

private fun responseFormat(contentType: String, text: String): RpcResponseFormat {
    val mime = contentType.substringBefore(';').trim().lowercase()
    return when {
        mime in setOf("text/html", "application/xhtml+xml") || text.startsWith("<!doctype html", true) || text.startsWith("<html", true) -> RpcResponseFormat.Html
        text.isBlank() -> RpcResponseFormat.Empty
        mime == "application/json" || mime.endsWith("+json") -> RpcResponseFormat.Json
        else -> RpcResponseFormat.Other
    }
}

enum class ServerErrorKind { Server, Empty, InvalidResponse, HtmlResponse, Http, AccessRequired, Unauthorized, Forbidden, Redirect }

/** Bounded diagnostics: never contains a response body, URL, headers or credential values. */
enum class RpcResponseFormat { Unknown, Empty, Html, Json, Other }

/** Same HTTP status API Android callers already use, with a portable recoverable error category. */
class ServerError(val status: Int, message: String, cause: Throwable? = null, val kind: ServerErrorKind = ServerErrorKind.Server, val format: RpcResponseFormat = RpcResponseFormat.Unknown) : IllegalStateException(message, cause)

class RpcTimeoutException : IllegalStateException("The server request timed out.")

class RpcConnectionException : IllegalStateException("Pully could not reach the server. Check your connection and try again.")
