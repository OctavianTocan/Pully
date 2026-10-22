package com.octaviantocan.pulls.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalAccountRuntimeTest {
    @Test
    fun anUnconfiguredBuildRemainsPublicAndDoesNotProbeTheLegacyServer() = runTest {
        val h = Harness(this, configured = false)
        h.runtime.restore()
        assertNull(h.runtime.openSignIn(backgroundScope) { error("No browser") })
        runCurrent()
        assertFalse(h.runtime.configured)
        assertTrue(h.network.requests.isEmpty())
        assertNull(h.runtime.view.value.account)
        h.runtime.close()
    }

    @Test
    fun restartRevokesPendingClaimsWithoutEverConfirmingOrPublishingThem() = runTest {
        val h = Harness(this)
        h.vault.savePending(PersonalSessionVaultTest.session(3))
        h.runtime.restore()
        runCurrent()
        assertEquals(1, h.network.requests.size)
        assertTrue(h.network.requests.single().url.endsWith("/sign-out"))
        assertTrue(h.vault.pending().isEmpty())
        assertNull(h.runtime.view.value.account)
        assertEquals(0, h.identityChanges)
        h.runtime.close()
    }

    @Test
    fun acceptedRestorationNeedsFreshMatchingImmutableIdentityBeforeTheProfileAppears() = runTest {
        val h = Harness(this)
        val session = PersonalSessionVaultTest.session(3)
        h.vault.savePending(session); h.vault.promote(session)
        val held = CompletableDeferred<Unit>()
        val original = h.network.respond
        h.network.respond = { request -> if (request.url.endsWith("/session")) held.await(); original(request) }
        h.runtime.restore()
        runCurrent()
        assertTrue(h.runtime.view.value.checking)
        assertNull(h.runtime.view.value.account)
        held.complete(Unit)
        runCurrent()
        assertEquals(42L, h.runtime.view.value.account!!.githubId)
        assertEquals("Bearer ${session.token}", h.network.requests.single().headers["Authorization"])
        h.runtime.close()
    }

    @Test
    fun htmlDoesNotDeleteASavedAccountButCannotVerifyItsProfile() = runTest {
        val h = Harness(this)
        val session = PersonalSessionVaultTest.session(3)
        h.vault.savePending(session); h.vault.promote(session)
        h.network.respond = { RpcResponse(401, "<html>private-proxy", "text/html") }
        h.runtime.restore(); runCurrent()
        assertEquals(PersonalAuthProblem.WebPage, h.runtime.view.value.problem)
        assertTrue(h.runtime.view.value.saved)
        assertNull(h.runtime.view.value.account)
        assertEquals(session.token, h.vault.accepted(PersonalSessionVaultTest.origin)!!.token)
        h.runtime.close()
    }

    @Test
    fun replacingTheSourceWhileRestorationIsHeldCannotExposeTheOldProfile() = runTest {
        val h = Harness(this)
        val session = PersonalSessionVaultTest.session(3)
        h.vault.savePending(session); h.vault.promote(session)
        val held = CompletableDeferred<Unit>()
        val original = h.network.respond
        h.network.respond = { withContext(NonCancellable) { held.await() }; original(it) }
        h.runtime.restore(); runCurrent()
        h.current = false; h.runtime.close()
        held.complete(Unit); runCurrent()
        assertNull(h.runtime.view.value.account)
        assertEquals(session.token, h.vault.accepted(PersonalSessionVaultTest.origin)!!.token)
    }

    @Test
    fun aTemporaryForegroundFailureKeepsAnAlreadyVerifiedProfileAndItsCredential() = runTest {
        val h = Harness(this)
        val session = PersonalSessionVaultTest.session(3)
        h.vault.savePending(session); h.vault.promote(session)
        h.runtime.restore(); runCurrent()
        assertEquals(42L, h.runtime.view.value.account!!.githubId)
        h.network.respond = { RpcResponse(401, "<html>proxy", "text/html") }
        h.runtime.restore(); runCurrent()
        assertEquals(PersonalAuthProblem.WebPage, h.runtime.view.value.problem)
        assertEquals(42L, h.runtime.view.value.account!!.githubId)
        assertEquals(session.token, h.vault.accepted(PersonalSessionVaultTest.origin)!!.token)
        h.runtime.close()
    }

    @Test
    fun signOutRetiresPublicAuthorityBeforeAnUncertainRevocationAndRetriesAfterRestart() = runTest {
        val h = Harness(this)
        val session = PersonalSessionVaultTest.session(3)
        h.vault.savePending(session); h.vault.promote(session)
        val original = h.network.respond
        h.network.respond = { request -> if (request.url.endsWith("/sign-out")) error("offline") else original(request) }
        h.runtime.restore(); runCurrent()
        h.runtime.signOut(); runCurrent()
        assertEquals(1, h.identityChanges)
        assertNull(h.runtime.view.value.account)
        assertNull(h.vault.accepted(PersonalSessionVaultTest.origin))
        assertEquals(session.token, PersonalSessionVault(h.storage).pending().single().token)
        h.runtime.close()
        h.network.respond = original
        val reopened = h.makeRuntime(PersonalSessionVault(h.storage))
        reopened.restore(); runCurrent()
        assertTrue(PersonalSessionVault(h.storage).pending().isEmpty())
        assertNull(reopened.view.value.account)
        reopened.close()
    }

    @Test
    fun successfulSignInPersistsThenRetiresTheOldPublicSourceWithoutProofCancellation() = runTest {
        val h = Harness(this)
        h.onIdentity = {
            assertEquals(h.network.session, h.vault.accepted(PersonalSessionVaultTest.origin)!!.token)
            h.current = false
            h.runtime.close()
        }
        val signIn = h.runtime.openSignIn(backgroundScope) { }
        signIn!!.controller.start(); runCurrent()
        assertEquals(1, h.identityChanges)
        assertEquals(0, h.network.requests.count { it.url.endsWith("/cancel") })
        assertEquals(0, h.network.requests.count { it.url.endsWith("/sign-out") })
        assertTrue(h.vault.pending().isEmpty())
        assertEquals(h.network.session, h.vault.accepted(PersonalSessionVaultTest.origin)!!.token)
    }

    private class Harness(private val scope: TestScope, configured: Boolean = true) {
        val network = PersonalAuthClientTest.Fixture()
        val storage = PersonalSessionVaultTest.MemoryBlob()
        val vault = PersonalSessionVault(storage)
        var current = true
        var identityChanges = 0
        var onIdentity: () -> Unit = {}
        private val origin = PersonalSessionVaultTest.origin.takeIf { configured }
        val runtime = makeRuntime(vault)
        fun makeRuntime(store: PersonalSessionVault) = PersonalAccountRuntime(origin, store, scope.backgroundScope,
            scope.backgroundScope, { 1200 + scope.testScheduler.currentTime / 1000 }, { current },
            { identityChanges++; onIdentity() }, RpcTransport { network.requests += it; network.respond(it) })
    }
}
