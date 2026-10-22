package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.SettingsPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiffUiTest {
    @Test
    fun aRetiredFileCallbackCannotStartReadsUnderTheNewHeadsOwnerToken() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        h.head = "head-b"
        h.page.refresh(); runCurrent()
        val reads = mutableListOf<String>()
        h.handler = { name, args ->
            if (name == "file-content" || name == "highlight") reads += name
            h.default(name, args)
        }
        ui.loadFullFile(file)
        ui.highlight(file, listOf("old text"), dark = false)
        runCurrent()
        assertTrue(ui.fullFiles.isEmpty())
        assertTrue(ui.fullErrors.isEmpty())
        assertTrue(ui.tokens.isEmpty())
        assertTrue(reads.isEmpty())
        h.page.detach()
    }

    @Test
    fun aRemovedFilesRetiredCallbackCannotAdoptTheNewHeadsOwner() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        h.handler = { name, args -> if (name == "github-pr")
            json("""{"key":"pull-1","diff":{"head":{"sha":"head-b"},"files":[]},"groups":[]}""")
            else h.default(name, args)
        }
        h.page.refresh(); runCurrent()
        assertTrue(h.page.files.isEmpty())
        val reads = mutableListOf<String>()
        h.handler = { name, args -> reads += name; h.default(name, args) }
        assertFalse(ui.canLoadFull(file))
        assertTrue(ui.entries(file).isEmpty())
        ui.loadFullFile(file)
        ui.highlight(file, listOf("retired text"), dark = false)
        runCurrent()
        assertTrue(reads.isEmpty())
        h.page.detach()
    }

    @Test
    fun unchangedViewerRowsMustComeFromACurrentReadAndRetireWithTheirHead() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val ui = DiffUi(h.page)
        val read = kotlin.test.assertNotNull(ui.captureFileRead())
        val file = kotlin.test.assertNotNull(ui.readUnchangedFile(read, "other.kt", "current line"))
        assertEquals(1, ui.entries(file).size)
        h.head = "head-b"
        h.page.refresh(); runCurrent()
        assertNull(ui.readUnchangedFile(read, "late.kt", "earlier response"))
        assertFalse(ui.canLoadFull(file))
        assertTrue(ui.entries(file).isEmpty())
        h.page.detach()
    }

    @Test
    fun unchangedReadTokensCannotCrossSessionsEvenAtIdenticalVersions() = runTest {
        val first = DiffHarness(backgroundScope)
        val second = DiffHarness(backgroundScope)
        first.page.load(); second.page.load(); runCurrent()
        assertEquals(first.page.key, second.page.key)
        assertEquals(first.page.opened?.headSha, second.page.opened?.headSha)
        val a = DiffUi(first.page)
        val b = DiffUi(second.page)
        val read = kotlin.test.assertNotNull(a.captureFileRead())
        assertFalse(b.isCurrentFileRead(read))
        assertNull(b.readUnchangedFile(read, "private.kt", "earlier account text"))
        assertTrue(b.entries(unchangedFile("private.kt", "unregistered text")).isEmpty())
        first.page.detach(); second.page.detach()
    }

    @Test
    fun fileReadKeysStayStableUntilTheFullRevisionChangesIncludingHeadAba() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val ui = DiffUi(h.page)
        val original = kotlin.test.assertNotNull(ui.captureFileRead())
        kotlin.test.assertSame(original, ui.captureFileRead())
        h.head = "head-b"
        h.page.refresh(); runCurrent()
        h.head = "head-a"
        h.page.refresh(); runCurrent()
        // The native view may not compose between these responses; key/head alone are identical again.
        val current = kotlin.test.assertNotNull(ui.captureFileRead())
        kotlin.test.assertNotSame(original, current)
        assertFalse(ui.isCurrentFileRead(original))
        kotlin.test.assertSame(current, ui.captureFileRead())
        h.page.detach()
    }

    @Test
    fun aFullFileFromAnEarlierHeadCannotFillTheNewViewOrKeepItsDrafts() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val answer = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> if (name == "file-content") answer.await() else h.default(name, args) }
        ui.drafts[file.path] = DraftTarget(file.path, "additions", 2)
        ui.loadFullFile(file, 1)
        runCurrent()
        h.head = "head-b"
        h.page.refresh(); runCurrent()
        ui.entries(h.page.files.single())
        answer.complete(json("""{"old":"old head","new":"wrong head"}"""))
        runCurrent()
        assertTrue(ui.fullFiles.isEmpty())
        assertTrue(ui.expandedGaps.isEmpty())
        assertTrue(ui.fullErrors.isEmpty())
        assertTrue(ui.drafts.isEmpty())
        assertFalse(ui.fullLoading[file.path] == true)
        h.page.detach()
    }

    @Test
    fun identityCancellationCannotPublishAFullFileOrFailureAfterTheNativeRequestReturns() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val answer = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> if (name == "file-content") withContext(NonCancellable) { answer.await() } else h.default(name, args) }
        ui.loadFullFile(file)
        runCurrent()
        h.generation++
        h.cache.clear()
        ui.entries(file)
        answer.complete(json("""{"new":"private earlier account"}"""))
        runCurrent()
        assertTrue(ui.fullFiles.isEmpty())
        assertTrue(ui.fullErrors.isEmpty())
        assertNull(h.page.opened)
    }

    @Test
    fun delayedHighlightForAnEarlierHeadCannotReplaceNewTokensOrTheme() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val answer = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> if (name == "highlight") answer.await() else h.default(name, args) }
        val work = backgroundScope.launch { ui.highlight(file, listOf("old text"), dark = false) }
        runCurrent()
        h.head = "head-b"
        h.page.refresh(); runCurrent()
        ui.entries(h.page.files.single())
        answer.complete(json("""{"theme":{"background":"#ffffff"},"lines":[[{"text":"old text","color":"#ff0000"}]]}"""))
        work.join()
        assertTrue(ui.tokens.isEmpty())
        assertNull(ui.theme)
        h.page.detach()
    }

    @Test
    fun actualHighlightCallsRespectTheServerLimitAndRetryOnlyMissingRows() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val calls = mutableListOf<List<String>>()
        var omitLast = true
        h.handler = { name, args -> if (name == "highlight") {
            val lines = args.getValue("lines").jsonArray.map { it.jsonPrimitive.content }
            calls += lines
            val returned = if (omitLast) lines.dropLast(1) else lines
            buildJsonObject { put("lines", JsonArray(returned.map { text -> JsonArray(listOf(buildJsonObject { put("text", text) })) })) }
        } else h.default(name, args) }
        val lines = (1..401).map { "line $it" }
        ui.highlight(file, lines, dark = false)
        assertEquals(listOf(400, 1), calls.map { it.size })
        assertEquals(lines.take(399).toSet(), ui.tokens.getValue(file.path).keys)
        omitLast = false
        ui.highlight(file, lines, dark = false)
        assertEquals(listOf(lines[399], lines[400]), calls.last())
        assertEquals(lines.toSet(), ui.tokens.getValue(file.path).keys)
        h.page.detach()
    }
}

