package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.core.AnalyzePreset
import com.octaviantocan.pulls.core.BriefBlock
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.*
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.critiqueVariant
import com.octaviantocan.pulls.ui.ai.runAnalyze
import com.octaviantocan.pulls.ui.review.NoSessionEffects
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.ReviewSessionCache
import com.octaviantocan.pulls.ui.settings.ModelConfiguration
import com.octaviantocan.pulls.ui.settings.SourceModelSettingsServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RootJourneyWorkTest {
    @Test
    fun fullReadinessRequiresTheExplicitFactoryAndDefaultStillRejectsWork() = runTest {
        assertFalse(RootJourneyTransport().fullJourney)
        val readTransport = RootJourneyTransport()
        val read = sampleRepo(readTransport)
        assertFalse(read.reviewData(PULL).canWrite)
        assertSampleRejected<IllegalStateException>(readTransport) { it.startTask("analyze", RootJourneyTransport.KEY) }
        val full = RootJourneyTransport.fullJourney(SampleJourneyStore()) { 1_000 }
        assertTrue(full.fullJourney)
        assertTrue(sampleRepo(full).reviewData(PULL).canWrite)
    }

    @Test
    fun theNativeFixtureIdentifierIsCanonicalAndCannotBecomeAnArbitraryStoragePath() {
        val id = "56362FEF-614A-44F2-9EF1-761DFFCC7939"
        assertEquals(id.lowercase(), RootJourneyTransport.readOnly(id).fixtureIdentifier)
        assertEquals(id.lowercase(), RootJourneyTransport.fullJourney(SampleJourneyStore(), id).fixtureIdentifier)
        for (invalid in listOf("", "../ordinary", "https://ordinary.example", "56362FEF-614A-44F2-9EF1-761DFFCC7939/other")) {
            assertFailsWith<IllegalArgumentException> { RootJourneyTransport.readOnly(invalid) }
            assertFailsWith<IllegalArgumentException> { RootJourneyTransport.fullJourney(SampleJourneyStore(), invalid) }
        }
    }

    @Test
    fun selectedSampleCommentAndSubmitUseTheRealHeadAndExpectedPendingReview() = runTest {
        val transport = RootJourneyTransport.fullJourney(SampleJourneyStore()) { 1_000 }
        val repo = sampleRepo(transport)
        repo.comment(PULL, RootJourneyWork.FILE, "additions", 1, RootJourneyWork.HEAD, RootJourneyWork.COMMENT, "review")
        val pending = repo.reviewData(PULL)
        assertEquals(7601L, pending.pendingReviewId)
        assertEquals(1, pending.pendingCount)
        assertEquals(RootJourneyWork.COMMENT, pending.threads.single().comments.single().body)
        assertSampleRejected<IllegalArgumentException>(transport) {
            it.submitReview(PULL, "COMMENT", RootJourneyWork.SUMMARY, RootJourneyWork.HEAD, ExpectedPendingReview(null))
        }
        assertEquals(1, repo.reviewData(PULL).pendingCount)
        repo.submitReview(PULL, "COMMENT", RootJourneyWork.SUMMARY, RootJourneyWork.HEAD, ExpectedPendingReview(7601))
        val submitted = repo.reviewData(PULL)
        assertNull(submitted.pendingReviewId)
        assertEquals(0, submitted.pendingCount)
        assertEquals(RootJourneyWork.SUMMARY, submitted.summaries.single().body)
        assertEquals("commented", submitted.summaries.single().state)
    }

    @Test
    fun actualStandardAndDeepSetupSurviveADeadClientWithoutReplayingStarts() = runTest {
        for (preset in listOf(AnalyzePreset.Standard, AnalyzePreset.Deep)) {
            val disk = SampleJourneyStore()
            var now = 1_000L
            val sent = mutableListOf<Pair<String, JsonObject>>()
            val transport = RootJourneyTransport.fullJourney(disk) { now }
            val settings = sampleSettings()
            val repo = sampleRepo(RpcTransport { request ->
                val call = Json.parseToJsonElement(request.body).jsonObject
                sent += call.getValue("name").jsonPrimitive.content to call.getValue("args").jsonObject
                transport.execute(request)
            }, { settings.effort })
            val models = SourceModelSettingsServices(repo, MemorySettingsPreferences(), { ModelConfiguration(RootJourneyWork.ENGINE, "") }, {}, { true })
            models.fetch(RootJourneyWork.ENGINE).getOrThrow()
            val session = ReviewSession(PULL, repo, backgroundScope, settings, cache = ReviewSessionCache(backgroundScope), effects = NoSessionEffects)
            session.load(); runCurrent()
            assertTrue(session.runAnalyze(models, AiCapabilities.Full, AnalyzeChoice.of(preset)))
            runCurrent()
            val starts = sent.filter { it.first == "ai-task-start" }.map { it.second }
            assertEquals(if (preset == AnalyzePreset.Deep) 2 else 1, starts.size)
            val analysis = starts.single { it["kind"]?.jsonPrimitive?.content == "analyze" }
            assertEquals("en", analysis["locale"]?.jsonPrimitive?.content)
            assertEquals(listOf("tour", "brief"), (analysis["then"] as JsonArray).map { it.jsonPrimitive.content })
            val expectedEffort = if (preset == AnalyzePreset.Deep) "high" else settings.effort.ifBlank { null }
            assertEquals(expectedEffort, analysis.getValue("model").jsonObject["effort"]?.jsonPrimitive?.content)
            if (preset == AnalyzePreset.Deep) {
                val critique = starts.single { it["kind"]?.jsonPrimitive?.content == "critique" }
                assertEquals("high", critique.getValue("model").jsonObject["effort"]?.jsonPrimitive?.content)
                assertEquals(critiqueVariant(RootJourneyWork.HEAD, RootJourneyWork.ENGINE, "", "high", null), critique["variant"]?.jsonPrimitive?.content)
            }
            assertTrue(sent.any { it.first == "ai-task-wait" })
            session.detach(); runCurrent()
            assertFalse(sent.any { it.first == "ai-task-cancel" })
            now += RootJourneyWork.COMPLETION_MILLIS // The dead client cannot poll or execute a follow-up pass.
            val reopenedCalls = mutableListOf<String>()
            val restoredTransport = RootJourneyTransport.fullJourney(disk) { now }
            val reopenedRepo = sampleRepo(RpcTransport { request ->
                reopenedCalls += Json.parseToJsonElement(request.body).jsonObject.getValue("name").jsonPrimitive.content
                restoredTransport.execute(request)
            })
            val reopened = ReviewSession(PULL, reopenedRepo, backgroundScope, settings, cache = ReviewSessionCache(backgroundScope), effects = NoSessionEffects)
            reopened.load(); runCurrent()
            assertNull(reopened.loadError)
            assertEquals("Fixture tour from the shared review", reopened.tour?.title)
            assertTrue(reopened.saved!!.brief!!.page.blocks.any { it is BriefBlock.Code })
            assertTrue(reopened.saved!!.brief!!.page.blocks.any { it is BriefBlock.Diagram })
            assertEquals(if (preset == AnalyzePreset.Deep) 4 else 3, reopened.tasks.size)
            assertTrue(reopened.tasks.values.all { it.status == "done" })
            assertFalse(reopenedCalls.any { it in setOf("ai-task-start", "ai-task-cancel", "ai-task-wait") })
            if (preset == AnalyzePreset.Deep) assertNotNull(reopened.saved?.critique?.variant)
            reopened.detach()
        }
    }

    @Test
    fun unchangedSampleWaitActuallySuspendsAndCancellationNeverCancelsServerWork() = runTest {
        var now = 1_000L
        val sent = mutableListOf<String>()
        val transport = RootJourneyTransport.fullJourney(SampleJourneyStore()) { now }
        val repo = sampleRepo(RpcTransport { request ->
            sent += Json.parseToJsonElement(request.body).jsonObject.getValue("name").jsonPrimitive.content
            transport.execute(request)
        })
        val id = repo.startTask("analyze", RootJourneyTransport.KEY) { put("locale", "en"); put("then", JsonArray(listOf(JsonPrimitive("tour"), JsonPrimitive("brief")))) }
        assertEquals(1, repo.waitTask(id, 0)["rev"]?.jsonPrimitive?.intOrNull)
        var returned = false
        var cancelled = false
        val wait = launch {
            try { repo.waitTask(id, 1); returned = true } catch (failure: CancellationException) { cancelled = true; throw failure }
        }
        runCurrent()
        assertFalse(returned)
        wait.cancel(); runCurrent()
        assertTrue(cancelled)
        assertFalse(sent.contains("ai-task-cancel"))
        now += RootJourneyWork.COMPLETION_MILLIS
        assertTrue(repo.tasks(RootJourneyTransport.KEY).all { it.status == "done" })
        assertNotNull(repo.savedState(RootJourneyTransport.KEY).brief)
        assertEquals(1, sent.count { it == "ai-task-start" })
    }

    @Test
    fun explicitStopSurvivesReopenWithoutStartingTheAutomaticPassesAndANewAnalysisCanFinish() = runTest {
        val disk = SampleJourneyStore()
        var now = 1_000L
        val calls = mutableListOf<String>()
        fun trackedRepo(transport: RpcTransport) = sampleRepo(RpcTransport { request ->
            calls += Json.parseToJsonElement(request.body).jsonObject.getValue("name").jsonPrimitive.content
            transport.execute(request)
        })
        fun models(repo: Repo) = SourceModelSettingsServices(repo, MemorySettingsPreferences(),
            { ModelConfiguration(RootJourneyWork.ENGINE, "") }, {}, { true })
        val settings = sampleSettings()
        val repo = trackedRepo(RootJourneyTransport.fullJourney(disk) { now })
        val initialModels = models(repo)
        initialModels.fetch(RootJourneyWork.ENGINE).getOrThrow()
        val session = ReviewSession(PULL, repo, backgroundScope, settings, cache = ReviewSessionCache(backgroundScope), effects = NoSessionEffects)
        session.load(); runCurrent()
        assertTrue(session.runAnalyze(initialModels, AiCapabilities.Full, AnalyzeChoice.of(AnalyzePreset.Standard)))
        runCurrent()
        val stopped = assertNotNull(session.taskFor("analyze")).id
        session.cancelTask(stopped); runCurrent()
        assertEquals(1, calls.count { it == "ai-task-cancel" })
        session.detach(); runCurrent()
        now += RootJourneyWork.COMPLETION_MILLIS

        val reopenedRepo = trackedRepo(RootJourneyTransport.fullJourney(disk) { now })
        val reopened = ReviewSession(PULL, reopenedRepo, backgroundScope, settings, cache = ReviewSessionCache(backgroundScope), effects = NoSessionEffects)
        reopened.load(); runCurrent()
        assertNull(reopened.loadError)
        assertEquals("cancelled", reopened.tasks.getValue(stopped).status)
        assertEquals(1, reopened.tasks.size)
        assertNull(reopened.tour)
        assertNull(reopened.saved?.brief)
        assertEquals(1, calls.count { it == "ai-task-start" })
        assertEquals(1, calls.count { it == "ai-task-cancel" })

        val currentModels = models(reopenedRepo)
        currentModels.fetch(RootJourneyWork.ENGINE).getOrThrow()
        assertTrue(reopened.runAnalyze(currentModels, AiCapabilities.Full, AnalyzeChoice.of(AnalyzePreset.Standard)))
        runCurrent()
        assertNotEquals(stopped, reopened.taskFor("analyze")?.id)
        reopened.detach(); runCurrent()
        now += RootJourneyWork.COMPLETION_MILLIS
        val finished = reopenedRepo.tasks(RootJourneyTransport.KEY)
        assertEquals("cancelled", finished.single { it.id == stopped }.status)
        assertEquals(3, finished.count { it.status == "done" })
        assertEquals("Fixture tour from the shared review", reopenedRepo.savedState(RootJourneyTransport.KEY).tour?.title)
        assertNotNull(reopenedRepo.savedState(RootJourneyTransport.KEY).brief)
        assertEquals(2, calls.count { it == "ai-task-start" })
        assertEquals(1, calls.count { it == "ai-task-cancel" })
    }

    @Test
    fun chatAndEarlierHistoryUseTheActualClearRestoreAndSavedResultRpcsAcrossReopen() = runTest {
        val disk = SampleJourneyStore()
        var now = 1_000L
        val repo = sampleRepo(RootJourneyTransport.fullJourney(disk) { now })
        repo.startTask("chat", RootJourneyTransport.KEY) { put("locale", "en"); put("text", RootJourneyWork.QUESTION) }
        now += 1_000
        assertEquals(RootJourneyWork.ANSWER, repo.savedState(RootJourneyTransport.KEY).chat.last().text)
        assertEquals(listOf("fixture-history-seed"), repo.chatHistory(RootJourneyTransport.KEY).map { it.id })
        repo.clearChat(RootJourneyTransport.KEY)
        assertTrue(repo.savedState(RootJourneyTransport.KEY).chat.isEmpty())
        assertEquals(2, repo.chatHistory(RootJourneyTransport.KEY).size)
        repo.restoreChat(RootJourneyTransport.KEY, "fixture-history-seed")
        assertEquals(RootJourneyWork.SEED_ANSWER, repo.savedState(RootJourneyTransport.KEY).chat.last().text)
        val restoredTransport = RootJourneyTransport.fullJourney(disk) { now }
        val restored = sampleRepo(restoredTransport)
        assertEquals(RootJourneyWork.SEED_QUESTION, restored.savedState(RootJourneyTransport.KEY).chat.first().text)
        assertEquals(listOf("fixture-history-current"), restored.chatHistory(RootJourneyTransport.KEY).map { it.id })
        assertSampleRejected<IllegalArgumentException>(restoredTransport) { it.restoreChat(RootJourneyTransport.KEY, "ordinary-history") }
    }

    @Test
    fun theFullSampleRejectsUnknownModelsArgumentsTargetsAndArbitraryContent() = runTest {
        val store = SampleJourneyStore()
        val transport = RootJourneyTransport.fullJourney(store) { 1_000 }
        val repo = sampleRepo(transport)
        assertSampleRejected<IllegalArgumentException>(transport) { it.comment(PULL, RootJourneyWork.FILE, "additions", 1, RootJourneyWork.HEAD, "Ordinary private text", "review") }
        assertSampleRejected<IllegalArgumentException>(transport) { it.comment(PULL, "other.kt", "additions", 1, RootJourneyWork.HEAD, RootJourneyWork.COMMENT, "review") }
        assertSampleRejected<IllegalArgumentException>(transport) {
            it.startTask("analyze", "another-key") { put("locale", "en"); put("then", JsonArray(listOf(JsonPrimitive("tour"), JsonPrimitive("brief")))) }
        }
        assertSampleRejected<IllegalArgumentException>(transport) { rejected ->
            rejected.startTask("analyze", RootJourneyTransport.KEY) { put("locale", "en"); put("then", JsonArray(listOf(JsonPrimitive("tour"), JsonPrimitive("brief")))); put("endpoint", "https://ordinary.example") }
        }
        assertSampleRejected<IllegalArgumentException>(transport, model = "an-ordinary-model") {
            it.startTask("analyze", RootJourneyTransport.KEY) { put("locale", "en"); put("then", JsonArray(listOf(JsonPrimitive("tour"), JsonPrimitive("brief")))) }
        }
        assertSampleRejected<IllegalArgumentException>(transport) { it.call("review-state") { put("key", RootJourneyTransport.KEY); put("unknown", true) } }
        assertSampleRejected<IllegalStateException>(transport) { it.call("pr-action") }
        assertSampleRejected<IllegalArgumentException>(transport) { it.open(OpenTarget.Pull("pully", "mobile-fixtures", 75, "github.com")) }
        assertFalse(store.value.orEmpty().contains("Ordinary private text"))
        assertTrue(repo.tasks(RootJourneyTransport.KEY).isEmpty())
    }

    @Test
    fun invalidPersistedSampleStateCannotSilentlyBecomeANewOrOrdinaryJourney() {
        for (raw in listOf("{}", """{"version":2}""", """{"version":1,"unexpected":"ordinary"}""", "x".repeat(32_001),
            """{"version":1,"nextId":1,"clientStarts":1,"tasks":[{"id":"ordinary-task","kind":"analyze","startedAt":1,"dueAt":8001}]}""")) {
            assertFails { RootJourneyTransport.fullJourney(SampleJourneyStore(raw)) { 1_000 } }
        }
    }
}

