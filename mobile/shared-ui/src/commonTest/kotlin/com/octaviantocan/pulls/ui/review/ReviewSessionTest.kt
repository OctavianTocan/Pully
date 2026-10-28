package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.data.RpcConnectionException
import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.SettingsPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewSessionTest {
    @Test
    fun sourceResetPublishesANewRouteAfterClearingTheOldSessionWithoutRemountingInitialCapture() = runTest {
        val h = Harness(backgroundScope)
        val first = h.page()
        first.load()
        runCurrent()
        assertEquals(0L, h.cache.routeGeneration)
        assertNotNull(first.opened)
        h.generation++
        h.cache.clear()
        assertNull(first.opened)
        assertFalse(first.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        assertEquals(1L, h.cache.routeGeneration)
        val next = h.page()
        assertEquals(1L, h.cache.routeGeneration)
        next.load()
        runCurrent()
        assertNotNull(next.opened)
        next.detach()
    }

    @Test
    fun warmedReviewIsImmediateAndJoiningTheWarmDoesNotOpenItTwice() = runTest {
        val h = Harness(backgroundScope)
        val delayed = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> if (name == "github-pr") delayed.await() else h.default(name, args) }
        h.cache.warm(h.repo, pull(1))
        runCurrent()
        val page = h.page()
        page.load()
        runCurrent()
        assertEquals(1, h.calls.count { it == "github-pr" })
        delayed.complete(opened())
        runCurrent()
        assertEquals("head-a", page.opened?.headSha)
        page.detach()
        val reopened = h.page()
        assertEquals("head-a", reopened.opened?.headSha)
        assertTrue(reopened.loading)
        reopened.detach()
    }

    @Test
    fun lruEvictsTheUnopenedReviewAndNeverSharesCopiesWithAnotherIdentity() = runTest {
        val h = Harness(backgroundScope)
        (1..12).forEach { h.cache.warm(h.repo, pull(it)) }
        runCurrent()
        h.page(1).also { assertNotNull(it.opened); it.detach() }
        h.cache.warm(h.repo, pull(13))
        runCurrent()
        h.page(1).also { assertNotNull(it.opened); it.detach() }
        h.page(2).also { assertNull(it.opened); it.detach() }
        h.generation++
        h.page(1).also { assertNull(it.opened); it.detach() }
    }

    @Test
    fun identityResetCancelsTheRouteAndLateNativeResponsesCannotRestorePrivateState() = runTest {
        val h = Harness(backgroundScope)
        val delayed = CompletableDeferred<JsonElement>()
        h.handler = { name, args ->
            if (name == "github-pr") withContext(NonCancellable) { delayed.await() } else h.default(name, args)
        }
        val page = h.page()
        page.load()
        runCurrent()
        h.generation++
        h.cache.clear()
        delayed.complete(opened())
        runCurrent()
        assertNull(page.opened)
        assertNull(page.overview)
        assertNull(page.saved)
        assertTrue(page.tasks.isEmpty())
        assertFalse(page.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        h.page().also { assertNull(it.opened); it.detach() }
    }

    @Test
    fun aPatchThatReturnsAfterTheHeadMovedCannotFillTheNewDiff() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        page.load()
        runCurrent()
        val delayed = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> if (name == "diff-patches") delayed.await() else h.default(name, args) }
        page.loadPatches(listOf("src/file.kt"))
        runCurrent()
        h.head = "head-b"
        page.refresh()
        runCurrent()
        delayed.complete(json("""{"files":{"src/file.kt":{"sha":"blob-a","hunks":[{"patch":"+old head"}]}}}"""))
        runCurrent()
        assertEquals("head-b", page.opened?.headSha)
        assertTrue(page.files.single().patchOmitted)
        assertTrue(page.files.single().patchLines.isEmpty())
        assertTrue(page.patchProblems.isEmpty())
        page.detach()
    }

    @Test
    fun rapidCommitSelectionsKeepTheLastChoiceWhenAnOlderRequestFinishesLate() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        page.load()
        runCurrent()
        val first = CompletableDeferred<JsonElement>()
        h.handler = { name, args ->
            if (name == "github-commit" && args["sha"]?.jsonPrimitive?.content == "first") first.await()
            else h.default(name, args)
        }
        page.selectCommits("first" to "first")
        runCurrent()
        page.selectCommits("last" to "last")
        runCurrent()
        first.complete(opened(head = "first", key = "commit-first"))
        runCurrent()
        assertEquals("last", page.opened?.headSha)
        assertEquals("last" to "last", page.selection)
        page.detach()
    }

    @Test
    fun staleTaskRevisionsCannotCompleteWorkButAnAuthoritativeOutcomeCan() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        page.load()
        runCurrent()
        val done = CompletableDeferred<JsonElement>()
        var reads = 0
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> when (++reads) {
                1 -> task(4, "running")
                2 -> task(3, "done")
                else -> done.await()
            }
            else -> h.default(name, args)
        } }
        val completed = mutableListOf<TaskState>()
        page.startTask("tour", onDone = { completed += it })
        runCurrent()
        assertEquals(4, page.tasks["task-1"]?.rev)
        assertTrue(page.tasks["task-1"]?.running == true)
        assertTrue(completed.isEmpty())
        assertTrue(h.effects.cues.isEmpty())
        testScheduler.advanceTimeBy(100)
        runCurrent()
        done.complete(task(5, "done"))
        runCurrent()
        assertEquals(listOf("done"), completed.map { it.status })
        assertEquals(listOf(SessionCue.Ready), h.effects.cues)
        assertEquals(listOf(4, 5), h.effects.updates.map { it.rev })
        page.detach()
    }

    @Test
    fun aLostPollIsAnObservationErrorWithoutAFailedTaskCueOrCompletion() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        page.load()
        runCurrent()
        val attempts = mutableListOf<Long>()
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> { attempts += testScheduler.currentTime; error("Network unavailable") }
            else -> h.default(name, args)
        } }
        val completed = mutableListOf<TaskState>()
        page.startTask("tour", onDone = { completed += it })
        runCurrent()
        assertTrue(page.tasks["task-1"]?.running == true)
        assertEquals(RpcConnectionException().message, page.taskObservationErrors["task-1"])
        testScheduler.advanceTimeBy(91_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 61_000L, 91_000L), attempts)
        assertTrue(completed.isEmpty())
        assertTrue(h.effects.cues.isEmpty())
        page.detach()
        testScheduler.advanceTimeBy(120_000)
        runCurrent()
        assertEquals(8, attempts.size)
        assertFalse("ai-task-cancel" in h.calls)
    }

    @Test
    fun invalidSavedServerOpensAnErrorPageWithoutWarmingOrOldIdentityData() = runTest {
        val h = Harness(backgroundScope)
        h.cache.warm(h.repo, pull(1))
        runCurrent()
        val old = h.page()
        assertNotNull(old.opened)
        h.server = "https://user:secret@review.example"
        h.cache.warm(h.repo, pull(2))
        val readsBefore = h.calls.size
        val page = h.page(tours = object : SessionTourCache {
            override fun get(pull: String): Tour? = error("Invalid sources must not read a tour cache")
            override suspend fun put(pull: String, tour: JsonObject) = error("No cache writes")
        })
        assertNull(old.opened)
        assertNull(page.opened)
        page.load()
        runCurrent()
        assertFalse(page.loading)
        assertNotNull(page.loadError)
        assertTrue(h.calls.size == readsBefore)
        assertNull(page.tour)
        h.server = "https://review.example"
        h.generation++
        h.page().also { assertNull(it.opened); it.detach() }
        assertFalse(page.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun interruptedObservationRetriesWithBackoffAndCompletesWhenTheNetworkReturns() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        page.load()
        runCurrent()
        var reads = 0
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> if (++reads <= 2) error("Offline") else task(3, "done")
            else -> h.default(name, args)
        } }
        var completed = 0
        page.startTask("tour", onDone = { completed++ })
        runCurrent()
        assertEquals(1, reads)
        assertTrue(page.tasks["task-1"]?.running == true)
        testScheduler.advanceTimeBy(999)
        runCurrent()
        assertEquals(1, reads)
        testScheduler.advanceTimeBy(1)
        runCurrent()
        assertEquals(2, reads)
        testScheduler.advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, reads)
        assertEquals(0, completed)
        assertTrue(h.effects.cues.isEmpty())
        testScheduler.advanceTimeBy(1)
        runCurrent()
        assertEquals(1, completed)
        assertEquals("done", page.tasks["task-1"]?.status)
        assertTrue(page.taskObservationErrors.isEmpty())
        assertEquals(listOf(SessionCue.Ready), h.effects.cues)
        page.detach()
    }

    @Test
    fun unfamiliarStatusCannotSettleOrEnableDuplicateWorkAndLaterDoneStillCompletes() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        page.load()
        runCurrent()
        var reads = 0
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> if (++reads == 1) task(2, "server-reconnecting") else task(3, "done")
            else -> h.default(name, args)
        } }
        var completed = 0
        page.startTask("tour", onDone = { completed++ })
        runCurrent()
        assertTrue(page.taskFor("tour")?.running == true)
        assertEquals(0, completed)
        assertTrue(h.effects.cues.isEmpty())
        assertNotNull(page.taskObservationErrors["task-1"])
        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, completed)
        assertEquals("done", page.tasks["task-1"]?.status)
        assertEquals(listOf(SessionCue.Ready), h.effects.cues)
        page.detach()
    }

    @Test
    fun aNewerTaskListCannotBeOverwrittenByAnOlderOutstandingPoll() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        page.load()
        runCurrent()
        val poll = CompletableDeferred<JsonElement>()
        var reads = 0
        var refreshed = false
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> if (++reads == 1) task(4, "running") else poll.await()
            "ai-task-list" -> if (refreshed) JsonArray(listOf(task(6, "done"))) else JsonArray(emptyList())
            else -> h.default(name, args)
        } }
        val completed = mutableListOf<TaskState>()
        page.startTask("tour", onDone = { completed += it })
        runCurrent()
        assertEquals(4, page.tasks["task-1"]?.rev)
        refreshed = true
        page.refresh()
        runCurrent()
        assertEquals(6, page.tasks["task-1"]?.rev)
        poll.complete(task(5, "running"))
        runCurrent()
        assertEquals(6, page.tasks["task-1"]?.rev)
        assertEquals("done", page.tasks["task-1"]?.status)
        assertEquals(listOf(6), completed.map { it.rev })
        assertEquals(listOf(SessionCue.Ready), h.effects.cues)
        page.detach()
    }

    @Test
    fun aNewerRunningTaskListSurvivesAnOlderPollAndObservationContinues() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        page.load()
        runCurrent()
        val poll = CompletableDeferred<JsonElement>()
        var reads = 0
        var refreshed = false
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> when (++reads) {
                1 -> task(4, "running")
                2 -> poll.await()
                else -> task(7, "done")
            }
            "ai-task-list" -> if (refreshed) JsonArray(listOf(task(6, "running"))) else JsonArray(emptyList())
            else -> h.default(name, args)
        } }
        val completed = mutableListOf<TaskState>()
        page.startTask("tour", onDone = { completed += it })
        runCurrent()
        refreshed = true
        page.refresh()
        runCurrent()
        poll.complete(task(5, "done"))
        runCurrent()
        assertEquals(6, page.tasks["task-1"]?.rev)
        assertTrue(page.tasks["task-1"]?.running == true)
        assertTrue(completed.isEmpty())
        assertTrue(h.effects.cues.isEmpty())
        testScheduler.advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(7), completed.map { it.rev })
        assertEquals("done", page.tasks["task-1"]?.status)
        page.detach()
    }

    @Test
    fun changingAFileMarkWhileOldMarksLoadStillResumesExistingServerWork() = runTest {
        val h = Harness(backgroundScope)
        val marks = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> when (name) {
            "reviewed-get" -> marks.await()
            "ai-task-list" -> JsonArray(listOf(task(2, "running")))
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        val page = h.page()
        page.load()
        runCurrent()
        page.setReviewed(listOf("blob-a"), true)
        runCurrent()
        marks.complete(json("""{"reviewed":[],"changedSinceReviewed":[]}"""))
        runCurrent()
        assertTrue("blob-a" in page.reviewed)
        assertTrue(page.taskFor("tour")?.running == true)
        page.detach()
    }

    @Test
    fun aMarkMadeElsewhereShowsAtOnceAndAMarkStillOnItsWayIsNotShownUndone() = runTest {
        val h = Harness(backgroundScope)
        val waits = ArrayDeque(listOf(CompletableDeferred<JsonElement>(), CompletableDeferred(), CompletableDeferred()))
        var serverMarks = listOf<String>()
        val written = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> when (name) {
            "sync-wait" -> waits.firstOrNull()?.await() ?: awaitCancellation()
            "reviewed-get" -> json("""{"reviewed":${serverMarks.map { "\"$it\"" }},"changedSinceReviewed":[]}""")
            "reviewed-set" -> written.await()
            else -> h.default(name, args)
        } }
        fun poke(version: Int) = waits.removeFirst().complete(json("""{"epoch":"e1","version":$version,"topics":["marks"],"reset":false}"""))
        val page = h.page(followServer = true)
        page.load()
        runCurrent()
        poke(1)
        runCurrent()
        assertFalse("blob-a" in page.reviewed)

        serverMarks = listOf("blob-a")
        poke(2)
        runCurrent()
        assertTrue("blob-a" in page.reviewed, "a mark made on another device shows without a reload")

        page.setReviewed(listOf("blob-a"), false)
        runCurrent()
        assertFalse("blob-a" in page.reviewed)
        poke(3)
        runCurrent()
        assertFalse("blob-a" in page.reviewed, "the server's older marks do not undo a write still on its way")
        written.complete(JsonObject(emptyMap()))
        runCurrent()
        assertEquals(1, h.calls.count { it == "reviewed-set" })
        page.detach()
    }

    @Test
    fun aBrokenNativeTourCacheCannotHideTheSavedServerResultOrStopTaskResume() = runTest {
        val h = Harness(backgroundScope)
        h.handler = { name, args -> when (name) {
            "review-state" -> json("""{"tour":{"title":"Saved tour","stops":[{"title":"Read this"}]}}""")
            "ai-task-list" -> JsonArray(listOf(task(2, "running")))
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        val page = h.page(tours = object : SessionTourCache {
            override fun get(pull: String): Tour? = error("Disk unavailable")
            override suspend fun put(pull: String, tour: JsonObject) { error("Disk full") }
        })
        page.load()
        runCurrent()
        assertEquals("Saved tour", page.tour?.title)
        assertTrue(page.taskFor("tour")?.running == true)
        assertNull(page.loadError)
        page.detach()
    }

    @Test
    fun identityChangeDuringTaskWaitCannotProduceAnOutcomeOrCancelServerWork() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        page.load()
        runCurrent()
        val delayed = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> withContext(NonCancellable) { delayed.await() }
            else -> h.default(name, args)
        } }
        var completed = false
        page.startTask("tour", onDone = { completed = true })
        runCurrent()
        h.generation++
        h.cache.clear()
        delayed.complete(task(1, "done"))
        runCurrent()
        assertFalse(completed)
        assertTrue(h.effects.cues.isEmpty())
        assertFalse("ai-task-cancel" in h.calls)
        assertTrue(page.tasks.isEmpty())
    }

    @Test
    fun warmingIsBoundedAndClosingARouteDoesNotIssueRemoteCancellation() = runTest {
        val h = Harness(backgroundScope)
        val cancelled = mutableListOf<String>()
        h.handler = { name, args ->
            if (name == "github-pr") try { awaitCancellation() } finally { cancelled += args["number"].toString() }
            else h.default(name, args)
        }
        (1..13).forEach { number -> h.cache.warm(h.repo, pull(number)); runCurrent() }
        assertEquals(listOf("1"), cancelled)
        h.cache.clear()
        runCurrent()
        assertEquals(13, cancelled.size)
        assertFalse("ai-task-cancel" in h.calls)
    }

    @Test
    fun stopGraceUsesTheInjectedClockAndDoesNotDelayResumedWork() = runTest {
        val h = Harness(backgroundScope)
        val page = h.page()
        assertEquals(Long.MAX_VALUE, page.millisSinceLastStart(listOf("tour")))
        page.load()
        runCurrent()
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        h.clock.now = 50
        page.startTask("tour")
        runCurrent()
        assertEquals(0, page.millisSinceLastStart(listOf("tour")))
        h.clock.now = 800
        assertEquals(750, page.millisSinceLastStart(listOf("tour")))
        assertEquals(Long.MAX_VALUE, page.millisSinceLastStart(listOf("critique")))
        page.detach()
    }
}