internal class DiffHarness(scope: CoroutineScope) {
    var engine = "claude-code"
    var model = ""
    var generation = 0L
    var head = "head-a"
    val cache = ReviewSessionCache(scope)
    var handler: suspend (String, JsonObject) -> JsonElement = ::default
    private val repo = Repo(
        configuration = { RepoConfiguration("https://review.example", engine = engine, model = model, generation = generation) },
        transport = RpcTransport { request ->
            val call = Json.parseToJsonElement(request.body).jsonObject
            val envelope = try {
                val result = handler(call.getValue("name").jsonPrimitive.content, call.getValue("args").jsonObject)
                buildJsonObject { put("result", result) }
            } catch (rejection: DiffRpcRejection) {
                buildJsonObject { put("error", rejection.message) }
            }
            RpcResponse(200, envelope.toString(), "application/json")
        },
    )
    val page = ReviewSession(
        OpenTarget.Pull("owner", "repo", 1, "github.com"), repo, scope,
        AppSettings(DiffPreferences, object : SettingsEffects {
            override fun touched(name: String) {}
            override fun settingsChanged(key: String, value: String) {}
            override fun usageStatsEnabled(enabled: Boolean) {}
        }) { "en" },
        cache = cache,
        effects = object : SessionEffects {
            override fun cue(cue: SessionCue) {}
            override fun updated(task: TaskState, source: SessionWorkSource?) {}
        },
    )
    /** Model a real server RPC rejection; ordinary thrown transport failures keep their separate path. */
    fun serverError(message: String): Nothing = throw DiffRpcRejection(message)

    fun default(name: String, args: JsonObject): JsonElement = when (name) {
        "github-pr" -> json("""{"key":"pull-1","diff":{"head":{"sha":"$head"},"files":[{"path":"src/file.kt","sha":"$head-blob","status":"modified"}]},"groups":[]}""")
        "pr-overview" -> json("""{"overview":{"title":"Current title","number":1}}""")
        "ai-task-list" -> JsonArray(emptyList())
        "jev-files" -> json("""{"enabled":false}""")
        else -> JsonObject(emptyMap())
    }
}

private class DiffRpcRejection(message: String) : Exception(message)

private object DiffPreferences : SettingsPreferences {
    override fun getString(name: String, default: String?) = default
    override fun getBoolean(name: String, default: Boolean) = default
    override fun getFloat(name: String, default: Float) = default
    override fun getStringSet(name: String, default: Set<String>?) = default
    override fun contains(name: String) = false
    override fun edit(): SettingsPreferences.Editor = error("This fixture does not change settings")
}

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
