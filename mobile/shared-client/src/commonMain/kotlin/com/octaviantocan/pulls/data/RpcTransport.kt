package com.octaviantocan.pulls.data

/** One fixed-endpoint POST. Implementations MUST reject redirects and cancel their native request. */
fun interface RpcTransport {
    suspend fun execute(request: RpcRequest): RpcResponse
}

class RpcRequest(
    val url: String,
    headers: Map<String, String>,
    val body: String,
    val connectTimeoutMs: Int = 20_000,
    val readTimeoutMs: Int = 70_000,
    val timeoutMs: Long = 90_000,
) {
    val headers: Map<String, String> = headers.toMap()
    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && timeoutMs > 0)
        require(headers.all { (name, value) ->
            name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) &&
                name.lowercase() !in setOf("host", "content-length", "transfer-encoding", "connection", "content-type") &&
                value.none { it == '\r' || it == '\n' || it.code == 0 }
        }) { "Invalid configured request headers." }
    }
}

class RpcResponse(val status: Int, val body: String, val contentType: String = "", internal val location: String = "")

/** Shared bound for an RPC response; lite diff opening remains the normal large-diff path. */
const val RPC_RESPONSE_LIMIT = 64 * 1024 * 1024