/** Run by iosTest against the real Compose runtime; Android host stubs cannot execute android.os.Trace. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.assertMountedRouteRecovery() {
    val h = Harness(backgroundScope)
    val clock = BroadcastFrameClock()
    val recomposer = Recomposer(coroutineContext + clock)
    val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    val composition = Composition(NoNodes(), recomposer)
    val routes = mutableListOf<Pair<ReviewSession, androidx.compose.runtime.MutableState<String>>>()
    try {
        composition.setContent {
            ReviewSessionRoute(pull(1), h.cache) {
                val page = remember { h.page().also { it.load() } }
                val draft = remember { mutableStateOf("").also { routes += page to it } }
                DisposableEffect(page) { onDispose { page.detach() } }
                // Keep the private draft observed in the same slot as a sheet's text field.
                draft.value
            }
        }
        runCurrent()
        val original = routes.single()
        assertEquals("head-a", original.first.opened?.headSha)
        assertEquals(1, h.calls.count { it == "github-pr" })
        original.second.value = "private account draft"
        Snapshot.sendApplyNotifications()
        runCurrent()
        clock.sendFrame(1L)
        runCurrent()
        assertSame(original.first, routes.single().first)

        // Settings/sign-in overlays leave this composition mounted while the native owner clears it.
        for ((index, server) in listOf("https://other.example", "https://other.example").withIndex()) {
            val previous = routes.last()
            h.server = server
            h.generation++
            h.head = "head-${index + 2}"
            h.cache.clear()
            assertFalse(previous.first.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(index + 2L)
            runCurrent()
            val current = routes.last()
            assertNotSame(previous.first, current.first)
            assertEquals("", current.second.value)
            assertEquals(h.head, current.first.opened?.headSha)
            assertFalse(current.first.loading)
            assertEquals(index + 2, h.calls.count { it == "github-pr" })
            current.second.value = "another private draft"
        }
        assertEquals(3, routes.size)
    } finally {
        composition.dispose()
        recomposer.close()
        runner.cancel()
    }
}

/** This composition tests state ownership, without platform UI nodes or an Android test runner. */
internal class NoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}

