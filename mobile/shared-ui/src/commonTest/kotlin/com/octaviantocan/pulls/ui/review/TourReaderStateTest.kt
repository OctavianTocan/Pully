package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.data.asTour
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TourReaderStateTest {
    @Test
    fun reopeningTheSameSourceKeepsFoldsButChangingIdentityDiscardsThem() = runTest {
        val h = DiffHarness(backgroundScope)
        val original = h.page.tourFolds("generation-a")
        original.foldAll(3)
        h.page.detach()
        fun reopen() = ReviewSession(h.page.target, h.page.repo, backgroundScope, h.page.settings,
            cache = h.cache, effects = NoSessionEffects)
        val reopened = reopen()
        assertSame(original, reopened.tourFolds("generation-a"))
        assertTrue(reopened.tourFolds("generation-a").folded(2))
        assertFalse(reopened.tourFolds("generation-b").folded(2))
        h.generation++
        val replacement = reopen()
        assertFalse(replacement.tourFolds("generation-a").folded(2))
        assertNotSame(original, replacement.tourFolds("generation-a"))
        original.foldAll(4)
        assertFalse(replacement.tourFolds("generation-a").folded(3))
        reopened.detach(); replacement.detach()
    }

    @Test
    fun nativeFoldRetentionEvictsTheLeastRecentlyReadTourWithoutChangingAndroidPolicy() {
        val store = TourFoldStore(limit = 2)
        val first = store.of("one").also { it.toggle(0) }
        store.of("two").toggle(1)
        assertSame(first, store.of("one"))
        store.of("three")
        assertFalse(store.of("two").folded(1))
        val android = TourFoldStore(limit = null)
        android.of("first").toggle(0)
        repeat(300) { android.of("tour:$it") }
        assertTrue(android.of("first").folded(0))
    }

    @Test
    fun retainedActualScreenActionsRejectRevokedCapabilitiesAndHeadAba() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var policy = AiCapabilities.Full
        val owner = assertNotNull(h.page.captureTourRead())
        assertSame(owner, h.page.captureTourRead())
        val actions = TourReaderActions(h.page, owner) { policy }
        var reads = 0; var asks = 0; var writes = 0
        actions.read { reads++ }; actions.ask { asks++ }; actions.regenerate { writes++ }
        policy = AiCapabilities.ReadOnly
        actions.read { reads++ }; actions.ask { asks++ }; actions.regenerate { writes++ }
        assertEquals(listOf(2, 1, 1), listOf(reads, asks, writes))
        policy = AiCapabilities.Full
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        actions.read { reads++ }; actions.ask { asks++ }; actions.regenerate { writes++ }
        assertEquals(listOf(2, 1, 1), listOf(reads, asks, writes))
        val current = TourReaderActions(h.page, h.page.captureTourRead()) { policy }
        current.regenerate { writes++ }
        assertEquals(2, writes)
        h.cache.clear()
        current.read { reads++ }; current.regenerate { writes++ }
        assertEquals(listOf(2, 1, 2), listOf(reads, asks, writes))
    }

    @Test
    fun cachedTourOpensBeforeTheDiffAndEqualServerRehydrationKeepsItsNewOwnerAndFolds() = runTest {
        val h = DiffHarness(backgroundScope)
        val wire = Json.parseToJsonElement("""{"title":"Kept tour","overview":"A saved explanation","locale":"en","generatedAt":"kept-tour","headSha":"head-a","stops":[],"glossary":[]}""").jsonObject
        val keptTour = wire.asTour()
        val page = ReviewSession(h.page.target, h.page.repo, backgroundScope, h.page.settings,
            tourStore = object : SessionTourCache {
                override fun get(pull: String): Tour = keptTour
                override suspend fun put(pull: String, tour: JsonObject) {}
            }, cache = h.cache, effects = NoSessionEffects)
        assertSame(keptTour, page.tour)
        val beforeOpening = assertNotNull(page.captureTourRead())
        val folds = page.tourFolds("kept-tour").also { it.toggle(2) }
        assertTrue(page.isCurrentTourRead(beforeOpening))
        page.load(); runCurrent()
        assertFalse(page.isCurrentTourRead(beforeOpening))
        val afterOpening = assertNotNull(page.captureTourRead())
        assertNotSame(beforeOpening, afterOpening)
        assertSame(folds, page.tourFolds("kept-tour"))
        h.handler = { name, args -> if (name == "review-state") buildJsonObject { put("tour", wire) } else h.default(name, args) }
        assertTrue(page.reloadSavedNow())
        assertEquals(keptTour, page.tour)
        assertNotSame(keptTour, page.tour)
        assertSame(afterOpening, page.captureTourRead())
        h.handler = { name, args -> if (name == "review-state") buildJsonObject {
            put("tour", buildJsonObject { wire.forEach { (key, value) -> put(key, value) }; put("title", "An updated tour") })
        } else h.default(name, args) }
        assertTrue(page.reloadSavedNow())
        assertFalse(page.isCurrentTourRead(afterOpening))
        assertTrue(folds.folded(2))
        page.detach(); h.page.detach()
    }

    @Test
    fun retiringReaderCommandsCancelsTheirOwnScrollWithoutCancellingIndependentResumeWork() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val actions = TourReaderActions(h.page, h.page.captureTourRead()) { AiCapabilities.Full }
        var scrolling = false
        var resumeAlive = true
        val resume = backgroundScope.launch {
            try { awaitCancellation() } finally { resumeAlive = false }
        }
        actions.scroll(backgroundScope) {
            scrolling = true
            try { awaitCancellation() } finally { scrolling = false }
        }
        runCurrent()
        assertTrue(scrolling)
        actions.close(); runCurrent()
        assertFalse(actions.current)
        actions.read { error("A disposed Tour command must be inert even in the same review") }
        assertFalse(scrolling)
        assertTrue(resumeAlive)
        h.head = "head-b"; h.page.refresh(); runCurrent()
        actions.scroll(backgroundScope) { error("A retired command must not start another scroll") }
        runCurrent()
        resume.cancel(); h.page.detach()
    }

    @Test
    fun readingTelemetryKeepsOnlyStopNumbersAndDoesNotReportSkippedAfterFinishing() {
        val events = mutableListOf<Triple<String, Int, Int?>>()
        val log = TourReading { step, index, total -> events += Triple(step, index, total) }
        log.at(1, 3); log.at(1, 3); log.at(2, 3); log.at(1, 3); log.at(3, 3); log.leave()
        assertEquals(listOf<Triple<String, Int, Int?>>(Triple("started", 1, 3), Triple("next", 2, 3), Triple("next", 3, 3), Triple("finished", 3, 3)), events)
        val abandoned = TourReading { step, index, total -> events += Triple(step, index, total) }
        abandoned.at(2, 4); abandoned.leave()
        assertEquals<Triple<String, Int, Int?>>(Triple("skipped", 2, null), events.last())
    }
}