private val PULL = OpenTarget.Pull("pully", "mobile-fixtures", 76, "github.com")
private class SampleJourneyStore(var value: String? = null) : RootJourneyStateStore {
    override fun read() = value
    override fun write(state: String) { value = state }
}

private fun sampleRepo(transport: RpcTransport, effort: () -> String = { "" }, model: String = "") = Repo({
    RepoConfiguration(PREVIEW_SERVER, mapOf("X-Pully-Session" to RootJourneyTransport.SESSION), RootJourneyWork.ENGINE, model, effort())
}, transport)

/** Exercise the real builder and its privacy wrapper, then the same raw request's exact fixture guard. */
private suspend inline fun <reified Failure : Throwable> assertSampleRejected(
    transport: RpcTransport,
    model: String = "",
    noinline action: suspend (Repo) -> Unit,
) {
    var request: RpcRequest? = null
    val repo = sampleRepo(RpcTransport { actual -> request = actual; transport.execute(actual) }, model = model)
    assertFailsWith<RpcConnectionException> { action(repo) }
    assertFailsWith<Failure> { transport.execute(assertNotNull(request)) }
}

private fun sampleSettings() = AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
    override fun touched(name: String) = Unit
    override fun settingsChanged(key: String, value: String) = Unit
    override fun usageStatsEnabled(enabled: Boolean) = Unit
}) { "en" }