private class Harness(scope: CoroutineScope) {
    var generation = 0L
    var server = "https://review.example"
    var head = "head-a"
    val calls = mutableListOf<String>()
    val effects = RecordedEffects()
    val clock = TestClock()
    val cache = ReviewSessionCache(scope)
    var handler: suspend (String, JsonObject) -> JsonElement = ::default
    val repo = Repo(
        configuration = { RepoConfiguration(server, generation = generation) },
        transport = RpcTransport { request ->
            val envelope = Json.parseToJsonElement(request.body).jsonObject
            val name = envelope.getValue("name").jsonPrimitive.content
            calls += name
            val result = handler(name, envelope.getValue("args").jsonObject)
            RpcResponse(200, buildJsonObject { put("result", result) }.toString(), "application/json")
        },
    )
    private val pageScope = scope
    private val settings = AppSettings(DefaultPreferences, object : SettingsEffects {
        override fun touched(name: String) {}
        override fun settingsChanged(key: String, value: String) {}
        override fun usageStatsEnabled(enabled: Boolean) {}
    }) { "en" }
    fun page(number: Int = 1, tours: SessionTourCache? = null, followServer: Boolean = false): ReviewSession = ReviewSession(
        pull(number), repo, pageScope, settings, tourStore = tours, cache = cache, effects = effects, clock = clock,
        followServer = followServer,
    )

