package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.RpcResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskStopOrderingTest {
    @Test
    fun anOlderRejectionCannotReportAfterANewerConfirmedStopBeforeTaskObservation() = runTest {
        newerStop(observeTerminal = false)
    }

    @Test
    fun anOlderRejectionCannotReportAfterANewerConfirmedStopAndItsAuthoritativeOutcome() = runTest {
        newerStop(observeTerminal = true)
    }

    private suspend fun TestScope.newerStop(observeTerminal: Boolean) {
        for (kind in listOf("analyze", "tour", "critique", "ask", "chat")) {
            val h = StopFixture(backgroundScope, kind)
            h.page.load(); runCurrent()
            val first = CompletableDeferred<RpcResponse>()
            h.cancelReply = first
            val errors = mutableListOf<String>()
            val action = h.action(onError = errors::add)
            action.stop(); runCurrent()
            assertEquals(listOf("server-$kind"), h.cancellations)
            assertTrue(errors.isEmpty())

            h.cancelReply = null
            h.reject = false
            action.stop(); runCurrent()
            assertEquals(listOf("server-$kind", "server-$kind"), h.cancellations)
            assertTrue(errors.isEmpty())
            if (observeTerminal) {
                h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
                assertEquals("cancelled", h.page.tasks.getValue("server-$kind").status)
            } else assertTrue(h.page.tasks.getValue("server-$kind").running)

            first.complete(h.failure()); runCurrent()
            assertEquals(emptyList(), errors, kind)
            assertEquals(if (observeTerminal) "cancelled" else "running", h.page.tasks.getValue("server-$kind").status)
            assertEquals(2, h.cancellations.size)
            assertEquals(0, h.starts)
            h.page.detach()
        }
    }

    @Test
    fun anAuthoritativeTerminalTaskSilencesTheHeldFailureWithoutANewerExplicitStop() = runTest {
        val h = StopFixture(backgroundScope)
        h.page.load(); runCurrent()
        val first = CompletableDeferred<RpcResponse>()
        h.cancelReply = first
        val errors = mutableListOf<String>()
        h.action(onError = errors::add).stop(); runCurrent()
        assertEquals(1, h.cancellations.size)

        h.cancelReply = null
        h.reject = false
        // The existing cleanup path has no explicit-Stop receipt owner. Observe its real server outcome.
        h.page.cancelTask("server-tour"); runCurrent()
        h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
        assertEquals("cancelled", h.page.tasks.getValue("server-tour").status)
        first.complete(h.failure()); runCurrent()
        assertEquals(emptyList(), errors)
        assertEquals(2, h.cancellations.size)
        assertEquals(0, h.starts)
        h.page.detach()
    }

    @Test
    fun aQueuedStopWhichLosesDispatchPermissionCannotSuppressTheAcceptedRejection() = runTest {
        val h = StopFixture(backgroundScope)
        h.page.load(); runCurrent()
        var ready = true
        val first = CompletableDeferred<RpcResponse>()
        h.cancelReply = first
        val errors = mutableListOf<String>()
        val action = h.action(ReviewActionReadiness({ ready }), onError = errors::add)
        action.stop(); runCurrent()
        action.stop()
        ready = false
        h.cache.pauseObservations()
        action.close()
        runCurrent()
        assertEquals(1, h.cancellations.size)
        first.complete(h.failure()); runCurrent()
        assertEquals(listOf(StopFixture.ERROR), errors)
        assertTrue(h.page.tasks.getValue("server-tour").running)
        h.page.detach()
    }
}
