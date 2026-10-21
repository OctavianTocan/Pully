package com.octaviantocan.pulls.data

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        if (status in 300..399) throw ServerError(status, "The server redirected the request. Check its address and Access configuration.", kind = ServerErrorKind.Redirect)
        val text = response.body
        if (response.contentType.substringBefore(';').trim().equals("text/html", ignoreCase = true) || text.trimStart().startsWith('<'))
            throw ServerError(status, "The server returned a sign-in page. Check its Access configuration.", kind = ServerErrorKind.AccessRequired)
        if (text.isBlank()) throw ServerError(status, "The server returned nothing (HTTP $status).", kind = ServerErrorKind.Empty)
        val parsed = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw ServerError(status, "The server returned an invalid RPC response (HTTP $status).", kind = ServerErrorKind.InvalidResponse)
        val error = parsed["error"]?.takeUnless { it == JsonNull }
        if (error != null || status !in 200..299) {
            val message = (error as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: "The server rejected the request (HTTP $status)."
            throw ServerError(status, message, kind = when (status) {
                401 -> ServerErrorKind.Unauthorized
                403 -> ServerErrorKind.Forbidden
                else -> ServerErrorKind.Server
            })
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
    }
}

enum class ServerErrorKind { Server, Empty, InvalidResponse, AccessRequired, Unauthorized, Forbidden, Redirect }

/** Same HTTP status API Android callers already use, with a portable recoverable error category. */
class ServerError(val status: Int, message: String, cause: Throwable? = null, val kind: ServerErrorKind = ServerErrorKind.Server) : IllegalStateException(message, cause)

class RpcTimeoutException : IllegalStateException("The server request timed out.")
