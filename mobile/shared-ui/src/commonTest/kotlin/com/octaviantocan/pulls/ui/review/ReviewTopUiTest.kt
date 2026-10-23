package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewTopUiTest {
    @Test
    fun oneActionRunsAtATimeAndAConfirmedMergeRefreshesBeforeItsCallback() = runTest {
        val events = mutableListOf<String>()
        val ui = ReviewTopUi { cue, emphasis -> events += "$cue:$emphasis" }
        val result = CompletableDeferred<Unit>()
        ui.polls = 7
        ui.run(backgroundScope, "merge", onOk = { events += "confirmed" }, refresh = { events += "refresh" }) { result.await() }
        runCurrent()
        ui.run(backgroundScope, "close", refresh = { error("Second action ran") }) { error("Second action ran") }
        assertEquals("merge", ui.busy)
        result.complete(Unit)
        runCurrent()
        assertNull(ui.busy)
        assertNull(ui.actionError)
        assertEquals(0, ui.polls)
        assertEquals(listOf("refresh", "${Cue.Success}:${Emphasis.Strong}", "confirmed"), events)
    }

    @Test
    fun aRejectedActionKeepsItsErrorAndTheNextAttemptCanRecover() = runTest {
        val cues = mutableListOf<Pair<Cue, Emphasis>>()
        val ui = ReviewTopUi { cue, emphasis -> cues += cue to emphasis }
        ui.run(backgroundScope, "close", refresh = { error("Rejected action refreshed") }) { error("Permission changed") }
        runCurrent()
        assertNull(ui.busy)
        assertEquals("Permission changed", ui.actionError)
        assertEquals(listOf(Cue.Error to Emphasis.Subtle), cues)
        var refreshed = 0
        ui.run(backgroundScope, "close", refresh = { refreshed++ }) {}
        assertNull(ui.actionError)
        runCurrent()
        assertEquals(1, refreshed)
        assertNull(ui.busy)
        assertEquals(Cue.Success to Emphasis.Subtle, cues.last())
    }

    @Test
    fun invalidatedRouteCannotPublishAnActionResultEvenIfItsNativeRequestIgnoresCancellation() = runTest {
        val events = mutableListOf<String>()
        val ui = ReviewTopUi { cue, _ -> events += cue.name }
        val owner = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + owner)
        val result = CompletableDeferred<Unit>()
        ui.run(scope, "merge", onOk = { events += "confirmed" }, refresh = { events += "refresh" }) {
            withContext(NonCancellable) { result.await() }
        }
        runCurrent()
        owner.cancel()
        result.complete(Unit)
        runCurrent()
        assertNull(ui.busy)
        assertNull(ui.actionError)
        assertEquals(emptyList(), events)
    }
}
