@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSUUID
import kotlin.time.Clock
import kotlin.time.TimeSource

/** First-party usage events only; owner() is sampled on Main, never by the serial file worker. */
class IosUsageAnalytics private constructor(
    private val scope: CoroutineScope,
    private val configuration: () -> RepoConfiguration?,
    private val owner: () -> String?,
    private val enabled: () -> Boolean,
    private val version: String,
    private val coldStart: Boolean,
    private val environment: IosUsageEnvironment,
) {
    constructor(scope: CoroutineScope, configuration: () -> RepoConfiguration?, owner: () -> String?, enabled: () -> Boolean, version: String, coldStart: Boolean = true) :
        this(scope, configuration, owner, enabled, version, coldStart, IosUsageEnvironment())

    internal constructor(scope: CoroutineScope, configuration: () -> RepoConfiguration?, owner: () -> String?, enabled: () -> Boolean, version: String, environment: IosUsageEnvironment) :
        this(scope, configuration, owner, enabled, version, false, environment)

    private class Binding(val source: RpcSource, val namespace: String, val queue: UsageAnalyticsQueue)
    private var binding: Binding? = null
    private var ticker: Job? = null
    private var foreground = false
    private var closed = false
    private var opened = false
    private var rendered = false
    private var privateConnection = false
    private var screen = "start"
    private var clock = PrivateUsageClock()
    private val processStart = TimeSource.Monotonic.markNow()

    val rpcObserver = RpcObserver { method, ms, ok, status ->
        // Capture only an opaque source and scalar metadata before crossing to Main.
        val source = runCatching { configuration()?.source }.getOrNull()
        onMain {
            if (source != null && source == runCatching { configuration()?.source }.getOrNull())
                active()?.track("rpc.call", mapOf("method" to method, "ms" to ms, "ok" to ok, "status" to status))
        }
    }

    fun track(name: String, vararg props: Pair<String, Any?>) = onMain { active()?.track(name, mapOf(*props)) }

    /** Server error text may contain read/typed content; the native event records its fixed kind only. */
    @Suppress("UNUSED_PARAMETER")
    fun error(kind: String, message: String?) = onMain {
        active()?.track("error.client", mapOf("kind" to kind, "screen" to screen))
    }

    fun showScreen(name: String) = onMain {
        screen = name
        emit(clock.show(name, monotonicMillis()))
    }

    fun firstFrame() = onMain {
        rendered = true
        recordOpen()
    }

    fun foreground() = onMain {
        if (foreground) return@onMain
        foreground = true
        clock.foreground(monotonicMillis())
        active()?.foreground(Clock.System.now().toEpochMilliseconds())
        startTicker()
    }

    fun background() = onMain {
        if (!foreground) return@onMain
        emit(clock.background(monotonicMillis()))
        foreground = false
        ticker?.cancel(); ticker = null
        active()?.let { queue ->
            queue.background(Clock.System.now().toEpochMilliseconds())
            // Observation may be suspended by iOS; persisted events retry on the next foreground.
            scope.launch { withTimeoutOrNull(3_000) { queue.flush() } }
        }
    }

    /** Finish the old private interval before revoking its queue; Guest never changes consent. */
    fun setPrivateConnection(available: Boolean) = onMain {
        if (privateConnection == available) return@onMain
        val events = clock.setPrivateConnection(available, monotonicMillis())
        // Account invalidation clears its public email before this callback. The already-bound
        // queue still owns these final events until storage publishes the replacement source.
        events.forEach { (name, props) -> binding?.queue?.track(name, props) }
        privateConnection = available
        if (!available) {
            binding?.queue?.background(Clock.System.now().toEpochMilliseconds())
            invalidateBinding()
        } else {
            emit(clock.show(screen, monotonicMillis()))
            recordOpen()
            if (foreground) { active()?.foreground(Clock.System.now().toEpochMilliseconds()); startTicker() }
        }
    }

    fun setEnabled(value: Boolean) = onMain {
        if (!value) {
            // Invalidate pending writes synchronously, including writes from already retired sources.
            IosAnalyticsFiles.invalidateWrites()
            binding?.queue?.setEnabled(false)
            invalidateBinding()
            IosAnalyticsFiles.dropAll()
        } else if (foreground && privateConnection) {
            active()?.foreground(Clock.System.now().toEpochMilliseconds())
            startTicker()
        }
    }

    fun invalidateSource() = onMain {
        privateConnection = false
        invalidateBinding()
        clock = PrivateUsageClock().also { if (foreground) it.foreground(monotonicMillis()) }
        screen = "start"
    }

    fun close() = onMain {
        closed = true
        invalidateBinding()
    }

    private fun active(): UsageAnalyticsQueue? {
        if (closed || !privateConnection || !enabled()) return null
        val config = runCatching { configuration() }.getOrNull() ?: return null
        val source = runCatching { config.source }.getOrNull() ?: return null
        val email = owner()
        val eligible = email != null && config.headers.any { (name, value) -> name.equals("X-Pully-Session", true) && value.isNotEmpty() }
        val namespace = sourceStorageNamespace(config.server, email, eligible, config.headers) ?: return null
        binding?.takeIf { it.source == source && it.namespace == namespace }?.let { return it.queue }
        invalidateBinding()
        val queue = UsageAnalyticsQueue(
            environment.disk(namespace), environment.io, "ios", version,
            { Clock.System.now().toString() }, environment.freshId,
            { runCatching { configuration()?.source == source }.getOrDefault(false) },
            { body ->
                if (!privateConnection) throw CancellationException("Private usage is paused.")
                val opening = configuration() ?: throw CancellationException("The usage source changed.")
                if (opening.source != source) throw CancellationException("The usage source changed.")
                val status = RpcClient(opening.server, environment.transport, opening.headers).postEvents(body)
                val latest = configuration()
                if (!privateConnection || latest == null || latest.source != source || latest.headers != opening.headers)
                    throw CancellationException("The usage source changed.")
                status
            }, enabled(),
        )
        binding = Binding(source, namespace, queue)
        if (foreground) {
            queue.foreground(Clock.System.now().toEpochMilliseconds())
            startTicker()
        }
        return queue
    }

    private fun startTicker() {
        if (closed || !foreground || !privateConnection || !enabled() || ticker?.isActive == true) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive && foreground && privateConnection && !closed && enabled()) {
                active()?.flush()
                delay(AnalyticsRules.FLUSH_EVERY_MS)
            }
        }
        ticker = next
        next.start()
    }

    private fun invalidateBinding() {
        ticker?.cancel(); ticker = null
        binding?.queue?.close()
        binding = null
    }

    private fun emit(events: List<Pair<String, Map<String, Any?>>>) {
        events.forEach { (name, props) -> active()?.track(name, props) }
    }

    private fun recordOpen() {
        if (rendered && !opened) active()?.let {
            opened = true
            it.track("app.open", mapOf("start" to if (coldStart) "cold" else "warm", "ms" to monotonicMillis()))
        }
    }

    private fun monotonicMillis(): Long = environment.monotonicMillis?.invoke() ?: processStart.elapsedNow().inWholeMilliseconds
    private fun onMain(action: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { if (!closed) action() }
    }
}

/** Tests replace only the platform clock/I/O/transport, while exercising the actual queue and adapter. */
internal class IosUsageEnvironment(
    val transport: RpcTransport = DarwinRpcTransport(),
    val disk: (String) -> AnalyticsDisk = ::IosAnalyticsDisk,
    val io: AnalyticsIo = IosAnalyticsFiles.io,
    val monotonicMillis: (() -> Long)? = null,
    val freshId: () -> String = { NSUUID().UUIDString },
)
