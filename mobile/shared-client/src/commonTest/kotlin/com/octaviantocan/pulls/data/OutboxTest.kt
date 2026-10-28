package com.octaviantocan.pulls.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OutboxTest {
    /** Runs the background work a minute ahead: `advanceUntilIdle` does not wait for `backgroundScope`. */
    private fun TestScope.settle() { advanceTimeBy(60_000); runCurrent() }

    private fun args(n: Int) = buildJsonObject { put("n", n) }

    @Test
    fun writesGoOutOneAtATimeInTheOrderTheyWereMade() = runTest {
        val sent = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val outbox = Outbox(backgroundScope, send = { write ->
            if (write.id == 1L) gate.await()
            sent += write.id.toInt()
        })
        outbox.add("reviewed-set", args(1)); outbox.add("reviewed-set", args(2)); outbox.add("reviewed-set", args(3))
        runCurrent()
        assertEquals(emptyList(), sent)
        assertEquals(listOf(1L, 2L, 3L), outbox.pending.map { it.id })
        gate.complete(Unit)
        settle()
        assertEquals(listOf(1, 2, 3), sent)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun aLostConnectionOrBusyServerKeepsTheWriteAndTriesAgainLater() = runTest {
        val failures = ArrayDeque(listOf<Exception>(
            RpcConnectionException(),
            ServerError(503, "busy"),
            ServerError(429, "slow down", kind = ServerErrorKind.Server),
            ServerError(302, "sign in", kind = ServerErrorKind.AccessRequired),
        ))
        var attempts = 0
        val outbox = Outbox(backgroundScope, send = { attempts++; failures.removeFirstOrNull()?.let { throw it } }, retryMs = listOf(100, 1_000))
        outbox.add("reviewed-set", args(1))
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(100); runCurrent()
        assertEquals(2, attempts)
        advanceTimeBy(999); runCurrent()
        assertEquals(2, attempts, "the wait grows")
        advanceTimeBy(1); runCurrent()
        assertEquals(3, attempts)
        settle()
        assertEquals(5, attempts)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun onlyTheServersOwnRefusalDropsAWriteAndSaysSo() = runTest {
        val rejected = mutableListOf<Long>()
        val outbox = Outbox(
            backgroundScope,
            send = { write -> if (write.id == 1L) throw ServerError(400, "Open the diff first.") },
            rejected = { write, message -> rejected += write.id; assertEquals("Open the diff first.", message) },
        )
        outbox.add("reviewed-set", args(1)); outbox.add("reviewed-set", args(2))
        settle()
        assertEquals(listOf(1L), rejected)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun clearingDropsEveryQueuedWriteOfTheOldAccount() = runTest {
        val sent = mutableListOf<Long>()
        val outbox = Outbox(backgroundScope, send = { write -> sent += write.id; throw RpcConnectionException() }, retryMs = listOf(1_000))
        outbox.add("reviewed-set", args(1)); outbox.add("reviewed-set", args(2))
        runCurrent()
        outbox.clear()
        settle()
        assertEquals(listOf(1L), sent)
        assertTrue(outbox.pending.isEmpty())
        outbox.add("reviewed-set", args(3))
        runCurrent()
        assertEquals(listOf(1L, 3L), sent)
    }
}
