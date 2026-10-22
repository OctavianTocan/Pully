package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.core.AnalyzePreset
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.ui.review.NoSessionEffects
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.ReviewSessionCache
import com.octaviantocan.pulls.ui.review.ReviewActionReadiness
import com.octaviantocan.pulls.ui.settings.ModelConfiguration
import com.octaviantocan.pulls.ui.settings.SourceModelSettingsServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiSetupActionsTest {
    @Test
    fun theRetainedReviewButtonUsesTheLatestLocalOptInInTheActualRequestAndStillHonorsReadiness() = runTest {
        for (optedIn in listOf(false, true)) {
            val h = Harness(backgroundScope)
            h.session.load(); runCurrent()
            var ready = true
            val actions = AiSetupActions(h.session, h.models, h.session.captureView(), ReviewActionReadiness({ ready })) { AiCapabilities.Full }
            // The control was already drawn before this local choice changed.
            h.session.settings.experiments.updateMergeRisk(true)
            h.session.settings.experiments.updateMergeRisk(optedIn)
            assertTrue(actions.critique("security"))
            runCurrent()
            val request = h.starts.single()
            val ordinaryVariant = "head-a:claude-code:::security"
            assertEquals(ordinaryVariant + if (optedIn) ":experimental-merge-risk" else "", request.getValue("variant").jsonPrimitive.content)
            assertEquals("security", request.getValue("lens").jsonPrimitive.content)
            if (optedIn) assertEquals(JsonPrimitive(true), request.getValue("experiments").jsonObject["mergeRisk"])
            else assertFalse("experiments" in request)

            ready = false
            h.session.settings.experiments.updateMergeRisk(!optedIn)
            assertFalse(actions.critique(null))
            runCurrent()
            assertEquals(1, h.starts.size)
            h.session.detach()
        }
    }

    @Test
    fun presetLaunchesAreUnavailableBeforeLoadThenStartOnceThroughTheCurrentButton() = runTest {
        val h = Harness(backgroundScope)
        val quick = AnalyzeChoice.of(AnalyzePreset.Quick)
        val unloaded = AiSetupActions(h.session, h.models, h.session.captureView()) { AiCapabilities.Full }
        assertTrue(unloaded.canAnalyze(quick)) // Model and custom setup remain available.
        assertFalse(unloaded.canStartAnalysis(quick))
        assertEquals(AnalysisStart.Unavailable, unloaded.analyze(quick))
        assertEquals(AnalyzeChoice.Default, h.session.settings.analyzeChoice)
        assertTrue(h.session.tasks.isEmpty())
        runCurrent(); assertTrue(h.starts.isEmpty())

        h.session.load(); runCurrent()
        assertEquals(AnalysisStart.Unavailable, unloaded.analyze(quick))
        val loaded = AiSetupActions(h.session, h.models, h.session.captureView()) { AiCapabilities.Full }
        assertTrue(loaded.canStartAnalysis(quick))
        assertEquals(AnalysisStart.Started, loaded.analyze(quick))
        assertEquals(quick, h.session.settings.analyzeChoice)
        assertTrue(h.session.taskFor("analyze")?.running == true)
        assertEquals(AnalysisStart.AlreadyRunning, loaded.analyze(AnalyzeChoice.Default))
        runCurrent()
        assertEquals(1, h.starts.size)
        assertFalse("then" in h.starts.single())
        h.session.detach()
    }

    @Test
    fun standardAndDeepKeepTheirCompleteServerOwnedChainsAndPersistedModel() = runTest {
        for (preset in listOf(AnalyzePreset.Standard, AnalyzePreset.Deep)) {
            val h = Harness(backgroundScope)
            h.session.load(); runCurrent()
            h.models.save(ModelConfiguration("codex", "sol"))
            assertTrue(h.session.runAnalyze(h.models, AiCapabilities.Full, AnalyzeChoice.of(preset)))
            // The original optimistic work appears in the same frame, before any RPC can resume.
            assertTrue(h.session.taskFor("analyze")?.running == true)
            assertEquals(listOf("tour", "brief"), h.session.taskFor("analyze")?.then)
            runCurrent()
            val analysis = h.starts.single { it["kind"]?.jsonPrimitive?.content == "analyze" }
            assertEquals(listOf("tour", "brief"), analysis.getValue("then").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("en", analysis.getValue("locale").jsonPrimitive.content)
            val model = analysis.getValue("model").jsonObject
            assertEquals("codex", model.getValue("engine").jsonPrimitive.content)
            assertEquals("sol", model.getValue("model").jsonPrimitive.content)
            if (preset == AnalyzePreset.Deep) {
                assertEquals("high", model.getValue("effort").jsonPrimitive.content)
                val critique = h.starts.single { it["kind"]?.jsonPrimitive?.content == "critique" }
                assertEquals("head-a:codex:sol:high:", critique.getValue("variant").jsonPrimitive.content)
                assertEquals("high", critique.getValue("model").jsonObject.getValue("effort").jsonPrimitive.content)
            } else {
                assertFalse("effort" in model)
                assertEquals(1, h.starts.size)
            }
            h.session.detach()
        }
    }

    @Test
    fun unavailablePassesAreRejectedBeforeRequestsAndQuickUsesTheRealCatalogEffort() = runTest {
        val h = Harness(backgroundScope)
        h.session.load(); runCurrent()
        val withoutTour = AiCapabilities.Full.copy(canTour = false)
        assertFalse(h.session.runAnalyze(h.models, withoutTour, AnalyzeChoice.Default))
        assertFalse(h.session.runAnalyze(h.models, AiCapabilities.ReadOnly, AnalyzeChoice.of(AnalyzePreset.Quick)))
        assertTrue(h.session.tasks.isEmpty())
        runCurrent(); assertTrue(h.starts.isEmpty())
        assertTrue(h.session.runAnalyze(h.models, withoutTour, AnalyzeChoice.of(AnalyzePreset.Quick)))
        assertFalse(h.session.runAnalyze(h.models, withoutTour, AnalyzeChoice.of(AnalyzePreset.Quick)))
        runCurrent()
        val only = h.starts.single()
        assertEquals("minimal", only.getValue("model").jsonObject.getValue("effort").jsonPrimitive.content)
        assertFalse("then" in only)
        h.session.detach()
    }

    @Test
    fun actualRetainedSetupCommandsRejectRevocationHeadAbaSourceReplacementAndDisposal() = runTest {
        val h = Harness(backgroundScope)
        h.session.load(); runCurrent()
        var policy = AiCapabilities.Full
        val old = AiSetupActions(h.session, h.models, h.session.captureView()) { policy }
        policy = AiCapabilities.ReadOnly
        assertFalse(old.update()); assertEquals(AnalysisStart.Unavailable, old.analyze(AnalyzeChoice.Default))
        var asks = 0; old.ask { asks++ }; assertEquals(0, asks)
        policy = AiCapabilities.Full
        h.head = "head-b"; h.session.refresh(); runCurrent()
        h.head = "head-a"; h.session.refresh(); runCurrent()
        assertFalse(old.update()); assertFalse(old.generate())
        val current = AiSetupActions(h.session, h.models, h.session.captureView()) { policy }
        current.close(); assertFalse(current.update())
        val retired = AiSetupActions(h.session, h.models, h.session.captureView()) { policy }
        h.generation++
        assertFalse(retired.update()); assertFalse(retired.generate())
        runCurrent(); assertTrue(h.starts.isEmpty())
        h.session.detach()
    }

    @Test
    fun tourQueuesBehindExistingAnalysisAndFirstTourKeepsTheLastFullPreset() = runTest {
        val queued = Harness(backgroundScope)
        queued.session.load(); runCurrent()
        queued.session.startTask("analyze")
        assertTrue(generateTour(queued.session, queued.models, AiCapabilities.Full))
        assertTrue(queued.session.tourQueued)
        runCurrent()
        assertEquals(listOf("analyze"), queued.starts.map { it.getValue("kind").jsonPrimitive.content })
        queued.session.detach()
        val first = Harness(backgroundScope)
        first.session.load(); runCurrent()
        assertTrue(generateTour(first.session, first.models, AiCapabilities.Full))
        assertEquals(listOf("tour", "brief"), first.session.taskFor("analyze")?.then)
        runCurrent()
        assertEquals(1, first.starts.size)
        first.session.detach()
    }

    private class Harness(scope: CoroutineScope) {
        var generation = 0L
        var head = "head-a"
        var config = ModelConfiguration("claude-code", "")
        val starts = mutableListOf<JsonObject>()
        val cache = ReviewSessionCache(scope)
        val prefs = MemorySettingsPreferences().apply {
            edit().putString("catalog:claude-code", catalog).putString("catalog:codex", catalog).apply()
        }
        private val repo = Repo({ RepoConfiguration("https://review.example", engine = config.engine, model = config.model, generation = generation) }, RpcTransport { request ->
            val call = Json.parseToJsonElement(request.body).jsonObject
            val name = call.getValue("name").jsonPrimitive.content
            val args = call.getValue("args").jsonObject
            val result: JsonElement = when (name) {
                "github-pr" -> Json.parseToJsonElement("""{"key":"pull-1","diff":{"head":{"sha":"$head"},"files":[]},"groups":[]}""")
                "ai-task-list" -> JsonArray(emptyList())
                "ai-task-start" -> { starts += args; JsonPrimitive("task-${starts.size}") }
                "ai-task-wait" -> awaitCancellation()
                else -> JsonObject(emptyMap())
            }
            RpcResponse(200, buildJsonObject { put("result", result) }.toString(), "application/json")
        })
        val models = SourceModelSettingsServices(repo, prefs, { config }, { config = it }, { true })
        val session = ReviewSession(OpenTarget.Pull("owner", "repo", 1, "github.com"), repo, scope,
            AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
                override fun touched(name: String) {}
                override fun settingsChanged(key: String, value: String) {}
                override fun usageStatsEnabled(enabled: Boolean) {}
            }) { "en" }, cache = cache, effects = NoSessionEffects)
    }
}

private const val catalog = """{"models":[{"id":"sol","name":"Sol","efforts":["minimal","high","max"],"defaultEffort":"high","isDefault":true}]}"""
