package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatsServicesTest {
    @Test
    fun theExistingRpcKeepsEachRangeForImmediateReopen() = runTest {
        val h = Harness()
        assertNull(h.service.cached(7))
        assertEquals(7L, h.service.fetch(7).totals.sessions)
        assertEquals(30L, h.service.fetch(30).totals.sessions)
        assertEquals(7L, h.service.cached(7)?.totals?.sessions)
        val restored = h.services()
        assertEquals(30L, restored.cached(30)?.totals?.sessions)
        assertEquals(listOf(7, 30), h.requests)
        assertTrue(h.preferences.contains("days:7"))
    }

    @Test
    fun offOrRetiredStatsCannotReadPrivateSummariesOrStartRequests() = runTest {
        val h = Harness()
        h.service.fetch(7)
        h.enabled = false
        assertNull(h.service.cached(7))
        assertFailsWith<CancellationException> { h.service.fetch(30) }
        h.enabled = true
        h.active = false
        assertNull(h.service.cached(7))
        assertFailsWith<CancellationException> { h.service.fetch(90) }
        assertEquals(listOf(7), h.requests)
    }

    @Test
    fun aLateReplyCannotPersistAfterAnAccessOrAccountGenerationChange() = runTest {
        val h = Harness()
        val waiting = CompletableDeferred<Unit>()
        h.wait = { withContext(NonCancellable) { waiting.await() } }
        val fetch = async { h.service.fetch(7) }
        runCurrent()
        h.generation++
        waiting.complete(Unit)
        assertFailsWith<CancellationException> { fetch.await() }
        assertNull(h.preferences.getString("days:7", null))
    }

    @Test
    fun optingOutWhileTheResponseIsOutCannotPopulateTheCache() = runTest {
        val h = Harness()
        val waiting = CompletableDeferred<Unit>()
        h.wait = { waiting.await() }
        val fetch = async { h.service.fetch(7) }
        runCurrent()
        h.enabled = false
        waiting.complete(Unit)
        assertFailsWith<CancellationException> { fetch.await() }
        assertNull(h.preferences.getString("days:7", null))
    }

    @Test
    fun aClosedUiOwnerRejectsAReplyEvenIfTheRepoConfigurationIsUnchanged() = runTest {
        val h = Harness()
        val waiting = CompletableDeferred<Unit>()
        h.wait = { waiting.await() }
        val fetch = async { h.service.fetch(7) }
        runCurrent()
        h.active = false
        waiting.complete(Unit)
        assertFailsWith<CancellationException> { fetch.await() }
        assertNull(h.preferences.getString("days:7", null))
    }

    @Test
    fun offlineRefreshKeepsThePreviousRangeForNextOpen() = runTest {
        val h = Harness()
        h.service.fetch(7)
        h.wait = { error("The fixture is offline.") }
        assertFailsWith<IllegalStateException> { h.service.fetch(7) }
        assertEquals(7L, h.service.cached(7)?.totals?.sessions)
        assertEquals(7L, h.services().cached(7)?.totals?.sessions)
    }

    @Test
    fun aCompletedOldRangeCannotPublishIntoTheNewRangeEvenBeforeComposeCancellation() = runTest {
        val h = PendingHarness()
        val old = async { h.page.refresh() }
        runCurrent()
        h.page.select(1)
        val next = async { h.page.refresh() }
        runCurrent()
        h.reply(1, 30)
        next.await()
        h.reply(0, 7)
        old.await()
        assertEquals(1, h.page.range)
        assertEquals(30L, (h.page.load as StatsLoad.Ready).summary.totals.sessions)
        assertEquals(listOf(7, 30), h.days)
    }

    @Test
    fun aRangeAbaCannotReviveTheEarlierRequestsAnswer() = runTest {
        val h = PendingHarness()
        val old = async { h.page.refresh() }
        runCurrent()
        h.page.select(1)
        h.page.select(0)
        val next = async { h.page.refresh() }
        runCurrent()
        h.reply(1, 7001)
        next.await()
        h.reply(0, 7)
        old.await()
        assertEquals(0, h.page.range)
        assertEquals(7001L, (h.page.load as StatsLoad.Ready).summary.totals.sessions)
        assertEquals(7001L, h.service.cached(7)?.totals?.sessions)
    }

    @Test
    fun anOldOrdinaryFailureCannotOverwriteTheSameRangesSuccessfulRetry() = runTest {
        val h = PendingHarness()
        val old = async { h.page.refresh() }
        runCurrent()
        h.page.retry()
        val next = async { h.page.refresh() }
        runCurrent()
        h.reply(1, 7002)
        next.await()
        h.pending[0].complete(RpcResponse(500, """{"error":"The obsolete request failed."}"""))
        old.await()
        assertEquals(7002L, (h.page.load as StatsLoad.Ready).summary.totals.sessions)
    }

    @Test
    fun anInvalidSavedServerShowsTheOriginalLoadFailureWithoutSendingOrReadingPrivateData() = runTest {
        val h = Harness("not a server")
        val page = StatsPageState(h.service, 0)
        assertNull(h.service.cached(7))
        page.refresh()
        assertEquals(StatsLoad.Failed, page.load)
        page.retry()
        page.refresh()
        assertEquals(1, page.attempt)
        assertEquals(StatsLoad.Failed, page.load)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun aScheduledOldProducerCannotAdoptTheNewRangesAuthorityBeforeStarting() = runTest {
        val h = Harness()
        val page = StatsPageState(h.service, 0)
        val scheduled = page.request()
        page.select(1)
        page.refresh(scheduled)
        assertEquals(StatsLoad.Waiting, page.load)
        assertTrue(h.requests.isEmpty())
        page.refresh(page.request())
        assertEquals(30L, (page.load as StatsLoad.Ready).summary.totals.sessions)
    }

    /** Actual Repo and source service; no task is cancelled, so only the publication revision can pass these cases. */
    private class PendingHarness {
        val pending = mutableListOf<CompletableDeferred<RpcResponse>>()
        val days = mutableListOf<Int>()
        val repo = Repo({ RepoConfiguration("https://stats.example") }, RpcTransport { request ->
            days += Json.parseToJsonElement(request.body).jsonObject.getValue("args").jsonObject.getValue("days").jsonPrimitive.content.toInt()
            val answer = CompletableDeferred<RpcResponse>()
            pending += answer
            withContext(NonCancellable) { answer.await() }
        })
        val service = SourceStatsServices(repo, MemorySettingsPreferences(), { true }, { true })
        val page = StatsPageState(service, 0)
        fun reply(index: Int, sessions: Long) { pending[index].complete(RpcResponse(200, """{"result":{"totals":{"events":1,"sessions":$sessions}}}""")) }
    }

    private class Harness(server: String = "https://stats.example") {
        var generation = 0L
        var active = true
        var enabled = true
        var wait: suspend () -> Unit = {}
        val preferences = MemorySettingsPreferences()
        val requests = mutableListOf<Int>()
        val repo = Repo({ RepoConfiguration(server, generation = generation) }, RpcTransport { request ->
            val rpc = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("analytics-summary", rpc.getValue("name").jsonPrimitive.content)
            val days = rpc.getValue("args").jsonObject.getValue("days").jsonPrimitive.content.toInt()
            requests += days
            wait()
            RpcResponse(200, """{"result":{"totals":{"events":1,"sessions":$days}}}""")
        })
        fun services() = SourceStatsServices(repo, preferences, { enabled }, { active })
        val service = services()
    }
}
