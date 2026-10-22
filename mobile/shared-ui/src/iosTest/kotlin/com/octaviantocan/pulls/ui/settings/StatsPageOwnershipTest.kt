package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.data.AnalyticsSummary
import com.octaviantocan.pulls.data.asAnalyticsSummary
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/** The actual remembered producer/range state runs on Compose Native, without Android framework stubs. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatsPageOwnershipTest {
    @Test
    fun anAbaBeforeTheNextFrameStillLaunchesTheReplacementRangeProducer() = runTest {
        val services = Services(11)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        lateinit var page: StatsPageState
        try {
            composition.setContent { StatsPageOwner(services) { page = rememberStatsPageState(services) } }
            runCurrent()
            assertEquals(listOf(7), services.requests)
            page.select(1)
            page.select(0)
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(1L); runCurrent()
            assertEquals(0, page.range)
            assertEquals(listOf(7, 7), services.requests)
            assertEquals(11L, (page.load as StatsLoad.Ready).summary.totals.sessions)
        } finally { composition.dispose(); recomposer.close(); runner.cancel() }
    }

    @Test
    fun replacingTheOwnerDropsTheOldRangeAndPrivateSummary() = runTest {
        val first = Services(11)
        val replacement = Services(22)
        val owner = mutableStateOf<StatsServices>(first)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        lateinit var mounted: StatsPageState
        try {
            composition.setContent {
                StatsPageOwner(owner.value) {
                    mounted = rememberStatsPageState(owner.value)
                    mounted.load
                }
            }
            runCurrent()
            mounted.select(2)
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(1L); runCurrent()
            assertEquals(2, mounted.range)
            val previous = mounted
            first.active = false
            owner.value = replacement
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(2L); runCurrent()
            assertNotSame(previous, mounted)
            assertEquals(0, mounted.range)
            assertEquals(22L, (mounted.load as StatsLoad.Ready).summary.totals.sessions)
            previous.select(1); previous.retry()
            assertEquals(0, mounted.range)
            assertEquals(0, mounted.attempt)
            assertEquals(listOf(7), replacement.requests)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel()
        }
    }

    private class Services(count: Int) : StatsServices {
        var active = true
        val requests = mutableListOf<Int>()
        private val summary = Json.parseToJsonElement("""{"totals":{"events":1,"sessions":$count}}""").asAnalyticsSummary()
        override fun current() = active
        override fun cached(days: Int): AnalyticsSummary = summary
        override suspend fun fetch(days: Int): AnalyticsSummary { requests += days; return summary }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
