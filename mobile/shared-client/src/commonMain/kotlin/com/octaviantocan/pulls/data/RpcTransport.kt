package com.octaviantocan.pulls.data

/** Fixed first-party RPC POSTs and tokenless public GETs share cancellation and redirect refusal. */
fun interface RpcTransport {
    suspend fun execute(request: RpcRequest): RpcResponse
}

enum class RpcMethod(val value: String) { Post("POST"), Get("GET") }

class RpcRequest(
    val url: String,
    headers: Map<String, String>,
    val body: String,
    val connectTimeoutMs: Int = 20_000,
    val readTimeoutMs: Int = 70_000,
    val timeoutMs: Long = 90_000,
    val method: RpcMethod = RpcMethod.Post,
    val responseLimit: Int = RPC_RESPONSE_LIMIT,
) {
    val headers: Map<String, String> = headers.toMap()
    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && timeoutMs > 0)
        require(responseLimit in 1..RPC_RESPONSE_LIMIT)
        require(method != RpcMethod.Get || body.isEmpty()) { "A public GET has no request body." }
        require(headers.all { (name, value) ->
            name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) &&
                name.lowercase() !in setOf("host", "content-length", "transfer-encoding", "connection", "content-type") &&
                value.none { it == '\r' || it == '\n' || it.code == 0 }
        }) { "Invalid configured request headers." }
    }
}

class RpcResponse(
    val status: Int,
    val body: String,
    val contentType: String = "",
    internal val location: String = "",
    internal val rateLimitRemaining: Long? = null,
    internal val retryAfterSeconds: Int? = null,
)

/** Shared bound for an RPC response; lite diff opening remains the normal large-diff path. */
const val RPC_RESPONSE_LIMIT = 64 * 1024 * 1024
