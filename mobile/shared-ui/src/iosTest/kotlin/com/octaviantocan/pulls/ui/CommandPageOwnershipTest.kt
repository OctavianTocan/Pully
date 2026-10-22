package com.octaviantocan.pulls.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommandPageOwnershipTest {
    @Test
    fun retirementAndRealCompositionDisposalEraseInputAndDisableRetainedRows() = runTest {
        val first = CommandPaletteOwner { true }
        val next = CommandPaletteOwner { true }
        val owner = mutableStateOf(first)
        val visible = mutableStateOf(true)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        lateinit var mounted: CommandPageState
        var runs = 0
        val command = Command("test", "Run") { runs++ }
        try {
            composition.setContent {
                if (visible.value) {
                    mounted = rememberCommandPageState(owner.value)
                    mounted.query
                }
            }
            runCurrent()
            val previous = mounted
            previous.changeQuery("private search")
            previous.expand("Account")
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(1L); runCurrent()
            assertSame(previous, mounted)
            first.close() // The native before-change hook runs before the replacement is published.
            assertEquals("", previous.query)
            assertTrue(previous.expanded.isEmpty())
            previous.run(command) { it.run() }
            assertEquals(0, runs)
            owner.value = next
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(2L); runCurrent()
            assertNotSame(previous, mounted)
            assertEquals("", mounted.query)
            mounted.changeQuery("current search")
            mounted.run(command) { it.run() }
            assertEquals(1, runs)
            val dismissed = mounted
            val oldClearScope = { dismissed.clearScope { Commands.scope = null } }
            visible.value = false
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(3L); runCurrent()
            assertTrue(next.current()) // Dismissing Commands does not retire the account.
            val replacementScope = PaletteScope("repository", "Repository")
            Commands.open(replacementScope)
            oldClearScope()
            assertSame(replacementScope, Commands.scope)
            assertEquals("", dismissed.query)
            dismissed.changeQuery("late search")
            dismissed.expand("Late")
            dismissed.run(command) { it.run() }
            assertEquals("", dismissed.query)
            assertFalse("Late" in dismissed.expanded)
            assertEquals(1, runs)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel()
            first.close(); next.close()
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
