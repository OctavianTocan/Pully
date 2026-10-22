package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A parent result guard removes the real helper, so retained callbacks cannot retain the prior Full permission. */
@OptIn(ExperimentalCoroutinesApi::class)
class FindingFocusLifetimeTest {
    @Test
    fun removingTheActualFindingForUnreadableResultsRetiresItsRetainedClick() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var policy by mutableStateOf(AiCapabilities.Full)
        var actual: FindingFocusAction? = null
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        var nanos = 0L
        suspend fun frame() { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(++nanos); runCurrent() }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalAiCapabilities provides policy) {
                    if (policy.canReadResults) actual = rememberFindingFocusAction(h.page, focusFixture())
                }
            }
            runCurrent(); frame()
            val retained = assertNotNull(actual)
            retained.open(); assertNotNull(h.page.focus)
            h.page.focus = null
            policy = AiCapabilities.None; frame()
            retained.open(); assertNull(h.page.focus)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
        }
    }
}
