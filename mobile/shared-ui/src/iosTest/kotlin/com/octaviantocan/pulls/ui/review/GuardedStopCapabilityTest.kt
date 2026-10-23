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
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Uses the actual Native Compose runtime and the same stop handler as the review surfaces. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardedStopCapabilityTest {
    @Test
    fun readOnlyAndRevokedControlsCannotIssueTheActualCancelRpc() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val cancellations = mutableListOf<String>()
        h.handler = { name, args ->
            if (name == "ai-task-cancel") cancellations += args.getValue("id").jsonPrimitive.content
            h.default(name, args)
        }
        var capabilities by mutableStateOf(AiCapabilities.ReadOnly)
        var ready = true
        val readiness = ReviewActionReadiness({ ready })
        var stop: (() -> Unit)? = null
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        suspend fun frame(nanos: Long) {
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(nanos); runCurrent()
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalAiCapabilities provides capabilities, LocalReviewActionReadiness provides readiness) {
                    stop = guardedStop(h.page, listOf("critique"), running = capabilities.canCritique) {
                        h.page.cancelTask("running-server-critique")
                    }
                }
            }
            runCurrent(); frame(1L)
            assertNull(stop)
            assertEquals(emptyList(), cancellations)

            capabilities = AiCapabilities.Full
            frame(2L)
            val formerControl = assertNotNull(stop)
            ready = false // No composition frame: a retained orb must see the live host gate.
            formerControl(); runCurrent()
            assertEquals(emptyList(), cancellations)
            ready = true
            formerControl(); runCurrent()
            assertEquals(listOf("running-server-critique"), cancellations)

            capabilities = AiCapabilities.ReadOnly
            frame(3L)
            assertNull(stop)
            formerControl(); runCurrent()
            assertEquals(listOf("running-server-critique"), cancellations)
            capabilities = AiCapabilities.Full; frame(4L)
            val dismissed = assertNotNull(stop)
            composition.dispose()
            dismissed(); runCurrent()
            assertEquals(listOf("running-server-critique"), cancellations)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
        }
    }
}