    fun default(name: String, args: JsonObject): JsonElement = when (name) {
        "github-pr" -> opened(head, "pull-${args["number"]}")
        "github-commit" -> opened(args["sha"]!!.jsonPrimitive.content, "commit-${args["sha"]!!.jsonPrimitive.content}")
        "pr-overview" -> json("""{"overview":{"title":"Current title","number":1}}""")
        "ai-task-list" -> JsonArray(emptyList())
        "jev-files" -> json("""{"enabled":false}""")
        else -> JsonObject(emptyMap())
    }
}

private class TestClock : SessionClock {
    var now = 0L
    override fun monotonicMillis(): Long = now
    override fun wallMillis(): Long = 1_000_000 + now
}

private class RecordedEffects : SessionEffects {
    val cues = mutableListOf<SessionCue>()
    val updates = mutableListOf<TaskState>()
    override fun cue(cue: SessionCue) { cues += cue }
    override fun updated(task: TaskState, source: SessionWorkSource?) { updates += task }
}

private object DefaultPreferences : SettingsPreferences {
    override fun getString(name: String, default: String?) = default
    override fun getBoolean(name: String, default: Boolean) = default
    override fun getFloat(name: String, default: Float) = default
    override fun getStringSet(name: String, default: Set<String>?) = default
    override fun contains(name: String) = false
    override fun edit(): SettingsPreferences.Editor = error("This fixture does not change settings")
}

private fun pull(number: Int) = OpenTarget.Pull("owner", "repo", number, "github.com")
private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
private fun opened(head: String = "head-a", key: String = "pull-1"): JsonElement = json(
    """{"key":"$key","diff":{"head":{"sha":"$head"},"files":[{"path":"src/file.kt","sha":"blob-a","status":"modified","patchOmitted":true}]},"groups":[]}""",
)
private fun task(rev: Int, status: String): JsonElement = json(
    """{"id":"task-1","key":"pull-1","kind":"tour","label":"Review","rev":$rev,"status":"$status","startedAt":1000}""",
)
