package com.octaviantocan.pulls.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalAccountForegroundTest {
    @Test
    fun aNativePauseBeforeTheNextFramePreventsAHeldReadyReplyFromStartingAClaim() = runTest {
        val h = Harness(this)
        val ready = CompletableDeferred<Unit>()
        val original = h.network.respond
        h.network.respond = { request ->
            if (request.url.endsWith("/status")) withContext(NonCancellable) { ready.await() }
            original(request)
        }
        val signIn = h.open()
        signIn.controller.start(); runCurrent()
        assertEquals(1, h.count("/status"))

        // This is the port called synchronously by ON_PAUSE, without a composition/disposal frame.
        h.runtime.foreground(false)
        ready.complete(Unit); runCurrent()
        assertEquals(0, h.count("/claim"))
        assertEquals(0, h.identityChanges)
        assertNull(h.vault.accepted(PersonalSessionVaultTest.origin))

        h.runtime.foreground(true); runCurrent()
        assertEquals(1, h.count("/start"), "Returning from the browser retains the same attempt")
        assertEquals(1, h.browserOpens)
        assertEquals(1, h.count("/claim"))
        assertEquals(h.network.session, h.vault.accepted(PersonalSessionVaultTest.origin)!!.token)
        assertEquals(1, h.identityChanges)
        h.runtime.close()
    }

    @Test
    fun aNewSheetInheritsTheNativePauseBeforeItCanOpenTheBrowserOrPoll() = runTest {
        val h = Harness(this)
        h.runtime.foreground(false)
        val signIn = h.open()
        signIn.controller.start(); runCurrent()
        assertTrue(signIn.controller.state.value is PersonalSignInState.Waiting)
        assertEquals(1, h.count("/start"))
        assertEquals(0, h.browserOpens)
        assertEquals(0, h.count("/status"))
        assertEquals(0, h.count("/claim"))

        h.runtime.foreground(true); runCurrent()
        assertEquals(1, h.browserOpens)
        assertEquals(1, h.count("/start"))
        assertEquals(1, h.identityChanges)
        h.runtime.close()
    }

    @Test
    fun anAlreadyStartedOneUseClaimCanFinishAcrossTheNormalNativePause() = runTest {
        val h = Harness(this)
        val claim = CompletableDeferred<Unit>()
        val original = h.network.respond
        h.network.respond = { request ->
            if (request.url.endsWith("/claim")) withContext(NonCancellable) { claim.await() }
            original(request)
        }
        h.open().controller.start(); runCurrent()
        assertEquals(1, h.count("/claim"))
        assertEquals(0, h.count("/session"))

        h.runtime.foreground(false)
        claim.complete(Unit); runCurrent()
        assertEquals(1, h.count("/session"))
        assertEquals(1, h.identityChanges)
        assertEquals(h.network.session, h.vault.accepted(PersonalSessionVaultTest.origin)!!.token)
        assertEquals(0, h.count("/cancel"))
        assertEquals(0, h.count("/sign-out"))
        h.runtime.close()
    }

    private class Harness(private val scope: TestScope) {
        val network = PersonalAuthClientTest.Fixture()
        val vault = PersonalSessionVault(PersonalSessionVaultTest.MemoryBlob())
        var identityChanges = 0
        var browserOpens = 0
        val runtime = PersonalAccountRuntime(PersonalSessionVaultTest.origin, vault, scope.backgroundScope,
            scope.backgroundScope, { 1200 + scope.testScheduler.currentTime / 1000 }, { true },
            { identityChanges++ }, RpcTransport { network.requests += it; network.respond(it) })

        fun open() = runtime.openSignIn(scope.backgroundScope) { browserOpens++ }!!
        fun count(suffix: String) = network.requests.count { it.url.endsWith(suffix) }
    }
}
