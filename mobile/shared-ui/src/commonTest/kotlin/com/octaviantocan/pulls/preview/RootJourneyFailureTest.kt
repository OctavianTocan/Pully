package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.core.AnalyzePreset
import com.octaviantocan.pulls.core.BriefBlock
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.runAnalyze
import com.octaviantocan.pulls.ui.review.NoSessionEffects
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.ReviewSessionCache
import com.octaviantocan.pulls.ui.settings.ModelConfiguration
import com.octaviantocan.pulls.ui.settings.SourceModelSettingsServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RootJourneyFailureTest {
    @Test
    fun aRealRejectedStopPersistsItsSingleFailureAndRetriesAfterProcessStyleReopening() = runTest {
        val h = FailureJourney(RootJourneyScenario.StopRejectedOnce)
        val repo = h.repo()
        val session = h.session(repo, backgroundScope)
        val models = h.models(repo)
        models.fetch(RootJourneyWork.ENGINE).getOrThrow()
        session.load(); runCurrent()
        assertTrue(session.runAnalyze(models, AiCapabilities.Full, AnalyzeChoice.of(AnalyzePreset.Standard)))
        runCurrent()
        val task = assertNotNull(session.taskFor("analyze"))
        val errors = mutableListOf<String>()
        session.cancelTask(task.id, onError = { errors += it }); runCurrent()
        assertEquals(listOf(RootJourneyWork.STOP_ERROR), errors)
        assertTrue(session.tasks.getValue(task.id).running)
        assertEquals("running", repo.tasks(RootJourneyTransport.KEY).single().status)
        assertEquals(1, h.count("ai-task-start"))
        assertEquals(1, h.count("ai-task-cancel"))
        session.detach(); runCurrent()

        // This creates a fresh sample server and real Session, with only the isolated durable store retained.
        val reopenedRepo = h.repo()
        val reopened = h.session(reopenedRepo, backgroundScope)
        reopened.load(); runCurrent()
        assertEquals(task.id, reopened.taskFor("analyze")?.id)
        reopened.cancelTask(task.id, onError = { errors += it }); runCurrent()
        assertEquals(listOf(RootJourneyWork.STOP_ERROR), errors)
        assertEquals(2, h.count("ai-task-cancel"))
        reopened.detach(); runCurrent()
        h.now += 50_000
        val finalRepo = h.repo()
        assertEquals("cancelled", finalRepo.tasks(RootJourneyTransport.KEY).single().status)
        assertNull(finalRepo.savedState(RootJourneyTransport.KEY).tour)
        assertNull(finalRepo.savedState(RootJourneyTransport.KEY).brief)
        assertEquals(1, h.count("ai-task-start"))
        assertEquals(2, h.count("ai-task-cancel"))
    }

    @Test
    fun aFailedAnalysisReopensAsAnErrorAndAFreshActualStandardProducesTheCompleteTypedChain() = runTest {
        val h = FailureJourney(RootJourneyScenario.AnalysisFailedOnce)
        val repo = h.repo()
        val models = h.models(repo)
        models.fetch(RootJourneyWork.ENGINE).getOrThrow()
        val session = h.session(repo, backgroundScope)
        session.load(); runCurrent()
        assertTrue(session.runAnalyze(models, AiCapabilities.Full, AnalyzeChoice.of(AnalyzePreset.Standard)))
        runCurrent()
        session.detach(); runCurrent()
        h.now += 11_000

        val reopenedRepo = h.repo()
        val reopened = h.session(reopenedRepo, backgroundScope)
        reopened.load(); runCurrent()
        assertNull(reopened.loadError)
        val failed = reopened.tasks.values.single()
        assertEquals("analyze", failed.kind)
        assertEquals("failed", failed.status)
        assertEquals(RootJourneyWork.ANALYSIS_ERROR, failed.error)
        assertNull(reopened.tour)
        assertNull(reopened.saved?.brief)
        assertEquals(1, h.count("ai-task-start"))
        assertEquals(0, h.count("ai-task-cancel"))
        val currentModels = h.models(reopenedRepo)
        currentModels.fetch(RootJourneyWork.ENGINE).getOrThrow()
        assertTrue(reopened.runAnalyze(currentModels, AiCapabilities.Full, AnalyzeChoice.of(AnalyzePreset.Standard)))
        runCurrent()
        reopened.detach(); runCurrent()
        h.now += 11_000

        val complete = h.session(h.repo(), backgroundScope)
        complete.load(); runCurrent()
        assertNull(complete.loadError)
        assertEquals(1, complete.tasks.values.count { it.status == "failed" })
        assertEquals(3, complete.tasks.values.count { it.status == "done" })
        assertEquals("Fixture tour from the shared review", complete.tour?.title)
        val brief = assertNotNull(complete.saved?.brief)
        assertTrue(brief.page.blocks.any { it is BriefBlock.Code })
        assertTrue(brief.page.blocks.any { it is BriefBlock.Diagram })
        assertEquals(2, h.count("ai-task-start"))
        assertEquals(0, h.count("ai-task-cancel"))
        complete.detach()
    }

    @Test
    fun failureSelectionIsAnExactAllowlistAndCannotBeChangedForAnExistingFixtureStore() = runTest {
        assertEquals(RootJourneyScenario.Normal, RootJourneyScenario.fromLaunch(null))
        assertEquals(RootJourneyScenario.Normal, RootJourneyScenario.fromLaunch(""))
        for (scenario in RootJourneyScenario.entries) {
            assertEquals(scenario, RootJourneyScenario.fromLaunch(scenario.launchName))
        }
        for (invalid in listOf("../ordinary", "https://ordinary.example", "stop-rejected-once ", "all-errors")) {
            assertFailsWith<IllegalStateException> { RootJourneyScenario.fromLaunch(invalid) }
        }
        val h = FailureJourney(RootJourneyScenario.StopRejectedOnce)
        h.repo().tasks(RootJourneyTransport.KEY) // Persist the selected identity before reopening.
        assertFailsWith<IllegalArgumentException> { RootJourneyTransport.fullJourney(h.disk) { h.now } }
        assertFailsWith<IllegalArgumentException> {
            RootJourneyTransport.fullJourney(h.disk, scenario = RootJourneyScenario.AnalysisFailedOnce) { h.now }
        }
        assertTrue(h.repo().tasks(RootJourneyTransport.KEY).isEmpty())
    }

    @Test
    fun anUnknownStopCannotConsumeTheBoundedFailureAndNormalStateCannotAdoptIt() = runTest {
        val h = FailureJourney(RootJourneyScenario.StopRejectedOnce)
        val transport = RootJourneyTransport.fullJourney(h.disk, scenario = h.scenario) { h.now }
        val invalid = RpcRequest("$PREVIEW_SERVER/api/rpc", mapOf("X-Pully-Session" to RootJourneyTransport.SESSION),
            buildJsonObject {
                put("name", "ai-task-cancel")
                put("args", buildJsonObject { put("id", "ordinary-task") })
            }.toString())
        assertFailsWith<IllegalStateException> { transport.execute(invalid) }
        assertFalse(Json.parseToJsonElement(assertNotNull(h.disk.value)).jsonObject.getValue("failureConsumed").jsonPrimitive.content.toBoolean())
        val normal = FailureJourney(RootJourneyScenario.Normal)
        normal.repo().tasks(RootJourneyTransport.KEY)
        val raw = Json.parseToJsonElement(assertNotNull(normal.disk.value)).jsonObject
        normal.disk.value = JsonObject(raw + ("failureConsumed" to kotlinx.serialization.json.JsonPrimitive(true))).toString()
        assertFails { normal.repo() }
    }
}

