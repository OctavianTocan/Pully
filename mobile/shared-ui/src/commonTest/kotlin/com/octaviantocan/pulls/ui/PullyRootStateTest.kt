package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.data.*
import com.octaviantocan.pulls.settings.*
import com.octaviantocan.pulls.ui.home.*
import com.octaviantocan.pulls.ui.settings.*
import com.octaviantocan.pulls.sound.Direction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PullyRootStateTest {
    @Test
    fun observationReadinessFollowsTheSameSourceAcrossPauseReplacementAndClose() = runTest {
        val h = RootHarness(this)
        h.root.pauseObservations()
        val first = h.root.home(h.repo, 0, h::create)
        assertSame(h.source!!.observations, first.observations)
        assertFalse(first.observations.ready)
        h.root.resumeObservations()
        assertTrue(first.observations.ready)
        h.root.invalidate()
        assertFalse(first.observations.ready)
        h.epoch++
        val next = h.root.home(h.repo, h.epoch, h::create)
        assertFalse(next.observations.ready)
        h.root.resumeObservations()
        assertTrue(next.observations.ready)
        assertFalse(first.observations.ready)
        h.root.close()
        assertFalse(next.observations.ready)
        h.root.resumeObservations()
        assertFalse(next.observations.ready)
    }

    @Test
    fun ordinaryRepositoryNavigationKeepsTheApplicationOwnerAndPrivateInput() = runTest {
        val h = RootHarness(this)
        val services = h.root.home(h.repo, 0, h::create)
        val navigation = h.root.navigation
        h.root.homeUi.search = "private search"
        h.root.homeUi.askOpen = true
        services.navigation(navigation.nav).openRepo("private-owner", "private-repo")
        navigation.nav.back()
        assertSame(services, h.root.home(h.repo, 0, h::create))
        assertSame(navigation, h.root.navigation)
        assertEquals("private search", h.root.homeUi.search)
        assertTrue(h.root.homeUi.askOpen)
        assertEquals(1, h.created)
        h.root.close()
    }

    @Test
    fun beforeCredentialPublicationRevokesWorkPrivateRoutesCachesAndRetainedCallbacks() = runTest {
        val h = RootHarness(this)
        val services = h.root.home(h.repo, 0, h::create)
        val oldSource = h.source!!
        val navigation = h.root.navigation
        val retained = services.navigation(navigation.nav)
        retained.openRepo("private-owner", "private-repo")
        h.root.homeUi.search = "private search"
        h.root.homeUi.askOpen = true
        services.data.sizes.put("private-owner", "private-repo", 1, 8, 2, 1)
        val work = oldSource.scope.launch { awaitCancellation() }
        runCurrent()
        val reviewGeneration = h.root.reviewSessions.routeGeneration

        // Native storage still exposes the old configuration inside its before-change hook.
        h.root.invalidate()
        assertEquals(0, h.epoch)
        assertFalse(oldSource.current)
        assertTrue(work.isCancelled)
        assertFalse(services.data.current)
        assertNull(services.data.sizes.get("private-owner", "private-repo", 1))
        assertTrue(h.root.reviewSessions.routeGeneration > reviewGeneration)
        assertNotSame(navigation, h.root.navigation)
        assertEquals(listOf(Screen.Home), h.root.navigation.stack.toList())
        assertEquals("", h.root.homeUi.search)
        assertFalse(h.root.homeUi.askOpen)
        retained.openRepo("private-owner", "private-repo")
        assertEquals(2, navigation.stack.size)
        services.home.sync.select("open")
        assertEquals(0, h.requests)

        h.epoch = 1
        val replacement = h.root.home(h.repo, 1, h::create)
        assertTrue(replacement.data.current)
        assertTrue(h.source!!.current)
        assertNotSame(services, replacement)
        h.root.close()
        work.join()
    }

    @Test
    fun aNativeEpochChangeRetiresPrivateStateEvenWhenTheServerSourceKeyIsUnchanged() = runTest {
        val h = RootHarness(this)
        val original = h.root.home(h.repo, 0, h::create)
        val originalSource = h.source!!
        h.root.homeUi.search = "old private input"
        val replacement = h.root.home(h.repo, 1, h::create)
        assertFalse(originalSource.current)
        assertFalse(original.data.current)
        assertTrue(replacement.data.current)
        assertEquals("", h.root.homeUi.search)
        assertEquals(2, h.created)
        h.root.close()
    }

    @Test
    fun aReentrantSourceChangeCannotInstallTheHomeThatWasBeingCreated() = runTest {
        val h = RootHarness(this)
        var abandoned: HomeScreenServices? = null
        assertFailsWith<IllegalStateException> {
            h.root.home(h.repo, 0) { source ->
                h.create(source).also { abandoned = it; h.root.invalidate() }
            }
        }
        assertFalse(abandoned!!.data.current)
        assertFalse(h.source!!.current)
        assertTrue(h.root.home(h.repo, 1, h::create).data.current)
        h.root.close()
    }

    @Test
    fun ordinarySettingsDismissalKeepsTheSameModelsAccountAndFeedbackDraft() = runTest {
        val h = RootHarness(this)
        val home = h.root.home(h.repo, 0, h::create)
        val settings = h.root.settings(home, h::createSettings)
        settings.feedbackPage.ui.text = "Keep this unsent note"
        val models = settings.models
        h.root.navigation.nav.settings()
        h.root.navigation.nav.back()
        val reopened = h.root.settings(home, h::createSettings)
        assertSame(settings, reopened)
        assertSame(models, reopened.models)
        assertEquals("Keep this unsent note", reopened.feedbackPage.ui.text)
        assertEquals(1, h.settingsCreated)
        h.root.close()
    }

    @Test
    fun beforeCredentialPublicationClosesAccountSecretsFeedbackAndCommandsTogether() = runTest {
        val h = RootHarness(this)
        val home = h.root.home(h.repo, 0, h::create)
        val settings = h.root.settings(home, h::createSettings)
        val key = AgentKey("key", "Laptop", "hint", "2026-10-21T12:00:00Z", null)
        settings.account.ui.made = "one-time-test-value" to key
        settings.account.ui.opened = key
        settings.account.ui.keys = listOf(key)
        settings.feedbackPage.ui.text = "Private unsent feedback"
        val commands = h.root.commands(home)
        Commands.open(PaletteScope("account", "Account"))
        assertTrue(commands.current())
        h.root.invalidate() // The native storage still exposes the opening credential snapshot.
        assertEquals(0, h.epoch)
        assertFalse(settings.current())
        assertFalse(commands.current())
        assertFalse(Commands.paletteOpen)
        assertNull(Commands.scope)
        assertNull(settings.account.ui.made)
        assertNull(settings.account.ui.opened)
        assertNull(settings.account.ui.keys)
        assertEquals("", settings.feedbackPage.ui.text)
        assertFalse(settings.models.current())
        assertNull(settings.stats.cached(7))
        assertFalse(settings.account.copy("one-time-test-value"))
        assertFailsWith<CancellationException> { settings.models.remember("codex", "old-model") }
        assertFailsWith<CancellationException> { settings.feedbackPage.send("late", "review", "owner/repo/1", false) }
        assertEquals(0, h.requests)
        h.epoch++
        val nextHome = h.root.home(h.repo, h.epoch, h::create)
        val replacement = h.root.settings(nextHome, h::createSettings)
        assertNotSame(settings, replacement)
        assertNotSame(settings.models, replacement.models)
        assertTrue(replacement.current())
        assertEquals("", replacement.feedbackPage.ui.text)
        h.root.close()
    }

    @Test
    fun aReentrantSourceChangeClosesTheSettingsThatWasStillBeingConstructed() = runTest {
        val h = RootHarness(this)
        val home = h.root.home(h.repo, 0, h::create)
        var abandoned: SettingsServices? = null
        assertFailsWith<IllegalStateException> {
            h.root.settings(home) { source, services ->
                h.createSettings(source, services).also {
                    abandoned = it
                    it.feedbackPage.ui.text = "Never installed"
                    h.root.invalidate()
                }
            }
        }
        assertFalse(abandoned!!.current())
        assertEquals("", abandoned!!.feedbackPage.ui.text)
        assertFalse(abandoned!!.account.current())
        h.root.close()
    }
}

