package com.octaviantocan.pulls.ios

import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.IncomingLinkInbox
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import com.octaviantocan.pulls.ui.home.HomeBackObserver
import com.octaviantocan.pulls.ui.home.HomeLifecycleObserver
import com.octaviantocan.pulls.ui.home.HomeLifecycleSubscription
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IosPersonalGuestWorkspaceTest {
    @Test
    fun theOrdinaryGuestMountUsesItsVerifiedAccountAndRealActivityPortForPersonalPages() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val requests = mutableListOf<RpcRequest>()
        val actions = ActiveActions()
        val owner = """"accountId":"account-42","githubId":42,"authority":"privateRepositories""""
        val repository = """{"id":42,"owner":"acme","name":"editor","fullName":"acme/editor","description":null,"stars":0,"language":null,"url":"https://github.com/acme/editor","private":true}"""
        val store = IosPersonalSessionStore(object : NativePersonalSessionStorage {
            override fun read(): NativePersonalSessionRead = NativePersonalSessionRead("""{"version":1,"accepted":{"origin":"https://accounts.example","token":"pully_${"A".repeat(43)}","accountId":"account-42","githubId":42,"authority":"privateRepositories"},"pending":[]}""".encodeToByteArray())
            override fun write(contents: ByteArray) = error("An ordinary read must not rewrite the vault")
        }, "https://accounts.example", RpcTransport { request ->
            requests += request
            val body = when {
                request.url.endsWith("/session") -> """{$owner,"login":"reader","sessionExpiresAt":9999999999,"accessExpiresAt":null,"refreshAvailable":false}"""
                request.url.endsWith("/account/capabilities") -> """{$owner,"privateRepositories":{"consentAvailable":true,"readEnabled":true}}"""
                request.url.endsWith("/account/pulls?relation=reviewRequested&page=1") -> """{$owner,"relation":"reviewRequested","pulls":[{"repository":$repository,"number":7,"title":"Owned private change","url":"https://github.com/acme/editor/pull/7","author":"reader","draft":false,"createdAt":"2026-10-22","updatedAt":"2026-10-22"}],"nextPage":null,"incomplete":false}"""
                else -> error("Unexpected personal route")
            }
            RpcResponse(200, body, "application/json")
        })
        val entry = NativeEntryState(false)
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val workspace = IosGuestWorkspace(actions, settings(), RpcTransport { error("No public page was opened") },
            backgroundScope, assertNotNull(entry.guestSource()), navigation, IncomingLinkInbox(), { true }, store)
        try {
            val mount = assertNotNull(workspace.personalBrowse)
            actions.activity!!.changed(true); runCurrent()
            assertEquals(1, requests.size, "A verified profile alone starts no personal lists")
            val browse = assertNotNull(mount.session)
            mount.openAccount(); runCurrent()
            val sheet = assertNotNull(mount.presentation)
            assertFalse(sheet.repositoryConsent)
            mount.dismiss(sheet)
            mount.openPulls(browse)
            val route = navigation.stack.last() as Screen.PersonalPulls
            val rows = browse.pulls(route)
            rows.start(); runCurrent()
            assertTrue(rows.rows.single().repository.private)
            assertSame(Screen.Explore, navigation.stack.first())
            assertEquals(2, navigation.stack.size)
            actions.activity!!.changed(false)
            val count = requests.size
            mount.openRepositories(browse); rows.retry()
            assertEquals(count, requests.size)
            assertSame(route, navigation.stack.last())
            workspace.close()
            assertNull(mount.session)
            assertNull(mount.presentation)
            assertTrue(rows.rows.isEmpty())
            actions.activity!!.changed(true); mount.openPulls(browse)
            assertEquals(count, requests.size)
            assertEquals(listOf(Screen.Explore), navigation.stack.toList())
            assertTrue(requests.all { it.url.startsWith("https://accounts.example/api/personal/v1/") })
            assertTrue(requests.all { it.headers["Authorization"] == "Bearer pully_${"A".repeat(43)}" })
            assertTrue(requests.all { it.headers.keys.none { name -> name.startsWith("CF-Access", true) || name.equals("X-Pully-Session", true) } })
        } finally { workspace.close(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test
    fun anInactiveEventDuringSecureStorageProbeCannotResumeTheOlderActiveCallback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val actions = ActiveActions()
        var interrupt = true
        var requests = 0
        val store = IosPersonalSessionStore(object : NativePersonalSessionStorage {
            override fun read(): NativePersonalSessionRead {
                if (interrupt) { interrupt = false; actions.activity!!.changed(false) }
                return NativePersonalSessionRead("""{"version":1,"accepted":{"origin":"https://accounts.example","token":"pully_${"A".repeat(43)}","accountId":"account-42","githubId":42},"pending":[]}""".encodeToByteArray())
            }
            override fun write(contents: ByteArray) = error("No write")
        }, "https://accounts.example", RpcTransport {
            requests++
            RpcResponse(200, """{"accountId":"account-42","githubId":42,"login":"reader","authority":"publicRead","sessionExpiresAt":9999999999,"accessExpiresAt":null,"refreshAvailable":false}""", "application/json")
        })
        val entry = NativeEntryState(false)
        val workspace = IosGuestWorkspace(actions, settings(), RpcTransport { error("No public list was started") },
            backgroundScope, assertNotNull(entry.guestSource()), AppNavigation(rootScreen = Screen.Explore), IncomingLinkInbox(), { true }, store)
        try {
            actions.activity!!.changed(true); runCurrent()
            assertEquals(0, requests, "The secure probe's reentrant pause must win before restoration starts")
            actions.activity!!.changed(true); runCurrent()
            assertEquals(1, requests)
        } finally { workspace.close(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test
    fun aHostWithoutTheActualActivityPortCannotProbeStorageOrStartPersonalRequests() = runTest {
        val actions = Actions()
        var reads = 0
        var writes = 0
        var requests = 0
        val store = IosPersonalSessionStore(object : NativePersonalSessionStorage {
            override fun read(): NativePersonalSessionRead { reads++; error("Unsupported activity must not probe secure storage") }
            override fun write(contents: ByteArray) { writes++; error("No write") }
        }, "https://accounts.example", RpcTransport { requests++; error("No personal request") })
        val entry = NativeEntryState(false)
        val workspace = IosGuestWorkspace(actions, settings(), RpcTransport { error("No public request was started") },
            backgroundScope, assertNotNull(entry.guestSource()), AppNavigation(rootScreen = Screen.Explore), IncomingLinkInbox(), { true }, store)
        workspace.close(); runCurrent()
        assertEquals(1, actions.cancellations)
        assertEquals(0, reads)
        assertEquals(0, writes)
        assertEquals(0, requests)
    }

    @Test
    fun actualActiveEventsDriveThePublicRuntimeAndRetainedEventsAreInertAfterWorkspaceClose() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val requests = mutableListOf<RpcRequest>()
        var writes = 0
        val storage = object : NativePersonalSessionStorage {
            override fun read(): NativePersonalSessionRead = NativePersonalSessionRead("""{"version":1,"accepted":{"origin":"https://accounts.example","token":"pully_${"A".repeat(43)}","accountId":"account-42","githubId":42},"pending":[]}""".encodeToByteArray())
            override fun write(contents: ByteArray) { writes++; error("Ordinary foreground must not rewrite the accepted vault") }
        }
        val store = IosPersonalSessionStore(storage, "https://accounts.example", RpcTransport {
            requests += it
            RpcResponse(200, """{"accountId":"account-42","githubId":42,"login":"reader","authority":"publicRead","sessionExpiresAt":9999999999,"accessExpiresAt":null,"refreshAvailable":false}""", "application/json")
        })
        val actions = ActiveActions()
        val entry = NativeEntryState(false)
        val workspace = IosGuestWorkspace(actions, settings(), RpcTransport { error("No public list was started") },
            backgroundScope, assertNotNull(entry.guestSource()), AppNavigation(rootScreen = Screen.Explore), IncomingLinkInbox(), { true }, store)
        try {
            runCurrent()
            assertTrue(requests.isEmpty())
            actions.home!!.started(); runCurrent()
            assertTrue(requests.isEmpty(), "Home started is not the native active signal")
            val retained = assertNotNull(actions.activity)
            retained.changed(true); runCurrent()
            assertEquals(1, requests.size)
            retained.changed(false)
            actions.home!!.started(); runCurrent()
            assertEquals(1, requests.size)
            retained.changed(true); runCurrent()
            assertEquals(2, requests.size)
            assertEquals(0, writes)
            workspace.close()
            assertEquals(1, actions.activityCancellations)
            retained.changed(true); runCurrent()
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.url == "https://accounts.example/api/personal/v1/session" })
            assertTrue(requests.all { it.headers.keys.none { name -> name.startsWith("CF-Access", true) || name.equals("X-Pully-Session", true) } })
        } finally { workspace.close(); runCurrent(); Dispatchers.resetMain() }
    }

    private open class Actions : NativeHomeActions {
        var home: HomeLifecycleObserver? = null
        var cancellations = 0
        override fun copyText(text: String) = Unit
        override fun openOnGithub(url: String) = error("No browser action")
        override fun openInBrowser(url: String) = error("No browser action")
        override fun observeLifecycle(observer: HomeLifecycleObserver): HomeLifecycleSubscription {
            home = observer
            observer.stopped()
            return object : HomeLifecycleSubscription { override fun cancel() { cancellations++ } }
        }
        override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver): HomeLifecycleSubscription = error("No UI was composed")
    }

    private class ActiveActions : Actions(), NativePersonalForegroundActions {
        var activity: NativePersonalForegroundObserver? = null
        var activityCancellations = 0
        override fun observePersonalForeground(observer: NativePersonalForegroundObserver): HomeLifecycleSubscription {
            activity = observer
            observer.changed(false)
            return object : HomeLifecycleSubscription { override fun cancel() { activityCancellations++ } }
        }
    }

    private fun settings() = AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
        override fun touched(name: String) = Unit
        override fun settingsChanged(key: String, value: String) = Unit
        override fun usageStatsEnabled(enabled: Boolean) = Unit
    }) { "en" }
}
