package com.octaviantocan.pulls.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsageAnalyticsQueueTest {
    @Test
    fun startingEventsMergeAfterDurableEventsOnceAndKeepOnlySafeProperties() = runTest {
        val h = Harness(listOf(event("screen.view"), "partial", event("app.open")))
        h.queue.track("rpc.call", mapOf("method" to "diff", "ms" to 3, "query" to "private", "token" to "private"))
        h.io.drain()
        assertEquals(listOf("screen.view", "app.open", "rpc.call"), h.disk.lines.map(::name))
        assertEquals(1, h.disk.writes)
        assertTrue(h.queue.flush())
        val sent = events(h.sent.single())
        assertEquals(3, sent.size)
        assertEquals("ios", sent.last().jsonObject["app"]?.jsonPrimitive?.content)
        assertEquals(setOf("method", "ms"), sent.last().jsonObject["props"]?.jsonObject?.keys)
        h.io.drain()
        assertTrue(h.disk.lines.isEmpty())
    }

    @Test
    fun aBurstIsBoundedAndCoalescesItsDiskRewrite() = runTest {
        val h = Harness()
        h.io.drain()
        val writes = h.disk.writes
        repeat(5_001) { h.queue.track("file.view", mapOf("index" to it)) }
        assertEquals(4_500, h.queue.size)
        assertEquals(1, h.io.pending.size)
        h.io.drain()
        assertEquals(writes + 1, h.disk.writes)
        assertEquals(4_500, h.disk.lines.size)
        assertEquals("501", Json.parseToJsonElement(h.disk.lines.first()).jsonObject["props"]?.jsonObject?.get("index")?.toString())
    }

    @Test
    fun anAcknowledgementCannotRemoveAnEventAddedDuringTheRequest() = runTest {
        val h = Harness()
        h.io.drain()
        repeat(201) { h.queue.track("rpc.call", mapOf("index" to it)) }
        val waiting = CompletableDeferred<Unit>()
        h.reply = { if (h.sent.size == 1) waiting.await(); 204 }
        val flush = async { h.queue.flush() }
        runCurrent()
        assertEquals(200, events(h.sent.single()).size)
        h.queue.track("screen.view")
        waiting.complete(Unit)
        assertTrue(flush.await())
        assertEquals(listOf(200, 2), h.sent.map { events(it).size })
        assertEquals("screen.view", events(h.sent.last()).last().jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(0, h.queue.size)
    }

    @Test
    fun transientAndAccessFailuresRetainEventsButExplicitRefusalDropsOnlyItsBatch() = runTest {
        val h = Harness()
        h.io.drain()
        repeat(201) { h.queue.track("rpc.call") }
        h.reply = { 403 }
        assertFalse(h.queue.flush())
        assertEquals(201, h.queue.size)
        h.reply = { if (h.sent.size == 2) 413 else 503 }
        assertFalse(h.queue.flush())
        assertEquals(1, h.queue.size)
        h.reply = { 204 }
        assertTrue(h.queue.flush())
        assertEquals(0, h.queue.size)
    }

    @Test
    fun turningStatsOffCancelsTheActualUploadAndRemovesDurableAndNewEvents() = runTest {
        val h = Harness(listOf(event("app.open")))
        h.io.drain()
        var detached = false
        h.reply = { try { awaitCancellation() } finally { detached = true } }
        val flush = async { h.queue.flush() }
        runCurrent()
        h.queue.setEnabled(false)
        flush.cancelAndJoin()
        assertTrue(detached)
        h.queue.track("screen.view")
        h.io.drain()
        assertEquals(0, h.queue.size)
        assertTrue(h.disk.lines.isEmpty())
        assertFalse(h.queue.flush())
        assertEquals(1, h.sent.size)
    }

    @Test
    fun disabledStartupCannotRestoreAPreviousQueue() = runTest {
        val h = Harness(listOf(event("app.open")), enabled = false)
        h.queue.track("screen.view")
        h.io.drain()
        assertEquals(0, h.queue.size)
        assertTrue(h.disk.lines.isEmpty())
        h.queue.setEnabled(true)
        h.queue.track("rpc.call")
        assertTrue(h.queue.flush())
        assertEquals(listOf("rpc.call"), events(h.sent.single()).map { it.jsonObject["name"]?.jsonPrimitive?.content })
    }

    @Test
    fun aRetiredSourceCannotAcknowledgeItsLateNoncancellableRequest() = runTest {
        val h = Harness()
        h.io.drain()
        h.queue.track("app.open")
        val waiting = CompletableDeferred<Unit>()
        h.reply = { withContext(NonCancellable) { waiting.await() }; 204 }
        val flush = async { h.queue.flush() }
        runCurrent()
        h.current = false
        h.queue.close()
        waiting.complete(Unit)
        flush.cancelAndJoin()
        h.io.drain()
        assertEquals(1, h.queue.size)
        assertEquals(listOf("app.open"), h.disk.lines.map(::name))
        assertFalse(h.queue.flush())
    }

    @Test
    fun closingBeforeLoadPersistsItsMergedSnapshotBeforeTheReplacementLoads() = runTest {
        val io = SerialIo()
        val disk = MemoryDisk(listOf(event("app.open")))
        val first = Harness(io = io, disk = disk)
        first.queue.track("screen.view")
        first.queue.close()
        val second = Harness(io = io, disk = disk)
        io.drain()
        assertEquals(listOf("app.open", "screen.view"), disk.lines.map(::name))
        assertEquals(2, second.queue.size)
        assertTrue(second.queue.flush())
        assertEquals(listOf("app.open", "screen.view"), events(second.sent.single()).map { it.jsonObject["name"]?.jsonPrimitive?.content })
    }

    @Test
    fun theExistingThirtyMinuteSessionRuleSurvivesProcessRestoration() = runTest {
        val h = Harness()
        h.io.drain()
        val first = h.disk.meta["session"]
        h.queue.background(1_000)
        h.queue.foreground(1_000 + AnalyticsRules.SESSION_GAP_MS - 1)
        assertEquals(first, h.disk.meta["session"])
        h.queue.background(1_000)
        h.queue.foreground(1_000 + AnalyticsRules.SESSION_GAP_MS)
        assertTrue(first != h.disk.meta["session"])
        h.queue.close()
        val restored = Harness(io = h.io, disk = h.disk)
        restored.queue.track("app.open")
        h.io.drain()
        assertTrue(restored.queue.flush())
        assertEquals(h.disk.meta["session"], events(restored.sent.single()).last().jsonObject["session"]?.jsonPrimitive?.content)
    }

    private class SerialIo : AnalyticsIo {
        val pending = ArrayDeque<() -> Unit>()
        override fun execute(work: () -> Unit) { pending.addLast(work) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst()() }
    }
    private class MemoryDisk(initial: List<String>) : AnalyticsDisk {
        var lines = initial
        var writes = 0
        val meta = mutableMapOf<String, String>()
        override fun read() = lines.toList()
        override fun replace(lines: List<String>) { this.lines = lines; writes++ }
        override fun metadata(name: String) = meta[name]
        override fun metadata(name: String, value: String?) { if (value == null) meta.remove(name) else meta[name] = value }
    }
    private class Harness(initial: List<String> = emptyList(), enabled: Boolean = true, val io: SerialIo = SerialIo(), val disk: MemoryDisk = MemoryDisk(initial)) {
        var current = true
        var id = 0
        val sent = mutableListOf<String>()
        var reply: suspend () -> Int = { 204 }
        val queue = UsageAnalyticsQueue(disk, io, "ios", "test", { "2026-10-21T16:00:00Z" }, { "id-${++id}" }, { current }, { sent += it; reply() }, enabled)
    }
    companion object {
        private fun event(name: String) = AnalyticsEvent(name, "2026-10-21T16:00:00Z", "test", "session", "device", app = "ios").encode()
        private fun name(line: String) = Json.parseToJsonElement(line).jsonObject["name"]?.jsonPrimitive?.content
        private fun events(body: String) = Json.parseToJsonElement(body).jsonObject["events"]!!.jsonArray
    }
}
