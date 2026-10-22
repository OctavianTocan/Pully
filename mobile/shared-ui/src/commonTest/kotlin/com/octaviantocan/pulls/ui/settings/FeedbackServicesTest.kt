package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedbackServicesTest {
    @Test
    fun sendsThroughTheExistingRpcWithTheOpeningScreenAndTruthfulNativeMetadata() = runTest {
        val h = Harness()
        h.services.ui.text = "  A note  "
        h.services.send(h.services.ui.text, "review", "owner/repo/12", true)
        assertEquals(listOf("feedback-send", "feedback-list"), h.methods)
        val args = h.arguments.first()!!
        assertEquals("A note", args["text"]?.jsonPrimitive?.content)
        assertEquals("review", args["screen"]?.jsonPrimitive?.content)
        assertEquals("owner/repo/12", args["place"]?.jsonPrimitive?.content)
        assertEquals("18.5", args["device"]?.jsonObject?.get("ios")?.jsonPrimitive?.content)
        assertNull(args["device"]?.jsonObject?.get("android"))
        assertEquals("", h.services.ui.text)
        assertEquals("kept", h.services.ui.sent?.single()?.text)
    }

    @Test
    fun failedSendRetainsDraftAndKeptHistoryForReopen() = runTest {
        val h = Harness()
        h.services.refresh()
        val kept = h.services.ui.sent
        h.services.ui.text = "Do not lose these words"
        h.respond = { _, _ -> error("offline") }
        assertFailsWith<IllegalStateException> { h.services.send(h.services.ui.text, "home", null, false) }
        assertEquals("Do not lose these words", h.services.ui.text)
        assertEquals(kept, h.services.ui.sent)
        // Nothing in sheet dismissal closes the source-owned services.
        assertEquals("Do not lose these words", h.services.ui.text)
    }

    @Test
    fun aSuccessfulSendStillCompletesWhenOnlyTheHistoryRefreshFails() = runTest {
        val h = Harness()
        h.services.refresh()
        h.services.ui.text = "A note"
        h.respond = { name, _ -> if (name == "feedback-list") error("offline") else RpcResponse(200, "{\"result\":null}") }
        h.services.send("A note", "home", null, false)
        assertEquals("", h.services.ui.text)
        assertEquals("kept", h.services.ui.sent?.single()?.text)
    }

    @Test
    fun sourceRetirementClearsDraftAndHistoryBeforeAnyReplacementAndBlocksRetainedActions() = runTest {
        val h = Harness()
        h.services.refresh()
        h.services.ui.text = "Private words"
        h.generation++
        h.services.close()
        assertEquals("", h.services.ui.text)
        assertNull(h.services.ui.sent)
        assertFalse(h.services.current())
        assertFailsWith<CancellationException> { h.services.send("Old callback", "home", null, false) }
        assertFailsWith<CancellationException> { h.services.refresh() }
        assertEquals(listOf("feedback-list"), h.methods)
    }

    @Test
    fun aLateNoncancellableHistoryReplyCannotRepopulateRetiredState() = runTest {
        val h = Harness()
        val reply = CompletableDeferred<RpcResponse>()
        h.respond = { _, _ -> withContext(NonCancellable) { reply.await() } }
        val task = async { h.services.refresh() }
        runCurrent()
        h.services.close()
        reply.complete(h.history("private"))
        assertFailsWith<CancellationException> { task.await() }
        assertNull(h.services.ui.sent)
    }

    @Test
    fun anOlderInitialRefreshCannotOverwritePostSendHistory() = runTest {
        val h = Harness()
        val initial = CompletableDeferred<RpcResponse>()
        h.respond = { name, index ->
            if (index == 0) withContext(NonCancellable) { initial.await() }
            else if (name == "feedback-list") h.history("after send") else RpcResponse(200, "{\"result\":null}")
        }
        val refreshing = async { h.services.refresh() }
        runCurrent()
        h.services.ui.text = "New note"
        h.services.send("New note", "home", null, false)
        initial.complete(h.history("before send"))
        refreshing.await()
        assertEquals("after send", h.services.ui.sent?.single()?.text)
    }

    @Test
    fun aFailedSendDoesNotDiscardAStillUsefulInitialHistoryReply() = runTest {
        val h = Harness()
        val initial = CompletableDeferred<RpcResponse>()
        h.respond = { name, _ ->
            if (name == "feedback-list") withContext(NonCancellable) { initial.await() }
            else error("offline")
        }
        val refreshing = async { h.services.refresh() }
        runCurrent()
        h.services.ui.text = "Retain my words"
        assertFailsWith<IllegalStateException> { h.services.send("Retain my words", "home", null, false) }
        initial.complete(h.history("existing note"))
        refreshing.await()
        assertEquals("existing note", h.services.ui.sent?.single()?.text)
        assertEquals("Retain my words", h.services.ui.text)
    }

    @Test
    fun cancellationAfterARequestCannotClearTheUnconfirmedDraftOrRefreshHistory() = runTest {
        val h = Harness()
        val reply = CompletableDeferred<RpcResponse>()
        h.respond = { _, _ -> withContext(NonCancellable) { reply.await() } }
        h.services.ui.text = "Keep on cancelled send"
        val sending = async { h.services.send(h.services.ui.text, "home", null, false) }
        runCurrent()
        sending.cancel()
        reply.complete(RpcResponse(200, "{\"result\":null}"))
        assertFailsWith<CancellationException> { sending.await() }
        assertEquals("Keep on cancelled send", h.services.ui.text)
        assertEquals(listOf("feedback-send"), h.methods)
    }

    @Test
    fun changingAccessOrAccountBeforeReplyCannotClearOrWriteIntoTheNewSource() = runTest {
        val h = Harness()
        val reply = CompletableDeferred<RpcResponse>()
        h.respond = { _, _ -> withContext(NonCancellable) { reply.await() } }
        h.services.ui.text = "Original"
        val sending = async { h.services.send("Original", "home", null, false) }
        runCurrent()
        h.generation++
        reply.complete(RpcResponse(200, "{\"result\":null}"))
        assertFailsWith<CancellationException> { sending.await() }
        assertEquals("Original", h.services.ui.text)
        assertEquals(listOf("feedback-send"), h.methods)
    }

    @Test
    fun invalidConfigurationShowsAnOrdinaryFailureWithoutSendingAndSettingsScreenMapsBackToSettings() = runTest {
        val h = Harness("not a server")
        assertEquals("settings", h.services.screen())
        h.services.ui.text = "Retained"
        assertFailsWith<IllegalArgumentException> { h.services.send("Retained", h.services.screen(), null, false) }
        assertEquals("Retained", h.services.ui.text)
        assertEquals(emptyList(), h.methods)
    }

    private class Harness(server: String = "https://feedback.example") {
        var generation = 0L
        val methods = mutableListOf<String>()
        val arguments = mutableListOf<JsonObject?>()
        var respond: suspend (String, Int) -> RpcResponse = { name, _ ->
            if (name == "feedback-list") history("kept") else RpcResponse(200, "{\"result\":null}")
        }
        private val repo = Repo({ RepoConfiguration(server, generation = generation) }, RpcTransport { request ->
            val body = Json.parseToJsonElement(request.body).jsonObject
            val name = body["name"]!!.jsonPrimitive.content
            val index = methods.size
            methods += name
            arguments += body["args"]?.jsonObject
            respond(name, index)
        })
        val settings = AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
            override fun touched(name: String) = Unit
            override fun settingsChanged(key: String, value: String) = Unit
            override fun usageStatsEnabled(enabled: Boolean) = Unit
        }) { "en" }
        val services = SourceFeedbackServices(repo, settings,
            FeedbackMetadata("0.58.0", 75, "Apple", "iPhone", "iOS 18.5", ios = "18.5"),
            { "settings-feedback" }, { null }, { true })
        fun history(text: String) = RpcResponse(200, """{"result":{"items":[{"id":"one","text":"$text","createdAt":"2026-10-21T18:00:00Z","status":"open"}]}}""")
    }
}
