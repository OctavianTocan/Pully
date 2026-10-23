package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One real serial native I/O queue; callers never perform queue-file work on the UI thread. */
fun interface AnalyticsIo { fun execute(work: () -> Unit) }

interface AnalyticsDisk {
    fun read(): List<String>
    fun replace(lines: List<String>)
    fun metadata(name: String): String?
    fun metadata(name: String, value: String?)
}

internal expect class AnalyticsLock() { fun <T> withLock(block: () -> T): T }

/** The existing bounded queue/load/batch policy, with native file I/O and source ownership injected. */
class UsageAnalyticsQueue(
    private val disk: AnalyticsDisk,
    private val io: AnalyticsIo,
    private val app: String,
    private val version: String,
    private val isoNow: () -> String,
    private val freshId: () -> String,
    private val current: () -> Boolean,
    private val send: suspend (String) -> Int,
    enabled: Boolean,
) {
    private class Queued(val sequence: Long, val line: String)
    private val lock = AnalyticsLock()
    private val queue = ArrayDeque<Queued>()
    private val sending = Mutex()
    private val requests = mutableSetOf<Job>()
    private val ready = CompletableDeferred<Unit>()
    private var sequence = 0L
    private var enabled = enabled
    private var closed = false
    private var persistScheduled = false
    private var loaded = false
    private val device = identifier("device")
    private var session = identifier("session")

    init { io.execute(::load) }

    val size: Int get() = lock.withLock { queue.size }

    fun track(name: String, props: Map<String, Any?> = emptyMap()) {
        val added = lock.withLock {
            if (closed || !enabled || !current()) return@withLock false
            val event = AnalyticsEvent(name, isoNow(), version, session, device, AnalyticsRules.cleanProps(props), app)
            queue.addLast(Queued(++sequence, event.encode()))
            repeat(AnalyticsRules.overflow(queue.size)) { queue.removeFirst() }
            true
        }
        if (added) persist()
    }

    /** Same 30-minute session rule; the wall timestamp survives process termination. */
    fun foreground(wallMillis: Long) {
        lock.withLock {
            if (closed) return@withLock
            val away = runCatching { disk.metadata("backgroundAt")?.toLongOrNull() }.getOrNull()
            val next = AnalyticsRules.session(session, away, wallMillis, freshId)
            if (next != session) {
                session = next
                runCatching { disk.metadata("session", next) }
            }
            runCatching { disk.metadata("backgroundAt", null) }
        }
    }

    fun background(wallMillis: Long) { lock.withLock { if (!closed) runCatching { disk.metadata("backgroundAt", wallMillis.toString()) } } }

    /** Dropping the queue also cancels the actual currently suspended HTTP observer. */
    fun setEnabled(value: Boolean) {
        val cancel = lock.withLock {
            if (closed) return@withLock null
            enabled = value
            if (value) emptyList() else { queue.clear(); requests.toList() }
        } ?: return
        cancel.forEach { it.cancel() }
        persist()
    }

    suspend fun flush(): Boolean {
        val caller = currentCoroutineContext()[Job]
        val active = lock.withLock {
            if (closed || !enabled || !current()) return@withLock false
            if (caller != null) requests += caller
            true
        }
        if (!active) return false
        try {
            ready.await()
            return sending.withLock sendLock@ {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val batch = lock.withLock {
                        if (closed || !enabled || !current()) return@withLock null
                        queue.take(AnalyticsRules.BATCH)
                    } ?: return@sendLock false
                    if (batch.isEmpty()) return@sendLock true
                    val status = try {
                        send(AnalyticsRules.batchBody(batch.map { it.line }))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return@sendLock false
                    }
                    currentCoroutineContext().ensureActive()
                    if (AnalyticsRules.outcome(status) == AnalyticsRules.Outcome.Later) return@sendLock false
                    val accepted = lock.withLock {
                        if (closed || !enabled || !current()) return@withLock false
                        val sent = batch.mapTo(mutableSetOf()) { it.sequence }
                        queue.removeAll { it.sequence in sent }
                        true
                    }
                    if (!accepted) return@sendLock false
                    persist()
                }
                @Suppress("UNREACHABLE_CODE") false
            }
        } finally {
            if (caller != null) lock.withLock { requests -= caller }
        }
    }

    /** Closed sources retain only their own durable unsent metadata and can no longer upload. */
    fun close() {
        val cancel = lock.withLock {
            if (closed) return@withLock null
            closed = true
            requests.toList()
        } ?: return
        cancel.forEach { it.cancel() }
        // Queue the final snapshot before a replacement source queues its load on the same worker.
        persist(final = true)
    }

    private fun identifier(name: String): String {
        val saved = runCatching { disk.metadata(name) }.getOrNull()
        if (saved != null && AnalyticsRules.validId(saved)) return saved
        return freshId().also { runCatching { disk.metadata(name, it) } }
    }

    private fun load() {
        try {
            val kept = runCatching { AnalyticsRules.newest(disk.read().filter(AnalyticsRules::isEvent)) }.getOrDefault(emptyList())
            lock.withLock {
                if (enabled) {
                    val since = queue.map { it.line }
                    queue.clear()
                    (kept + since).forEach { queue.addLast(Queued(++sequence, it)) }
                    repeat(AnalyticsRules.overflow(queue.size)) { queue.removeFirst() }
                }
                loaded = true
            }
            // Existing pending writes see the merged queue; there is never an append/rewrite duplicate.
            persist()
        } finally { ready.complete(Unit) }
    }

    /** At most one pending rewrite plus one running rewrite, even during a burst of events. */
    private fun persist(final: Boolean = false) {
        val schedule = lock.withLock {
            if ((closed && !final) || persistScheduled) false else { persistScheduled = true; true }
        }
        if (!schedule) return
        io.execute {
            val lines = lock.withLock {
                persistScheduled = false
                if (!loaded) return@withLock null
                if (enabled) queue.map { it.line } else emptyList()
            }
            if (lines != null) runCatching { disk.replace(lines) }
        }
    }
}