private class RootHarness(scope: CoroutineScope) {
    var epoch = 0L
    var requests = 0
    var created = 0
    var settingsCreated = 0
    var source: RootSource? = null
    private val settings = AppSettings(RootPreferences, object : SettingsEffects {
        override fun touched(name: String) = Unit
        override fun settingsChanged(key: String, value: String) = Unit
        override fun usageStatsEnabled(enabled: Boolean) = Unit
    }) { "en" }
    val root = PullyRootState(scope) { settings.inboxFilter }
    val repo = Repo({ RepoConfiguration("https://pully.example", generation = epoch) }, RpcTransport {
        requests++
        RpcResponse(200, "{\"result\":[]}")
    })

    fun create(source: RootSource): HomeScreenServices {
        this.source = source
        created++
        val values = mutableMapOf<String, String>()
        val storage = GuardedKeyValueStore(object : KeyValueStore {
            override fun get(key: String) = values[key]
            override fun set(key: String, value: String) { values[key] = value }
        }) { source.current }
        val sizes = PullSizes.source("test-root:${++nextSource}")
        val effects = object : HomeScreenEffects {
            override fun track(name: String, vararg props: Pair<String, Any?>) = Unit
            override fun error(name: String, message: String?) = Unit
            override fun size(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) =
                sizes.put(owner, repo, number, additions, deletions, files)
        }
        val home = HomeState(
            MyPullsSync({ null }, { repo.myPulls(it) }, storage = storage, currentSource = { source.current }),
            ArchiveStore(storage), TourIndex(storage), InboxLayout(storage),
        )
        val pages = object : RepoPageStorage {
            override suspend fun read(owner: String, name: String): JsonObject? = null
            override suspend fun write(owner: String, name: String, raw: JsonObject) = Unit
        }
        val data = HomeScreenData(repo, FirstPages(pages) { source.current },
            PostedDiffsStore(repo, storage, { true }, { source.current }),
            GithubStatsStore(repo, storage) { source.current }, { source.current }, effects, sizes)
        return HomeScreenServices(repo, settings, home, data, RootNative, effects,
            HomeScreenCapabilities(false, false, false, false, false, false, false, false, false, false),
            { error("Unexpected unavailable route") }, { error("Unexpected Commands") }, observations = source.observations)
    }

