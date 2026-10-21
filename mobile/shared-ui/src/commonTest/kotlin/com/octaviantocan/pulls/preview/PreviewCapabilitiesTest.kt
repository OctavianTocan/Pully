package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.SettingsPreferences
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.home.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class PreviewCapabilitiesTest {
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
