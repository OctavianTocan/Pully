package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** The actual remember helpers must retire even when a parent hides the whole unreadable surface. */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxAskOwnershipTest {
    @Test
    fun actualPolicyRemovalReplacementAndDisposalRetireTheDrawnInboxControlsAndObservation() = runTest {
        val first = InboxAskHarness(backgroundScope)
        val next = InboxAskHarness(backgroundScope)
        first.conversation = inboxConversation("First", "Saved answer", "done", "first-task")
        next.conversation = inboxConversation("Next", "Next answer", "done", "next-task")
        val source = mutableStateOf(first.ask)
        val policy = mutableStateOf(AiCapabilities.Full)
        val visible = mutableStateOf(true)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(InboxAskNoNodes(), recomposer)
        lateinit var actions: InboxAskActions
        var time = 0L
        suspend fun frame() { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(++time); runCurrent() }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalAiCapabilities provides policy.value) {
                    if (visible.value && policy.value.canReadResults) {
                        val mount = rememberInboxAskMount(source.value)
                        actions = rememberInboxAskActions(source.value, mount)
                    }
                }
            }
            runCurrent(); frame(); frame()
            val original = actions
            assertTrue(original.canAsk)
            original.input("The current draft")
            first.observations.pause()
            assertFalse(original.current)
            assertEquals(1, first.native.observers.size)
            original.input("Unverified input"); original.send()
            assertEquals("The current draft", first.ask.input)
            first.observations.resume(); frame()
            policy.value = AiCapabilities.ReadOnly; frame()
            assertFalse(actions.canAsk)
            assertFalse(original.canAsk)
            original.send(); original.input("Old policy input")
            assertEquals("The current draft", first.ask.input)
            policy.value = AiCapabilities.None; frame()
            assertFalse(original.current)
            assertTrue(first.native.observers.isEmpty())
            assertFalse(original.copy("Old private answer"))
            assertEquals("The current draft", first.ask.input)
            source.value = next.ask; policy.value = AiCapabilities.Full; frame(); frame()
            assertNotSame(original, actions)
            assertTrue(actions.canAsk)
            val replacement = actions
            visible.value = false; frame()
            assertFalse(replacement.current)
            assertTrue(next.native.observers.isEmpty())
            replacement.input("Disposed next input"); replacement.ask("Disposed next question")
            assertFalse(replacement.copy("Disposed next answer"))
            assertEquals(0, next.count("ai-task-start"))
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel()
            first.close(); next.close()
        }
    }
}

private class InboxAskNoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
