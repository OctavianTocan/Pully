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
class IosUsageAnalytics(
    private val scope: CoroutineScope,
    private val configuration: () -> RepoConfiguration?,
    private val owner: () -> String?,
    private val enabled: () -> Boolean,
    private val version: String,
) {
    private class Binding(val source: RpcSource, val namespace: String, val queue: UsageAnalyticsQueue)
    private var binding: Binding? = null
    private var ticker: Job? = null
    private var foreground = false
    private var closed = false
    private var opened = false
    private var frontSince: Long? = null
    private var screen = "start"
    private var screens = ScreenClock()
    private val processStart = TimeSource.Monotonic.markNow()
    private val transport = DarwinRpcTransport()

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
        emit(screens.show(name, monotonicMillis()))
    }

    fun firstFrame() = onMain {
        if (!opened) active()?.let {
            opened = true
            it.track("app.open", mapOf("start" to "cold", "ms" to monotonicMillis()))
        }
    }

    fun foreground() = onMain {
        if (foreground) return@onMain
        foreground = true
        frontSince = monotonicMillis()
        screens.resume(monotonicMillis())
        active()?.foreground(Clock.System.now().toEpochMilliseconds())
        startTicker()
    }

    fun background() = onMain {
        if (!foreground) return@onMain
        val now = monotonicMillis()
        emit(screens.pause(now))
        frontSince?.let { active()?.track("app.background", mapOf("seconds" to (now - it) / 1000)) }
        frontSince = null
        foreground = false
        ticker?.cancel(); ticker = null
        active()?.let { queue ->
            queue.background(Clock.System.now().toEpochMilliseconds())
            // Observation may be suspended by iOS; persisted events retry on the next foreground.
            scope.launch { withTimeoutOrNull(3_000) { queue.flush() } }
        }
    }

    fun setEnabled(value: Boolean) = onMain {
        if (!value) {
            // Invalidate pending writes synchronously, including writes from already retired sources.
            IosAnalyticsFiles.invalidateWrites()
            binding?.queue?.setEnabled(false)
            invalidateBinding()
            IosAnalyticsFiles.dropAll()
        } else if (foreground) {
            active()?.foreground(Clock.System.now().toEpochMilliseconds())
            startTicker()
        }
    }

    fun invalidateSource() = onMain {
        invalidateBinding()
        screens = ScreenClock()
        screen = "start"
    }

    fun close() = onMain {
        closed = true
        invalidateBinding()
    }

    private fun active(): UsageAnalyticsQueue? {
        if (closed || !enabled()) return null
        val config = runCatching { configuration() }.getOrNull() ?: return null
        val source = runCatching { config.source }.getOrNull() ?: return null
        val email = owner()
        val eligible = email != null && config.headers.any { (name, value) -> name.equals("X-Pully-Session", true) && value.isNotEmpty() }
        val namespace = sourceStorageNamespace(config.server, email, eligible, config.headers) ?: return null
        binding?.takeIf { it.source == source && it.namespace == namespace }?.let { return it.queue }
        invalidateBinding()
        val queue = UsageAnalyticsQueue(
            IosAnalyticsDisk(namespace), IosAnalyticsFiles.io, "ios", version,
            { Clock.System.now().toString() }, { NSUUID().UUIDString },
            { runCatching { configuration()?.source == source }.getOrDefault(false) },
            { body ->
                val opening = configuration() ?: throw CancellationException("The usage source changed.")
                if (opening.source != source) throw CancellationException("The usage source changed.")
                val status = RpcClient(opening.server, transport, opening.headers).postEvents(body)
                val latest = configuration()
                if (latest == null || latest.source != source || latest.headers != opening.headers)
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
        if (closed || !foreground || !enabled() || ticker?.isActive == true) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive && foreground && !closed && enabled()) {
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

    private fun monotonicMillis(): Long = processStart.elapsedNow().inWholeMilliseconds
    private fun onMain(action: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { if (!closed) action() }
    }
}
