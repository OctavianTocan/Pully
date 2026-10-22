package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.SettingsPreferences
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.review.ReviewSessionCache
import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.ui.design.Toaster
import com.octaviantocan.pulls.ui.home.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreviewCapabilitiesTest {
    @Test
    fun postedDeleteRequiresTheLiveGateButItsAcceptedReceiptSurvivesPause() = runTest {
        val reply = CompletableDeferred<Unit>()
        var requests = 0
        val repo = Repo({ RepoConfiguration("https://fixture.example") }, RpcTransport {
            requests++
            reply.await()
            RpcResponse(200, """{"result":{}}""")
        })
        val gate = HomeObservations(false)
        val services = writeServices(repo, gate)
        val row = services.data.posted.rows.single()
        assertFalse(services.deletePosted(row))
        assertEquals(0, requests)
        gate.resume()
        val accepted = async { services.deletePosted(row) }
        runCurrent()
        assertEquals(1, requests)
        gate.pause()
        reply.complete(Unit)
        assertTrue(accepted.await())
        assertTrue(services.data.posted.rows.isEmpty())
        assertFalse(services.deletePosted(row))
        assertEquals(1, requests)
    }

    @Test
    fun anAcceptedPostedReceiptCannotRefillARetiredSource() = runTest {
        val reply = CompletableDeferred<Unit>()
        val services = writeServices(Repo({ RepoConfiguration("https://fixture.example") }, RpcTransport {
            reply.await()
            RpcResponse(200, """{"result":{}}""")
        }), HomeObservations())
        val accepted = async { services.deletePosted(services.data.posted.rows.single()) }
        runCurrent()
        services.data.invalidate()
        reply.complete(Unit)
        assertFalse(accepted.await())
        assertTrue(services.data.posted.rows.isEmpty())
    }

    @Test
    fun aQueuedTourStopDoesNotDispatchOrAnnounceSuccessAfterReadinessCloses() = runTest {
        var requests = 0
        val repo = Repo({ RepoConfiguration("https://fixture.example") }, RpcTransport {
            requests++
            RpcResponse(200, """{"result":{}}""")
        })
        val gate = HomeObservations()
        val tours = TourIndex(null)
        val entry = TourEntry("owner", "repo", 1, "Sample tour", 1, 0, "", true, 1L)
        tours.upsert(entry)
        val toaster = Toaster()
        dismissTour(entry, listOf(WorkTask("github:owner/repo#1", "tour", "running", id = "sample-task")), tours, repo, this, toaster, ready = { gate.ready }) { true }
        gate.pause()
        runCurrent()
        assertEquals(0, requests)
        assertEquals(entry, tours.get(entry.key))
        assertNull(toaster.message)
    }

    @Test
    fun anAcceptedTourStopSettlesAfterPauseButNotAfterSourceReplacement() = runTest {
        for (replaceSource in listOf(false, true)) {
            val reply = CompletableDeferred<Unit>()
            var current = true
            var requests = 0
            val repo = Repo({ RepoConfiguration("https://fixture.example") }, RpcTransport {
                requests++
                reply.await()
                RpcResponse(200, """{"result":{}}""")
            })
            val gate = HomeObservations()
            val tours = TourIndex(null)
            val entry = TourEntry("owner", "repo", 1, "Sample tour", 1, 0, "", true, 1L)
            tours.upsert(entry)
            val toaster = Toaster()
            dismissTour(entry, listOf(WorkTask("github:owner/repo#1", "tour", "running", id = "sample-task")), tours, repo, this, toaster, ready = { gate.ready }) { current }
            runCurrent()
            assertEquals(1, requests)
            assertEquals(entry, tours.get(entry.key))
            assertNull(toaster.message)
            gate.pause()
            if (replaceSource) current = false
            reply.complete(Unit)
            runCurrent()
            if (replaceSource) {
                assertEquals(entry, tours.get(entry.key))
                assertNull(toaster.message)
            } else {
                assertFalse(tours.get(entry.key)!!.writing)
                assertNotNull(toaster.message)
            }
        }
    }

    @Test
    fun aFailedTourStopKeepsTheRunningEntryAndOffersItsError() = runTest {
        val feedback = mutableListOf<com.octaviantocan.pulls.sound.Cue>()
        val previous = runCatching { com.octaviantocan.pulls.sound.ControlFeedback.installed() }.getOrNull()
        com.octaviantocan.pulls.sound.ControlFeedback.install(object : com.octaviantocan.pulls.sound.NativeControlFeedback {
            override fun play(cue: com.octaviantocan.pulls.sound.Cue, emphasis: com.octaviantocan.pulls.sound.Emphasis, direction: com.octaviantocan.pulls.sound.Direction?, input: com.octaviantocan.pulls.sound.Input?) { feedback += cue }
            override fun hold() = Unit
            override fun gesture(role: com.octaviantocan.pulls.sound.NativeGestureFeedback) = Unit
        })
        try {
        val repo = Repo({ RepoConfiguration("https://fixture.example") }, RpcTransport {
            RpcResponse(503, """{"error":"Sample stop unavailable."}""", "application/json")
        })
        val tours = TourIndex(null)
        val entry = TourEntry("owner", "repo", 1, "Sample tour", 1, 0, "", true, 1L)
        tours.upsert(entry)
        val toaster = Toaster()
        dismissTour(entry, listOf(WorkTask("github:owner/repo#1", "tour", "running", id = "sample-task")), tours, repo, this, toaster) { true }
        runCurrent()
        assertEquals(entry, tours.get(entry.key))
        assertNotNull(toaster.message)
        assertEquals(listOf(com.octaviantocan.pulls.sound.Cue.Error), feedback)
        } finally {
            if (previous != null) com.octaviantocan.pulls.sound.ControlFeedback.install(previous)
        }
    }

    @Test
    fun unsupportedReviewTourAndSettingsDestinationsStateTheirLimitationWithoutChangingTheStack() {
        val limitations = mutableListOf<HomePreviewFeature>()
        val services = preview(unavailable = limitations::add)
        val navigation = AppNavigation()
        val nav = services.navigation(navigation.nav)
        val target = OpenTarget.Pull("pully", "mobile-fixtures", 76, "github.com")
        nav.openTarget(target); nav.openTour(target); nav.openFrom(target, "inbox", true)
        nav.openPlace(target, "search", LinkPlace.Files(line = 7)); nav.settings(); services.commands()
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
        assertEquals(listOf(HomePreviewFeature.Review, HomePreviewFeature.Tour, HomePreviewFeature.Tour, HomePreviewFeature.Review, HomePreviewFeature.Settings, HomePreviewFeature.Commands), limitations)
        assertNull(services.ask)
        assertFalse(services.capabilities.startTour)
        assertFalse(services.capabilities.deletePosted)
        nav.openRepo("pully", "mobile-fixtures")
        assertEquals(Screen.Repo("pully", "mobile-fixtures"), navigation.stack.last())
        nav.back()
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
    }

    @Test
    fun supportedFiltersAndRepositoryPagesUseTheActualSharedRpcShapesAndPolicies() = runTest {
        val services = preview()
        services.home.sync.select("open")
        val pulls = services.home.sync.pulls!!
        assertEquals(27, pulls.size)
        assertEquals(2, com.octaviantocan.pulls.data.Triage.inbox(pulls, "authored", emptySet(), "").pulls.size)
        val store = services.data.repository("pully", "mobile-fixtures")
        store.load()
        assertEquals(27, store.items.size)
        assertEquals(listOf(74), PullListQuery.filter(store.items, "review workflow").map { it.number })
        assertNull(store.error)
    }

    @Test
    fun theFixtureTransportCannotStartPaidWorkEvenWhenACallerBypassesItsUiGate() = runTest {
        val services = preview()
        assertFailsWith<IllegalStateException> { services.repo.startTask("analysis", "github:pully/mobile-fixtures#76") }
    }

    @Test
    fun retainedOldSourceCallbacksCannotRouteOrSendAQuestionUnderTheNextOwner() {
        val limitations = mutableListOf<HomePreviewFeature>()
        val s = preview(unavailable = limitations::add)
        var sent = 0
        var commands = 0
        val services = HomeScreenServices(s.repo, s.settings, s.home, s.data, s.native, s.effects,
            s.capabilities.copy(inboxAsk = true, commands = true), limitations::add, { commands++ },
            ask = object : HomeAskSurface {
                override fun invalidate() = Unit
                override fun sendOrQueue(question: String) { sent++ }
                @androidx.compose.runtime.Composable override fun Content(listed: List<com.octaviantocan.pulls.core.ListedPull>, onOpen: (com.octaviantocan.pulls.core.ListedPull) -> Unit, onClose: () -> Unit) = Unit
            })
        val stack = AppNavigation()
        val retained = services.navigation(stack.nav)
        retained.openRepo("pully", "mobile-fixtures")
        assertEquals(2, stack.stack.size)
        s.data.invalidate()
        val target = OpenTarget.Pull("pully", "mobile-fixtures", 76, "github.com")
        retained.openRepo("private-old-owner", "private-old-repo")
        retained.openRepoTours("private-old-owner", "private-old-repo")
        retained.openTarget(target); retained.openTour(target)
        retained.openFrom(target, "inbox", false); retained.openPlace(target, "inbox", LinkPlace.Files(line = 7))
        retained.settings(); retained.uiBook()
        services.commands(); services.sendQuestion("old private question")
        assertEquals(2, stack.stack.size)
        assertTrue(limitations.isEmpty())
        assertEquals(0, commands)
        assertEquals(0, sent)
        retained.back()
        assertEquals(listOf(Screen.Home), stack.stack.toList())
        stack.nav.openRepo("new-owner", "new-repo")
        retained.home()
        assertEquals(listOf(Screen.Home), stack.stack.toList())
    }

    @Test
    fun enablingAnAskEntryWithoutItsCompleteRendererIsRejectedBeforeAnyWorkCanBeSent() {
        val s = preview()
        assertFailsWith<IllegalArgumentException> {
            HomeScreenServices(s.repo, s.settings, s.home, s.data, s.native, s.effects,
                s.capabilities.copy(inboxAsk = true), {}, {})
        }
    }

    @Test
    fun retainedProductionRowPressCannotWarmItsPullThroughReplacementCredentials() = runTest {
        val requests = mutableListOf<RpcRequest>()
        var configuration = RepoConfiguration("https://first.example", mapOf("X-Pully-Session" to "fixture-first"))
        val repo = Repo({ configuration }, RpcTransport { request ->
            requests += request
            RpcResponse(200, """{"result":{"key":"fixture-warm","diff":{"head":{"sha":"fixture-head"},"files":[]},"groups":[]}}""")
        })
        val cache = ReviewSessionCache(backgroundScope)
        val first = preview()
        fun services(base: HomeScreenServices) = HomeScreenServices(
            repo, base.settings, base.home, base.data, base.native, base.effects,
            base.capabilities.copy(reviewRoutes = true), {}, {}, reviewSessions = cache,
        )
        val firstServices = services(first)
        val oldPress = firstServices::warmReview
        val oldRow = PullRow("first-owner", "private-repo", 1, "Sample private pull", "reader", "", false)
        oldPress(oldRow)
        runCurrent()
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.headers["X-Pully-Session"] == "fixture-first" })
        requests.clear()
        cache.clear()

        first.data.invalidate() // The native/Android before-change owner retires before credential publication.
        configuration = RepoConfiguration("https://second.example", mapOf("X-Pully-Session" to "fixture-second"))
        oldPress(oldRow)
        runCurrent()
        assertTrue(requests.isEmpty())

        val secondServices = services(preview())
        secondServices.warmReview(oldRow.copy(owner = "second-owner", number = 2))
        runCurrent()
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.headers["X-Pully-Session"] == "fixture-second" })
        requests.clear()
        cache.clear()
        oldPress(oldRow)
        runCurrent()
        assertTrue(requests.isEmpty())
    }
}