private class FailureJourney(val scenario: RootJourneyScenario) {
    val disk = FailureJourneyStore()
    var now = 1_000L
    private val calls = mutableListOf<String>()
    private val settings = AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
        override fun touched(name: String) = Unit
        override fun settingsChanged(key: String, value: String) = Unit
        override fun usageStatsEnabled(enabled: Boolean) = Unit
    }) { "en" }
    fun count(name: String) = calls.count { it == name }
    fun repo(): Repo {
        val transport = RootJourneyTransport.fullJourney(disk, scenario = scenario) { now }
        return Repo({ RepoConfiguration(PREVIEW_SERVER, mapOf("X-Pully-Session" to RootJourneyTransport.SESSION), RootJourneyWork.ENGINE) },
            RpcTransport { request ->
                calls += Json.parseToJsonElement(request.body).jsonObject.getValue("name").jsonPrimitive.content
                transport.execute(request)
            })
    }
    fun session(repo: Repo, scope: CoroutineScope) = ReviewSession(
        OpenTarget.Pull("pully", "mobile-fixtures", 76, "github.com"), repo, scope, settings,
        cache = ReviewSessionCache(scope), effects = NoSessionEffects,
    )
    fun models(repo: Repo) = SourceModelSettingsServices(repo, MemorySettingsPreferences(),
        { ModelConfiguration(RootJourneyWork.ENGINE, "") }, {}, { true })
}

private class FailureJourneyStore(var value: String? = null) : RootJourneyStateStore {
    override fun read() = value
    override fun write(state: String) { value = state }
}
