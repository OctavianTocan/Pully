package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.ui.review.DiffHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BriefRewriteOwnershipTest {
    @Test
    fun theProductionRememberHelperReadsTheCurrentPolicyAndRetiresOnReaderRemoval() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val brief = installBriefFixture(h)
        val policy = mutableStateOf(AiCapabilities.Full)
        val visible = mutableStateOf(true)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(BriefNoNodes(), recomposer)
        lateinit var actions: BriefRewriteActions
        suspend fun frame(time: Long) { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(time); runCurrent() }
        try {
            composition.setContent {
                if (visible.value) CompositionLocalProvider(LocalAiCapabilities provides policy.value) {
                    actions = rememberBriefRewriteActions(h.page, brief)
                }
            }
            runCurrent()
            val retained = actions
            assertTrue(actions.canRewrite)
            policy.value = AiCapabilities.ReadOnly; frame(1L)
            assertSame(retained, actions)
            assertFalse(retained.canRewrite)
            policy.value = AiCapabilities.Full; frame(2L)
            assertTrue(retained.canRewrite)
            visible.value = false; frame(3L)
            assertFalse(retained.canRewrite)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
        }
    }
}

private class BriefNoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
