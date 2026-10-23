package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.CritiqueActions
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.ai.installCritique
import com.octaviantocan.pulls.ui.ai.rememberCritiqueActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewWriteOwnershipTest {
    @Test
    fun theProductionRememberHelpersFollowCurrentPoliciesAndRetireOnRealReaderDisposal() = runTest {
        val first = DiffHarness(backgroundScope)
        val next = DiffHarness(backgroundScope)
        for (h in listOf(first, next)) {
            h.handler = { name, args -> if (name == "review-data") buildJsonObject { put("canWrite", true) } else h.default(name, args) }
            h.page.load()
        }
        runCurrent()
        installCritique(first); installCritique(next)
        val session = mutableStateOf(first.page)
        val review = mutableStateOf(ReviewCapabilities.Full)
        val ai = mutableStateOf(AiCapabilities.Full)
        val visible = mutableStateOf(true)
        val top = ReviewTopUi { _, _ -> }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(WriteNoNodes(), recomposer)
        lateinit var mutation: ReviewMutationActions
        lateinit var critique: CritiqueActions
        lateinit var commands: ReviewCommandActions
        suspend fun frame(time: Long) { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(time); runCurrent() }
        try {
            composition.setContent {
                if (visible.value) CompositionLocalProvider(LocalReviewCapabilities provides review.value, LocalAiCapabilities provides ai.value) {
                    mutation = rememberReviewMutationActions(session.value, top)
                    critique = rememberCritiqueActions(session.value)
                    commands = rememberReviewCommandActions(session.value)
                }
            }
            runCurrent()
            val original = mutation
            assertTrue(mutation.canSubmit)
            assertTrue(critique.canPublish)
            assertTrue(commands.canDiff)
            review.value = ReviewCapabilities.None; ai.value = AiCapabilities.None; frame(1L)
            assertSame(original, mutation)
            assertFalse(mutation.canSubmit)
            assertFalse(critique.canPublish)
            assertFalse(commands.canDiff)
            review.value = ReviewCapabilities.Full; ai.value = AiCapabilities.Full; session.value = next.page; frame(2L)
            assertNotSame(original, mutation)
            assertFalse(original.current) // The old session is still alive: actual composition disposal revokes it.
            assertTrue(mutation.canSubmit)
            val dismissedMutation = mutation
            val dismissedCritique = critique
            val dismissedCommands = commands
            visible.value = false; frame(3L)
            assertFalse(dismissedMutation.current)
            assertFalse(dismissedCritique.current)
            assertFalse(dismissedCommands.current)
            assertFalse(dismissedMutation.submit("COMMENT", "Late input", null) {})
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel()
            first.page.detach(); next.page.detach()
        }
    }
}

private class WriteNoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
