package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskStopActionTest {
    @Test
    fun aReal503StopLeavesTheTaskRunningAndASuccessfulRetryReconcilesTheServerReceipt() = runTest {
        for (kind in listOf("analyze", "tour", "critique", "ask", "chat")) {
            val h = StopFixture(backgroundScope, kind)
            h.page.load(); runCurrent()
            val errors = mutableListOf<String>()
            val action = h.action(onError = errors::add)
            action.stop(); runCurrent()
            assertEquals(listOf("server-$kind"), h.cancellations)
            assertEquals(listOf(StopFixture.ERROR), errors)
            assertTrue(h.page.tasks.getValue("server-$kind").running)

            h.reject = false
            action.stop(); runCurrent()
            assertEquals(listOf("server-$kind", "server-$kind"), h.cancellations)
            assertEquals(listOf(StopFixture.ERROR), errors)
            h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
            assertEquals("cancelled", h.page.tasks.getValue("server-$kind").status)
            assertEquals(0, h.starts)
            h.page.detach()
        }
    }

    @Test
    fun queuedUserStopsRecheckLivePermissionSourceAndControlLifetimeBeforeTheRpc() = runTest {
        for (retirement in listOf("foreground", "policy", "source", "control", "view")) {
            val h = StopFixture(backgroundScope)
            h.page.load(); runCurrent()
            if (retirement == "view") { h.page.selectCommits("sample-commit" to "sample-commit"); runCurrent() }
            var ready = true
            var capabilities = AiCapabilities.Full
            val errors = mutableListOf<String>()
            val action = h.action(ReviewActionReadiness({ ready }), { capabilities }, errors::add)
            action.stop() // The accepted tap has not reached its queued request yet.
            when (retirement) {
                "foreground" -> ready = false
                "policy" -> capabilities = AiCapabilities.ReadOnly
                "source" -> h.generation++
                "control" -> action.close()
                "view" -> h.page.selectCommits(null)
            }
            runCurrent()
            assertEquals(emptyList(), h.cancellations)
            assertEquals(emptyList(), errors)
            h.page.detach()
        }
    }

    @Test
    fun anAcceptedStopErrorKeepsItsReceiptThroughPauseAndControlDismissal() = runTest {
        for (dismiss in listOf(false, true)) {
            val h = StopFixture(backgroundScope)
            h.page.load(); runCurrent()
            var ready = true
            val reply = CompletableDeferred<RpcResponse>()
            h.cancelReply = reply
            val errors = mutableListOf<String>()
            val action = h.action(ReviewActionReadiness({ ready }), onError = errors::add)
            action.stop(); runCurrent()
            assertEquals(listOf("server-tour"), h.cancellations)
            ready = false
            h.cache.pauseObservations()
            if (dismiss) action.close()
            reply.complete(h.failure()); runCurrent()
            assertEquals(listOf(StopFixture.ERROR), errors)
            assertTrue(h.page.tasks.getValue("server-tour").running)
            ready = true
            h.cache.resumeObservations(); runCurrent()
            assertEquals(listOf(StopFixture.ERROR), errors)
            assertEquals(listOf("server-tour"), h.cancellations)
            h.page.detach()
        }
    }

    @Test
    fun aLateStopErrorCannotReachAReplacementSourceOrDetachedSession() = runTest {
        for (retirement in listOf("source", "session", "head-aba")) {
            val h = StopFixture(backgroundScope)
            h.page.load(); runCurrent()
            val reply = CompletableDeferred<RpcResponse>()
            h.cancelReply = reply
            val errors = mutableListOf<String>()
            h.action(onError = errors::add).stop(); runCurrent()
            assertEquals(listOf("server-tour"), h.cancellations)
            when (retirement) {
                "source" -> h.generation++
                "session" -> h.page.detach()
                "head-aba" -> {
                    h.head = "replacement-head"; h.page.refresh(); runCurrent()
                    h.head = "stop-head"; h.page.refresh(); runCurrent()
                    assertTrue(h.page.tasks.getValue("server-tour").running)
                }
            }
            reply.complete(h.failure()); runCurrent()
            assertEquals(emptyList(), errors)
            assertEquals(0, h.starts)
            h.page.detach()
        }
    }

    @Test
    fun cleanupCancellationRetainsItsExistingBestEffortBehavior() = runTest {
        val h = StopFixture(backgroundScope)
        h.page.load(); runCurrent()
        h.page.cancelTask("server-tour"); runCurrent()
        assertEquals(listOf("server-tour"), h.cancellations)
        assertTrue(h.page.tasks.getValue("server-tour").running)
        h.page.detach()
    }
}

/** A real Repo transport response, including an actual503 envelope rather than a thrown fixture exception. */
internal class StopFixture(scope: CoroutineScope, private val kind: String = "tour") {
    var generation = 0L
    var head = "stop-head"
    var reject = true
    var cancelReply: CompletableDeferred<RpcResponse>? = null
    var starts = 0
    val cancellations = mutableListOf<String>()
    private var status = "running"
    private var revision = 1
    val cache = ReviewSessionCache(scope)
    private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw)
    private val repo = Repo({ RepoConfiguration("https://stop.example", generation = generation) }, RpcTransport { request ->
        val call = Json.parseToJsonElement(request.body).jsonObject
        val args = call.getValue("args").jsonObject
        when (call.getValue("name").jsonPrimitive.content) {
            "ai-task-cancel" -> {
                require(args.keys == setOf("id") && args.getValue("id").jsonPrimitive.content == "server-$kind")
                cancellations += args.getValue("id").jsonPrimitive.content
                val response = cancelReply?.let { withContext(NonCancellable) { it.await() } }
                    ?: if (reject) failure() else success(JsonObject(emptyMap()))
                if (response.status == 200 && status == "running") { status = "cancelled"; revision++ }
                response
            }
            "ai-task-wait" -> awaitCancellation()
            "ai-task-start" -> { starts++; error("Stop must not start a task") }
            "github-pr" -> success(json("""{"key":"stop-pull","diff":{"head":{"sha":"$head"},"files":[]},"groups":[]}"""))
            "github-commit" -> success(json("""{"key":"stop-pull","diff":{"head":{"sha":"commit-head"},"files":[]},"groups":[]}"""))
            "pr-overview" -> success(json("""{"overview":{"title":"Stop sample","number":1}}"""))
            "ai-task-list" -> success(json("""[{"id":"server-$kind","key":"stop-pull","kind":"$kind","status":"$status","rev":$revision}]"""))
            "jev-files" -> success(json("""{"enabled":false}"""))
            else -> success(JsonObject(emptyMap()))
        }
    })
    val page = ReviewSession(OpenTarget.Pull("owner", "repo", 1, "github.com"), repo, scope,
        AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
            override fun touched(name: String) = Unit
            override fun settingsChanged(key: String, value: String) = Unit
            override fun usageStatsEnabled(enabled: Boolean) = Unit
        }) { "en" }, cache = cache, effects = NoSessionEffects)

    fun action(
        readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
        capabilities: () -> AiCapabilities = { AiCapabilities.Full },
        onError: (String) -> Unit,
    ) = TaskStopAction(page, page.captureView(), "server-$kind", listOf(kind), readiness, capabilities, { true }, onError)

    fun failure() = RpcResponse(503, """{"error":"$ERROR"}""", "application/json")
    private fun success(result: JsonElement) = RpcResponse(200, buildJsonObject { put("result", result) }.toString(), "application/json")

    companion object { const val ERROR = "Sample stop unavailable." }
}
