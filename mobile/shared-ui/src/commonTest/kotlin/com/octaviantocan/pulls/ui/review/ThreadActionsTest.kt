package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.sound.Cue
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadActionsTest {
    @Test
    fun aReplyRunsOnceAndClearsItsDraftBeforeReloading() = runTest {
        val events = mutableListOf<String>()
        val result = CompletableDeferred<Unit>()
        val actions = ThreadActions(backgroundScope, { events += "reload" }) { cue, _ -> events += cue.name }
        actions.run({ result.await() }) { events += "clear draft" }
        runCurrent()
        actions.run({ error("A second reply must not run") })
        assertTrue(actions.busy)
        result.complete(Unit)
        runCurrent()
        assertFalse(actions.busy)
        assertNull(actions.error)
        assertEquals(listOf("clear draft", "reload", Cue.Success.name), events)
    }

    @Test
    fun aRejectedWriteStaysOnTheCardAndCanBeRetried() = runTest {
        val events = mutableListOf<String>()
        val actions = ThreadActions(backgroundScope, { events += "reload" }) { cue, _ -> events += cue.name }
        actions.run({ error("Write permission changed") })
        runCurrent()
        assertEquals("Write permission changed", actions.error)
        assertFalse(actions.busy)
        assertEquals(listOf(Cue.Error.name), events)
        actions.run({})
        assertNull(actions.error)
        runCurrent()
        assertEquals(listOf(Cue.Error.name, "reload", Cue.Success.name), events)
    }

    @Test
    fun detachingCannotClearDraftsReloadOrSoundForAnUncancellableLateWrite() = runTest {
        val events = mutableListOf<String>()
        val owner = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + owner)
        val result = CompletableDeferred<Unit>()
        val actions = ThreadActions(scope, { events += "reload" }) { cue, _ -> events += cue.name }
        actions.run({ withContext(NonCancellable) { result.await() } }) { events += "clear draft" }
        runCurrent()
        owner.cancel()
        result.complete(Unit)
        runCurrent()
        assertFalse(actions.busy)
        assertNull(actions.error)
        assertEquals(emptyList(), events)
    }
}