    fun createSettings(source: RootSource, home: HomeScreenServices): SettingsServices {
        settingsCreated++
        val current = { source.current }
        val models = SourceModelSettingsServices(repo, RootPreferences,
            { ModelConfiguration("codex", "fixture-model") }, { error("Unexpected model write") }, current)
        val account = SourceAccountPagesServices(repo, { "fixture@example.test" }, current,
            { error("Unexpected sign-out") }, { error("Unexpected clipboard") })
        val stats = SourceStatsServices(repo, RootPreferences, { true }, current)
        val feedback = SourceFeedbackServices(repo, home.settings,
            FeedbackMetadata("fixture", 1, "fixture", "fixture", "fixture"), { "home" }, { null }, current)
        return SettingsServices(home.settings, models, account, stats, feedback,
            { error("Unexpected Server page") }, object : SettingsFeedback {
                override fun vibrationOn() = error("Unexpected haptic")
                override fun soundsChanged(enabled: Boolean) = error("Unexpected sound")
                override fun soundSelected(direction: Direction?) = error("Unexpected sound")
            }, SettingsPlatformContent(emptySet(), {}, {}, {}, {}, { _, _ -> error("Unexpected platform page") }), current, {})
    }
}

private var nextSource = 0
private object RootNative : NativeHomeActions {
    override fun copyText(text: String) = error("Unexpected clipboard action")
    override fun openOnGithub(url: String) = error("Unexpected external action")
    override fun openInBrowser(url: String) = error("Unexpected browser action")
    override fun observeLifecycle(observer: HomeLifecycleObserver) = error("Unexpected lifecycle registration")
    override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver) = error("Unexpected Back registration")
}

private object RootPreferences : SettingsPreferences {
    override fun getString(name: String, default: String?) = default
    override fun getBoolean(name: String, default: Boolean) = default
    override fun getFloat(name: String, default: Float) = default
    override fun getStringSet(name: String, default: Set<String>?) = default
    override fun contains(name: String) = false
    override fun edit() = error("No settings are edited in this source-lifetime test")
}
