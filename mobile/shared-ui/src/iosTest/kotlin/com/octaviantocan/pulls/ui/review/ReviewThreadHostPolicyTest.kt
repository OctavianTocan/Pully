package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Uses the production remembered thread writer; no replacement permission model or rendered-font fixture. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewThreadHostPolicyTest {
    @Test
    fun retainedRealThreadWriterReadsTheLatestHostPolicyBeforeAndAfterAnActualRpc() = runTest {
        val h = DiffHarness(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        h.handler = { name, args -> when (name) {
            "review-data" -> buildJsonObject { put("canWrite", true) }
            "review-reply" -> { writes++; gate.await(); JsonObject(emptyMap()) }
            else -> h.default(name, args)
        } }
        h.page.load(); runCurrent(); h.page.reloadReviews(); runCurrent()
        var policy by mutableStateOf(ReviewCapabilities.ReadOnly)
        var actual: ReviewThreadActions? = null
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        var nanos = 0L
        suspend fun frame() { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(++nanos); runCurrent() }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalReviewCapabilities provides policy) { actual = rememberReviewThreadActions(h.page) }
            }
            runCurrent(); frame()
            val retained = assertNotNull(actual)
            retained.run({ pull -> h.page.repo.reply(pull, 11, "A reply") }); runCurrent()
            assertEquals(0, writes)
            policy = ReviewCapabilities.Full; frame()
            assertSame(retained, actual)
            var cleared = 0
            retained.run({ pull -> h.page.repo.reply(pull, 11, "A reply") }) { cleared++ }; runCurrent()
            assertEquals(1, writes)
            policy = ReviewCapabilities.ReadOnly; frame()
            gate.complete(Unit); runCurrent()
            assertEquals(0, cleared)
            assertNull(retained.error)
            assertFalse(retained.canWrite)
            retained.run({ pull -> h.page.repo.deleteComment(pull, 12) }); runCurrent()
            assertEquals(1, writes)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
        }
    }
}
