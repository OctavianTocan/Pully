package com.octaviantocan.pulls.ui.personal

import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.PersonalAuthOrigin
import com.octaviantocan.pulls.data.PersonalAuthority
import com.octaviantocan.pulls.data.PersonalSessionBlobStorage
import com.octaviantocan.pulls.data.PersonalSessionVault
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.NativeWorkspace
import com.octaviantocan.pulls.ui.NativeWorkspaceOwner
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubAccountEntryTest {
    @Test
    fun unconfiguredEntryOpensAnExplanationWithoutStorageOrAnyAccountRequest() = runTest {
        val h = PublicWorkspace(this, configured = false)
        try {
            val retained = h.entryAction::open
            assertTrue(retained())
            assertEquals(GitHubAccountEntry.Unavailable.Configuration, assertNotNull(h.entryAction.explanation).reason)
            assertNull(h.mount.presentation)
            assertEquals(0, h.storage.reads)
            assertEquals(0, h.storage.writes)
            assertTrue(h.requests.isEmpty())
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
        } finally { h.close() }
    }

    @Test
    fun configuredEntryUsesTheActualNormalAccountOwnerAndPublicSignInFlow() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        val entry = GitHubAccountEntry({ true }, { true }, { h.runtime.configured },
            { h.runtime.verifyStorage().also(mount::availability) }, { mount })
        val browsers = mutableListOf<String>()
        mount.foreground(true, h.runtime.verifyStorage())
        try {
            assertTrue(entry.open())
            val presentation = assertNotNull(mount.presentation)
            assertFalse(presentation.repositoryConsent)
            assertNull(entry.explanation)
            assertTrue(h.requests.isEmpty(), "Opening the normal sheet cannot itself start OAuth")
            val owner = PersonalAccountSheetOwner(h.runtime.openSignIn(backgroundScope) { browsers += it }) { mount.dismiss(presentation) }
            try {
                assertNotNull(owner.signIn).controller.start()
                runCurrent()
                assertEquals(1, browsers.size)
                assertTrue(browsers.single().startsWith("https://github.com/login/oauth/authorize?"))
                assertEquals(PersonalAuthority.PublicRead, assertNotNull(h.runtime.view.value.account).authority)
                assertTrue(h.requests.none { it.url.endsWith("/repositories/start") })
                assertTrue(h.requests.all { it.url.startsWith("https://accounts.example/api/personal/v1/") })
                assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
            } finally { owner.close() }
        } finally { entry.close(); mount.close(); h.runtime.close() }
    }

    @Test
    fun aCachedSuccessfulVaultCannotHideANewStorageFailureOrStartAuth() = runTest {
        val h = PublicWorkspace(this, configured = true)
        try {
            h.runtime.restore(); runCurrent()
            assertTrue(h.storage.reads > 0)
            h.storage.unavailable = true
            assertTrue(h.entryAction.open())
            assertEquals(GitHubAccountEntry.Unavailable.Device, assertNotNull(h.entryAction.explanation).reason)
            assertNull(h.mount.presentation)
            assertTrue(h.requests.isEmpty())
            assertEquals(0, h.storage.writes)
            h.storage.unavailable = false
            assertTrue(h.entryAction.open())
            assertNull(h.entryAction.explanation)
            assertFalse(assertNotNull(h.mount.presentation).repositoryConsent)
            assertTrue(h.requests.isEmpty())
        } finally { h.close() }
    }

    @Test
    fun retainedEntryIsInertOnPauseSourceAbaAndDisposal() = runTest {
        val h = PublicWorkspace(this, configured = true)
        val retained = h.entryAction::open
        try {
            h.foreground = false
            assertFalse(retained())
            assertNull(h.entryAction.explanation)
            assertNull(h.mount.presentation)
            h.foreground = true
            h.entry.select(NativeEntryMode.ExistingServer)
            h.entry.select(NativeEntryMode.Explore)
            assertFalse(retained())
            assertNull(h.mount.presentation)
            assertTrue(h.requests.isEmpty())
            h.entryAction.close()
            assertFalse(retained())
            assertEquals(0, h.storage.writes)
        } finally { h.close() }
        val disposed = PublicWorkspace(this, configured = true)
        try {
            val retainedDisposed = disposed.entryAction::open
            disposed.entryAction.close()
            assertFalse(retainedDisposed())
            assertNull(disposed.mount.presentation)
            assertTrue(disposed.requests.isEmpty())
        } finally { disposed.close() }
    }

    @Test
    fun aPresentEmptyRecordIsNotMissingAndCanBeRetriedWithoutOverwrite() = runTest {
        val h = PublicWorkspace(this, configured = true)
        try {
            h.storage.bytes = byteArrayOf()
            assertTrue(h.entryAction.open())
            assertEquals(GitHubAccountEntry.Unavailable.Device, assertNotNull(h.entryAction.explanation).reason)
            assertEquals(0, assertNotNull(h.storage.bytes).size)
            assertNull(h.mount.presentation)
            assertEquals(0, h.storage.writes)
            h.storage.bytes = null
            assertTrue(h.entryAction.open())
            assertNotNull(h.mount.presentation)
            assertTrue(h.requests.isEmpty())
        } finally { h.close() }
    }

    @Test
    fun aCachedVaultCannotHideFreshCorruptBytesAndProbeCopiesAreCleared() = runTest {
        val h = PublicWorkspace(this, configured = true)
        try {
            h.runtime.restore(); runCurrent()
            h.storage.bytes = "not-a-vault".encodeToByteArray()
            assertTrue(h.entryAction.open())
            assertEquals(GitHubAccountEntry.Unavailable.Device, assertNotNull(h.entryAction.explanation).reason)
            assertNull(h.mount.presentation)
            assertEquals("not-a-vault", assertNotNull(h.storage.bytes).decodeToString())
            assertTrue(assertNotNull(h.storage.lastRead).all { it == 0.toByte() })
            assertEquals(0, h.storage.writes)
            assertTrue(h.requests.isEmpty())
        } finally { h.close() }
    }

    @Test
    fun sourceRetirementDuringTheActualProbeCannotPublishAnOldExplanationOrSheet() = runTest {
        val h = PublicWorkspace(this, configured = true)
        try {
            h.storage.onRead = { h.entry.renewExplore() }
            assertFalse(h.entryAction.open())
            assertNull(h.entryAction.explanation)
            assertNull(h.mount.presentation)
            assertTrue(h.requests.isEmpty())
            assertEquals(0, h.storage.writes)
        } finally { h.close() }
    }

    @Test
    fun settingsIntentRetiresTheActualWorkspaceBeforeItsNewPublicOwnerOpens() = runTest {
        val entry = NativeEntryState(true)
        val navigation = AppNavigation(rootScreen = Screen.Home)
        navigation.stack.add(Screen.Repo("private-owner", "private-repository"))
        navigation.stack.add(Screen.Settings)
        val request = GitHubAccountRequest(entry)
        val events = mutableListOf<String>()
        var settingsCurrent: () -> Boolean = { false }
        var guest: PublicWorkspace? = null
        val owner = NativeWorkspaceOwner(entry, navigation,
            createExisting = { _, current ->
                settingsCurrent = current
                object : NativeWorkspace {
                    override fun beforeLeave() { events += "private-settled" }
                    override fun close() { events += "private-closed" }
                }
            },
            createGuest = { source, stack, _ ->
                events += "public-created"
                PublicWorkspace(this, false, entry, source, stack).also { guest = it; it.foreground = false }
            }, selected = {},
        )
        val openingSettings = settingsCurrent
        val retainedSettings = { request.request(openingSettings, { true }) { owner.select(NativeEntryMode.Explore) } }
        try {
            retainedSettings()
            assertEquals(listOf("private-settled", "private-closed", "public-created"), events)
            assertEquals(listOf(Screen.Explore), navigation.stack.toList())
            val public = assertNotNull(guest)
            assertSame(navigation, public.navigation)
            request.deliver(public.entryAction)
            assertTrue(request.hasPending)
            assertNull(public.entryAction.explanation)
            public.foreground = true
            request.deliver(public.entryAction)
            assertFalse(request.hasPending)
            assertEquals(GitHubAccountEntry.Unavailable.Configuration, assertNotNull(public.entryAction.explanation).reason)
            public.entryAction.dismiss(assertNotNull(public.entryAction.explanation))
            retainedSettings()
            request.deliver(public.entryAction)
            assertFalse(request.hasPending)
            assertNull(public.entryAction.explanation)
            assertTrue(public.requests.isEmpty())
            assertEquals(0, public.storage.reads)
        } finally { owner.close() }
    }

    @Test
    fun aPendingSettingsIntentCannotReplayIntoAnotherPublicGeneration() = runTest {
        val h = PublicWorkspace(this, configured = false)
        val request = GitHubAccountRequest(h.entry)
        try {
            request.request({ true }, { false }) { error("An inactive Settings callback cannot switch entry") }
            assertFalse(request.hasPending)
            request.request({ true }, { true }) {}
            assertTrue(request.hasPending)
            h.entry.renewExplore()
            request.deliver(h.entryAction)
            assertFalse(request.hasPending)
            assertNull(h.entryAction.explanation)
            assertTrue(h.requests.isEmpty())
        } finally { h.close() }
    }

    @Test
    fun aDisposedSettingsActionCannotRequestAgainOnTheSameCurrentAccount() = runTest {
        val h = PublicWorkspace(this, configured = false)
        val request = GitHubAccountRequest(h.entry)
        val action = GitHubAccountSettingsAction({ true }) { request.request({ true }, { true }) {} }
        val retained = action::open
        try {
            action.close()
            retained()
            assertFalse(request.hasPending)
            val replacement = GitHubAccountSettingsAction({ true }) { request.request({ true }, { true }) {} }
            replacement.open()
            assertTrue(request.hasPending)
            request.deliver(h.entryAction)
            assertEquals(GitHubAccountEntry.Unavailable.Configuration, assertNotNull(h.entryAction.explanation).reason)
            assertTrue(h.requests.isEmpty())
            replacement.close()
        } finally { action.close(); h.close() }
    }

    private class PublicWorkspace(
        scope: TestScope,
        configured: Boolean,
        val entry: NativeEntryState = NativeEntryState(false),
        source: NativeEntryState.GuestSource = requireNotNull(entry.guestSource()),
        val navigation: AppNavigation = AppNavigation(rootScreen = Screen.Explore),
    ) : NativeWorkspace {
        class Storage : PersonalSessionBlobStorage {
            var reads = 0
            var writes = 0
            var unavailable = false
            var bytes: ByteArray? = null
            var lastRead: ByteArray? = null
            var onRead: (() -> Unit)? = null
            override fun read(): ByteArray? { reads++; onRead?.invoke(); check(!unavailable); return bytes?.copyOf()?.also { lastRead = it } }
            override fun write(contents: ByteArray) { writes++; error("Entry cannot write a session") }
        }
        val storage = Storage()
        val requests = mutableListOf<RpcRequest>()
        var foreground = true
        private var closed = false
        val runtime = PersonalAccountRuntime(
            if (configured) PersonalAuthOrigin.parse("https://accounts.example") else null,
            PersonalSessionVault(storage), scope.backgroundScope, scope.backgroundScope,
            { 1200 }, { !closed && source.current }, {}, RpcTransport { requests += it; error("Entry did not start sign-in") },
        )
        val mount = PersonalBrowseMount(runtime, scope.backgroundScope, source, navigation)
        val entryAction = GitHubAccountEntry(
            { !closed && source.current }, { foreground }, { runtime.configured },
            { runtime.verifyStorage().also(mount::availability) }, { mount },
        )
        init { mount.foreground(true, runtime.verifyStorage()) }
        override fun close() {
            if (closed) return
            closed = true
            entryAction.close(); mount.close(); runtime.close()
        }
    }
}
