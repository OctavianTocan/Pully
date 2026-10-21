package com.octaviantocan.pulls.data

import kotlin.time.TimeSource

/** A native configuration snapshot. Increase [generation] whenever server credentials or identity change. */
class RepoConfiguration(
    val server: String,
    headers: Map<String, String> = emptyMap(),
    val engine: String = "claude-code",
    val model: String = "",
    val effort: String = "",
    val generation: Long = 0,
) {
    val headers = headers.toMap()
    val source: RpcSource get() = RpcSource(ServerEndpoint.parse(server).base, generation)

    internal fun sameSource(other: RepoConfiguration): Boolean = runCatching {
        source == other.source && headers == other.headers
    }.getOrDefault(false)
}

/** Equatable cache/session namespace; it retains no headers, tokens, account email or other credentials. */
class RpcSource internal constructor(private val endpoint: String, val generation: Long) {
    override fun equals(other: Any?): Boolean = other is RpcSource && endpoint == other.endpoint && generation == other.generation
    override fun hashCode(): Int = 31 * endpoint.hashCode() + generation.hashCode()
    override fun toString(): String = "RpcSource(generation=$generation)"
}

fun interface RpcObserver {
    fun completed(name: String, durationMs: Long, ok: Boolean, status: Int?)
}

fun interface RpcClock {
    fun nowMillis(): Long
}

internal object MonotonicRpcClock : RpcClock {
    private val start = TimeSource.Monotonic.markNow()
    override fun nowMillis(): Long = start.elapsedNow().inWholeMilliseconds
}
