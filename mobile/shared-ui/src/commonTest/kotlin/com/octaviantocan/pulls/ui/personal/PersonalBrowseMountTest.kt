package com.octaviantocan.pulls.ui.personal

import com.octaviantocan.pulls.data.PersonalAuthority
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import kotlinx.coroutines.CompletableDeferred
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
class PersonalBrowseMountTest {
    @Test
    fun combinedSignInOpensTheConfirmedAccountsHomeWithRepositoryNavigation() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        val held = CompletableDeferred<Unit>()
        h.onClaim = { response -> held.await(); response }
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        try {
            mount.foreground(true)
            h.runtime.openConnect(backgroundScope) {}!!.controller.start(); runCurrent()
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
            assertNull(mount.session)
            held.complete(Unit); runCurrent()
            val route = h.navigation.stack.last() as Screen.PersonalPulls
            assertTrue(route.home)
            assertSame(route, h.navigation.stack.single())
            val browse = assertNotNull(mount.session)
            assertTrue(browse.privateEnabled)
            assertNull(mount.presentation)
            browse.pulls(route).start(); runCurrent()
            assertTrue(browse.pulls(route).rows.single().repository.private)
            mount.openRepositories(browse)
            val repositories = h.navigation.stack.last() as Screen.PersonalRepositories
            browse.repositories(repositories).start(); runCurrent()
            assertTrue(browse.repositories(repositories).rows.single().private)
            assertTrue(h.reads().filterNot { it.url.endsWith("/capabilities") }.all { "/github/account/" in it.url })
            assertEquals(1, h.requests.count { it.url.endsWith("/start") })
            h.navigation.nav.back(); mount.keepRoutes()
            mount.foreground(false); mount.foreground(true); runCurrent()
            assertSame(route, h.navigation.stack.single(), "Repositories return to the same Home")
            assertEquals(1, h.requests.count { it.url.endsWith("/start") })
        } finally { mount.close(); h.runtime.close() }
    }

    @Test
    fun restoredRepositoryAccountWaitsForForegroundAdmissionAndPreservesAnOpenDeepLink() = runTest {
        for (deepLink in listOf(false, true)) {
            val h = PersonalBrowseSessionTest.Harness(this)
            h.verify(private = true)
            val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
            try {
                if (deepLink) h.navigation.stack.add(Screen.PersonalPulls())
                val before = h.navigation.stack.toList()
                mount.foreground(true, available = false); runCurrent()
                assertEquals(before, h.navigation.stack.toList())
                mount.availability(true); runCurrent()
                if (deepLink) assertEquals(before, h.navigation.stack.toList())
                else assertTrue((h.navigation.stack.last() as Screen.PersonalPulls).home)
            } finally { mount.close(); h.runtime.close() }
        }
    }

    @Test
    fun anonymousMountHasNoReaderAndAnOlderPublicProfileIsOfferedCompletion() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        try {
            mount.foreground(true)
            assertNull(mount.session)
            assertTrue(h.requests.isEmpty())
            mount.openAccount()
            val opening = assertNotNull(mount.presentation)
            assertFalse(opening.repositoryConsent)
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
            mount.dismiss(opening)
            h.verify()
            val browse = assertNotNull(mount.session)
            assertTrue(browse.current)
            assertTrue(h.reads().all { it.url.endsWith("/github/account/capabilities") })
            assertFalse(assertNotNull(mount.presentation).repositoryConsent)
            assertTrue(h.requests.none { it.url.endsWith("/repositories/start") }, "Finishing connection still needs the person's action")
            mount.openAccount(); runCurrent()
            assertTrue(assertNotNull(browse.capabilities).consentAvailable)
            assertTrue(h.reads().all { it.url.endsWith("/github/account/capabilities") })
            val account = assertNotNull(mount.presentation)
            mount.dismiss(account)
            mount.openRepositories(browse)
            val route = h.navigation.stack.last() as Screen.PersonalRepositories
            browse.repositories(route).start(); runCurrent()
            assertFalse(browse.repositories(route).rows.single().private)
            assertFalse(browse.privateEnabled)
            assertEquals(2, h.navigation.stack.size)
        } finally { mount.close(); h.runtime.close() }
    }

    @Test
    fun theSharedAccountActionsUseOneStackAndKeepPrivateConsentExplicit() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        h.verify()
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        try {
            mount.foreground(true); mount.openAccount(); runCurrent()
            val browse = assertNotNull(mount.session)
            val normal = assertNotNull(mount.presentation)
            assertFalse(normal.repositoryConsent)
            mount.dismiss(normal)
            mount.openPulls(browse)
            val route = h.navigation.stack.last() as Screen.PersonalPulls
            browse.pulls(route).start(); runCurrent()
            assertSame(Screen.Explore, h.navigation.stack.first())
            assertEquals(PersonalAuthority.PublicRead, h.runtime.view.value.account!!.authority)
            assertTrue(h.requests.none { it.url.endsWith("/repositories/start") })
            mount.openRepositoryConsent(browse)
            val consent = assertNotNull(mount.presentation)
            assertTrue(consent.repositoryConsent)
            mount.dismiss(normal)
            assertSame(consent, mount.presentation, "An old sheet close cannot dismiss its replacement")
            mount.dismiss(consent)
            assertSame(route, h.navigation.stack.last())
            assertFalse(browse.privateEnabled)
        } finally { mount.close(); h.runtime.close() }
    }

    @Test
    fun actualLifecyclePortPausesAReadySignInBeforeAnyClaimOrUiFrame() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        val ready = CompletableDeferred<RpcResponse>()
        h.onStatus = { ready.await() }
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        try {
            mount.foreground(true)
            val signIn = h.runtime.openSignIn(backgroundScope) {}!!
            signIn.controller.start(); runCurrent()
            assertTrue(h.requests.any { it.url.endsWith("/status") })
            mount.foreground(false)
            ready.complete(h.json("""{"phase":"ready"}""")); runCurrent()
            assertTrue(h.requests.none { it.url.endsWith("/claim") })
            assertNull(h.runtime.view.value.account)
            mount.foreground(true); runCurrent()
            assertEquals(1, h.requests.count { it.url.endsWith("/claim") })
            assertEquals(PersonalAuthority.PublicRead, h.runtime.view.value.account!!.authority)
        } finally { mount.close(); h.runtime.close() }
    }

    @Test
    fun delayedPrivateConsentOpensTheBrowserOnceOnlyAfterTheNativeProbeAdmitsTheResume() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        h.verify()
        val start = CompletableDeferred<Unit>()
        h.onStart = { response -> start.await(); response }
        h.onStatus = { h.json("""{"phase":"pending"}""") }
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        var nativeForeground = true
        var nativeAvailable = true
        val opened = mutableListOf<String>()
        try {
            mount.foreground(true); mount.openAccount(); runCurrent()
            val browse = assertNotNull(mount.session)
            mount.dismiss(assertNotNull(mount.presentation))
            mount.openRepositoryConsent(browse)
            assertTrue(assertNotNull(mount.presentation).repositoryConsent)
            val signIn = assertNotNull(h.runtime.openRepositoryConsent(backgroundScope) { url ->
                if (nativeForeground && nativeAvailable) {
                    opened += url
                    nativeForeground = false
                    nativeAvailable = false
                    mount.foreground(false)
                }
            })
            assertTrue(signIn.controller.start()); runCurrent()
            assertEquals(1, h.requests.count { it.url.endsWith("/repositories/start") })
            val before = h.requests.size
            mount.foreground(false)
            nativeForeground = false
            nativeAvailable = false
            start.complete(Unit); runCurrent()
            assertTrue(opened.isEmpty())

            mount.foreground(true, available = false)
            nativeForeground = true
            runCurrent()
            assertTrue(opened.isEmpty())
            assertTrue(h.requests.drop(before).none { it.url.endsWith("/status") || it.url.endsWith("/claim") },
                "An active scene without an admitted secure probe cannot resume the consent controller")
            mount.availability(false); runCurrent()
            assertTrue(opened.isEmpty())

            mount.foreground(false)
            nativeForeground = false
            mount.availability(true); runCurrent()
            assertTrue(opened.isEmpty(), "A late probe cannot admit an inactive scene")
            mount.foreground(true, available = false)
            nativeForeground = true
            nativeAvailable = true
            mount.availability(true); runCurrent()
            assertEquals(1, opened.size, "The retained consent must still open GitHub after a successful probe")
            assertTrue(opened.single().contains("scope=repo+offline_access"))
            assertEquals(before, h.requests.size, "Synchronous browser inactivity must win before polling")

            mount.foreground(true, available = false)
            nativeForeground = true
            nativeAvailable = true
            mount.availability(true); runCurrent()
            assertEquals(1, opened.size)
            assertEquals(1, h.requests.drop(before).count { it.url.endsWith("/status") })
            assertEquals(PersonalAuthority.PublicRead, h.runtime.view.value.account!!.authority)
        } finally { mount.close(); h.runtime.close() }
    }

    @Test
    fun aPrivateConsentClaimAlreadyEnteredStillConfirmsDuringAnUnadmittedNativeResume() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        h.verify()
        val beforeConsent = h.requests.size
        val claim = CompletableDeferred<Unit>()
        h.onClaim = { response -> claim.await(); response }
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        var opened = 0
        try {
            mount.foreground(true)
            val completion = assertNotNull(mount.presentation)
            val signIn = assertNotNull(h.runtime.openRepositoryConsent(backgroundScope) { opened++ })
            assertTrue(signIn.controller.start()); runCurrent()
            assertEquals(1, h.requests.drop(beforeConsent).count { it.url.endsWith("/claim") })
            assertEquals(PersonalAuthority.PublicRead, h.runtime.view.value.account!!.authority)
            mount.foreground(false)
            mount.foreground(true, available = false)
            claim.complete(Unit); runCurrent()
            assertEquals(PersonalAuthority.PrivateRepositories, h.runtime.view.value.account!!.authority)
            assertEquals(1, opened)
            assertEquals(1, h.requests.drop(beforeConsent).count { it.url.endsWith("/claim") })
            assertSame(completion, mount.presentation)
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
            mount.availability(true)
            assertNull(mount.presentation)
            assertTrue((h.navigation.stack.last() as Screen.PersonalPulls).home)
        } finally { mount.close(); h.runtime.close() }
    }

    @Test
    fun nativeSecureProbeGatesPresentationAndReadsUntilTheSameActiveSourceIsAvailable() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        h.verify(private = true)
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        try {
            val browse = assertNotNull(mount.session)
            mount.foreground(true, available = false)
            mount.openAccount(); mount.openPulls(browse)
            assertNull(mount.presentation)
            assertTrue(h.reads().isEmpty())
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
            mount.availability(true)
            mount.openPulls(browse)
            val route = h.navigation.stack.last() as Screen.PersonalPulls
            browse.pulls(route).start(); runCurrent()
            assertTrue(browse.pulls(route).rows.single().repository.private)
            mount.foreground(false)
            mount.availability(true)
            val count = h.reads().size
            mount.openRepositories(browse); browse.pulls(route).retry()
            assertEquals(count, h.reads().size)
            assertSame(route, h.navigation.stack.last())
        } finally { mount.close(); h.runtime.close() }
    }

    @Test
    fun accountRejectionPurgesBeforeCleanupAndDefersOnlyNormalRecoveryWhileInactive() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        h.verify(private = true)
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        try {
            mount.foreground(true)
            val browse = assertNotNull(mount.session)
            mount.openPulls(browse)
            val route = h.navigation.stack.last() as Screen.PersonalPulls
            val state = browse.pulls(route)
            state.start(); runCurrent()
            assertTrue(state.rows.single().repository.private)
            h.onWrite = {
                assertTrue(state.rows.isEmpty())
                assertFalse(browse.current)
                assertNull(h.runtime.personalReads())
                mount.foreground(false)
            }
            h.onRead = { request ->
                if (request.url.endsWith("/capabilities")) h.defaultRead(request) else
                    RpcResponse(401, """{"error":{"code":"reconnect","message":"Reconnect","retryAfterSeconds":null}}""", "application/json")
            }
            state.foreground(); runCurrent()
            assertNull(mount.session)
            assertNull(mount.presentation)
            assertNull(h.runtime.view.value.account)
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
            mount.foreground(true)
            assertFalse(assertNotNull(mount.presentation).repositoryConsent)
            val count = h.reads().size
            mount.openPulls(browse); state.retry()
            assertEquals(count, h.reads().size)
        } finally { h.onWrite = null; mount.close(); h.runtime.close() }
    }

    @Test
    fun sourceRetirementClearsTheSheetReadersAndRetainedActionsSynchronously() = runTest {
        val h = PersonalBrowseSessionTest.Harness(this)
        h.verify(private = true)
        val mount = PersonalBrowseMount(h.runtime, backgroundScope, h.entry.guestSource()!!, h.navigation)
        try {
            mount.foreground(true); mount.openAccount(); runCurrent()
            val opening = assertNotNull(mount.presentation)
            val browse = assertNotNull(mount.session)
            h.entry.renewExplore()
            assertFalse(mount.current)
            assertNull(mount.presentation)
            assertNull(mount.session)
            assertFalse(browse.current)
            val count = h.requests.size
            mount.dismiss(opening); mount.openPulls(browse); mount.openAccount(); mount.foreground(true)
            assertEquals(count, h.requests.size)
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
            assertEquals(NativeEntryMode.Explore, h.entry.mode)
        } finally { mount.close(); h.runtime.close() }
    }
}