private fun writeServices(repo: Repo, observations: HomeObservations): HomeScreenServices {
    val base = preview()
    val values = mutableMapOf("rows" to """[{"id":"sample-posted","title":"Sample posted diff","author":"sample","version":1,"updatedAt":"2026-10-21","files":1,"additions":1,"deletions":0}]""")
    val storage = object : KeyValueStore {
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) { values[key] = value }
    }
    val data = HomeScreenData(
        repo, base.data.pages, PostedDiffsStore(repo, storage, { true }, { base.data.current }),
        base.data.stats, { base.data.current }, base.effects, base.data.sizes,
    )
    return HomeScreenServices(
        repo, base.settings, base.home, data, base.native, base.effects,
        base.capabilities.copy(postedDiffs = true, deletePosted = true), {}, {}, observations = observations,
    )
}

private fun preview(unavailable: (HomePreviewFeature) -> Unit = {}): HomeScreenServices {
    val values = mutableMapOf<String, String>()
    val store = object : KeyValueStore {
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) { values[key] = value }
    }
    val pages = object : RepoPageStorage {
        override suspend fun read(owner: String, name: String): JsonObject? = null
        override suspend fun write(owner: String, name: String, raw: JsonObject) = Unit
    }
    val native = object : NativeHomeActions {
        override fun copyText(text: String) = Unit
        override fun openOnGithub(url: String) = Unit
        override fun openInBrowser(url: String) = Unit
        override fun observeLifecycle(observer: HomeLifecycleObserver) = object : HomeLifecycleSubscription { override fun cancel() = Unit }
        override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver) = object : HomeLifecycleSubscription { override fun cancel() = Unit }
    }
    val settings = AppSettings(PreviewPreferences, object : SettingsEffects {
        override fun touched(name: String) = Unit
        override fun settingsChanged(key: String, value: String) = Unit
        override fun usageStatsEnabled(enabled: Boolean) = Unit
    }) { "en" }
    return previewServices(native, settings, store, pages, unavailable)
}

private object PreviewPreferences : SettingsPreferences {
    override fun getString(name: String, default: String?) = default
    override fun getBoolean(name: String, default: Boolean) = default
    override fun getFloat(name: String, default: Float) = default
    override fun getStringSet(name: String, default: Set<String>?) = default
    override fun contains(name: String) = false
    override fun edit() = object : SettingsPreferences.Editor {
        override fun putString(name: String, value: String?) = this
        override fun putBoolean(name: String, value: Boolean) = this
        override fun putFloat(name: String, value: Float) = this
        override fun putStringSet(name: String, value: Set<String>) = this
        override fun apply() = Unit
    }
}
