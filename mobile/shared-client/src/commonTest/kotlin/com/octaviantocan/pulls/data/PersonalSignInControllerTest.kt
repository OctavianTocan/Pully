package com.octaviantocan.pulls.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalSignInControllerTest {
    @Test
    fun browserBackgroundRetainsProofAndSuccessfulClaimIsConsumedBeforeSourcePublication() = runTest {
        val h = Harness(this)
        val original = h.network.respond
        h.network.respond = { request ->
            if (request.url.endsWith("/session")) {
                assertTrue(h.staged.containsKey(h.network.session), "Secure stage must precede delivery acknowledgement")
                assertTrue(h.accepted.isEmpty())
            }
            original(request)
        }
        h.onAccept = { session -> h.accepted += session; h.staged.remove(session.token); h.network.generation++; h.controller.close() }
        assertTrue(h.controller.start())
        runCurrent()
        assertEquals(1, h.browser.size)
        assertEquals(2, h.network.requests.size)
        assertIs<PersonalSignInState.Waiting>(h.controller.state.value)
        assertFalse(h.controller.state.value.toString().contains(h.network.proof.secret))
        h.controller.foreground(true)
        runCurrent()
        assertEquals(1, h.accepted.size)
        assertEquals(3_600L, h.accepted.single().status.sessionExpiresAt)
        assertTrue(h.staged.isEmpty())
        assertEquals(0, h.count("/cancel"), "Successful source retirement must not revoke its consumed claim")
        assertEquals(1, h.count("/start"))
        assertFalse(h.controller.start(), "A retired callback cannot start another sign-in")
    }

    @Test
    fun disabledDiscoveryNeverStartsOAuthOrOpensABrowser() = runTest {
        val h = Harness(this)
        h.network.respond = { PersonalAuthClientTest.json("""{"github":{"enabled":false}}""") }
        h.controller.start()
        runCurrent()
        assertEquals(PersonalSignInState.Failed(PersonalAuthProblem.NotConfigured), h.controller.state.value)
        assertTrue(h.browser.isEmpty())
        assertEquals(0, h.count("/start"))
        assertTrue(h.accepted.isEmpty())
        h.close()
    }

    @Test
    fun aDeviceCodeIsShownAndGithubOpensOnlyWhenThePersonAsks() = runTest {
        val h = Harness(this)
        val original = h.network.respond
        var statuses = 0
        h.network.respond = { request -> when {
            request.url.endsWith("/start") -> h.network.startReply(GITHUB_DEVICE_URL, "WDJB-MJHT")
            request.url.endsWith("/status") && ++statuses < 3 -> PersonalAuthClientTest.json("""{"phase":"pending"}""")
            else -> original(request)
        } }
        h.controller.start()
        runCurrent()
        assertTrue(h.browser.isEmpty(), "The code is shown before GitHub opens")
        assertEquals(PersonalSignInState.Waiting(1800, userCode = "WDJB-MJHT"), h.controller.state.value)
        assertEquals(1, h.count("/status"), "The server is polled while the person copies the code")
        assertTrue(h.controller.openGitHub())
        assertEquals(listOf("https://github.com/login/device"), h.browser)
        h.controller.foreground(true)
        runCurrent()
        advanceTimeBy(4_100)
        runCurrent()
        assertEquals(1, h.accepted.size)
        assertEquals(1, h.browser.size)
        h.close()
    }

    @Test
    fun aStartThatFinishesInBackgroundWaitsBeforeOpeningTheBrowser() = runTest {
        val h = Harness(this)
        h.controller.foreground(false)
        h.controller.start()
        runCurrent()
        assertTrue(h.browser.isEmpty())
        assertIs<PersonalSignInState.Waiting>(h.controller.state.value)
        h.controller.foreground(true)
        runCurrent()
        assertEquals(1, h.browser.size)
        assertTrue(h.accepted.isEmpty())
        h.close()
    }

    @Test
    fun aLatePausedStatusCannotClaimAndResumingKeepsTheSameAttempt() = runTest {
        val h = Harness(this)
        h.onOpen = {}
        val original = h.network.respond
        val held = CompletableDeferred<Unit>()
        var statuses = 0
        h.network.respond = { request ->
            if (request.url.endsWith("/status") && ++statuses == 1)
                withContext(NonCancellable) { held.await() }
            original(request)
        }
        h.controller.start()
        runCurrent()
        assertEquals(1, h.count("/status"))
        h.controller.foreground(false)
        held.complete(Unit)
        runCurrent()
        assertEquals(0, h.count("/claim"))
        h.controller.foreground(true)
        runCurrent()
        assertEquals(1, h.accepted.size)
        assertEquals(1, h.count("/start"))
        assertEquals(1, h.browser.size)
        val statusBodies = h.network.requests.filter { it.url.endsWith("/status") }.map { it.body }
        assertEquals(1, statusBodies.distinct().size)
        h.close()
    }

    @Test
    fun anAlreadyStartedOneUseClaimFinishesAcrossOrdinaryBackground() = runTest {
        val h = Harness(this)
        h.onOpen = {}
        val original = h.network.respond
        val held = CompletableDeferred<Unit>()
        var claimDetached = false
        h.network.respond = { request ->
            if (request.url.endsWith("/claim")) try { held.await() } finally { claimDetached = true }
            original(request)
        }
        h.controller.start()
        runCurrent()
        assertIs<PersonalSignInState.Finishing>(h.controller.state.value)
        h.controller.foreground(false)
        runCurrent()
        assertFalse(claimDetached)
        held.complete(Unit)
        runCurrent()
        assertEquals(1, h.accepted.size)
        assertEquals(1, h.count("/claim"))
        assertEquals(0, h.count("/cancel"))
        h.close()
    }

    @Test
    fun sourceRetirementRevokesTheCapturedAttemptAndRejectsAnUncancellableLateClaim() = runTest {
        val h = Harness(this)
        h.onOpen = {}
        val original = h.network.respond
        val held = CompletableDeferred<Unit>()
        h.network.respond = { request ->
            if (request.url.endsWith("/claim")) withContext(NonCancellable) { held.await() }
            original(request)
        }
        h.controller.start()
        runCurrent()
        h.network.generation++
        h.controller.close()
        runCurrent()
        assertEquals(1, h.count("/cancel"))
        assertEquals(PersonalSignInState.Idle, h.controller.state.value)
        held.complete(Unit)
        runCurrent()
        assertTrue(h.accepted.isEmpty())
        assertFalse(h.controller.retry())
    }

    @Test
    fun explicitCancellationCannotOpenALateBrowserAndASeparateNewAttemptCanStart() = runTest {
        val h = Harness(this)
        val original = h.network.respond
        val held = CompletableDeferred<Unit>()
        h.network.respond = { request ->
            if (request.url.endsWith("/start")) withContext(NonCancellable) { held.await() }
            original(request)
        }
        h.controller.start()
        runCurrent()
        h.controller.cancel()
        held.complete(Unit)
        runCurrent()
        assertTrue(h.browser.isEmpty())
        assertEquals(PersonalSignInState.Idle, h.controller.state.value)
        h.network.respond = original
        assertTrue(h.controller.start())
        runCurrent()
        assertEquals(1, h.browser.size)
        assertEquals(2, h.count("/start"))
        h.close()
    }

    @Test
    fun aPausedAttemptExpiresWithoutSavingItsProofOrInstallingAnAccount() = runTest {
        val h = Harness(this)
        h.controller.start()
        runCurrent()
        advanceTimeBy(600_001)
        runCurrent()
        assertEquals(PersonalSignInState.Failed(PersonalAuthProblem.Expired), h.controller.state.value)
        assertEquals(1, h.count("/cancel"))
        assertEquals(0, h.count("/status"))
        assertTrue(h.accepted.isEmpty())
        assertFalse(h.controller.retry())
        h.close()
    }

    @Test
    fun temporaryNetworkFailureKeepsTheSameProofForExplicitRetry() = runTest {
        val h = Harness(this)
        h.onOpen = {}
        val original = h.network.respond
        var statuses = 0
        h.network.respond = { request ->
            if (request.url.endsWith("/status") && ++statuses == 1) error("native token=${h.network.session}")
            original(request)
        }
        h.controller.start()
        runCurrent()
        assertEquals(PersonalAuthProblem.Unreachable, assertIs<PersonalSignInState.Waiting>(h.controller.state.value).problem)
        assertTrue(h.controller.retry())
        runCurrent()
        assertEquals(1, h.accepted.size)
        assertEquals(1, h.count("/start"))
        assertEquals(1, h.browser.size)
        h.close()
    }

    @Test
    fun deniedConsentClearsOnlyThisAttemptAndShowsFixedRecovery() = runTest {
        val h = Harness(this)
        h.onOpen = {}
        val original = h.network.respond
        h.network.respond = { request ->
            if (request.url.endsWith("/status")) PersonalAuthClientTest.json("""{"phase":"failed","failure":"denied"}""") else original(request)
        }
        h.controller.start()
        runCurrent()
        assertEquals(PersonalSignInState.Failed(PersonalAuthProblem.Denied), h.controller.state.value)
        assertEquals(1, h.count("/cancel"))
        assertTrue(h.accepted.isEmpty())
        h.close()
    }

    @Test
    fun failedSecureStoreCommitRevokesTheNewSessionWithoutCancellingAConsumedAttempt() = runTest {
        val h = Harness(this)
        h.onOpen = {}
        h.onAccept = { error("native-keystore-detail") }
        h.controller.start()
        runCurrent()
        assertEquals(PersonalSignInState.Failed(PersonalAuthProblem.Unavailable), h.controller.state.value)
        assertEquals(1, h.count("/sign-out"))
        assertEquals(0, h.count("/cancel"))
        assertEquals("Bearer ${h.network.session}", h.network.requests.last().headers["Authorization"])
        assertTrue(h.accepted.isEmpty())
        h.close()
    }

    @Test
    fun aFailedSecureStageNeverAcknowledgesOrPublishesItsProvisionalSession() = runTest {
        val h = Harness(this)
        h.onOpen = {}
        h.onStage = { error("native-keystore-detail") }
        h.controller.start()
        runCurrent()
        assertEquals(PersonalSignInState.Failed(PersonalAuthProblem.Unavailable), h.controller.state.value)
        assertEquals(0, h.count("/session"))
        assertEquals(1, h.count("/sign-out"))
        assertEquals(0, h.count("/cancel"))
        assertTrue(h.accepted.isEmpty())
        h.close()
    }

    @Test
    fun retiringDuringConfirmationRetainsTheSecureCleanupRecordUntilRevocationSucceeds() = runTest {
        val h = Harness(this)
        h.onOpen = {}
        val original = h.network.respond
        val confirmation = CompletableDeferred<Unit>()
        val revoked = CompletableDeferred<Unit>()
        h.network.respond = { request ->
            if (request.url.endsWith("/session")) withContext(NonCancellable) { confirmation.await() }
            if (request.url.endsWith("/sign-out")) revoked.await()
            original(request)
        }
        h.controller.start()
        runCurrent()
        assertTrue(h.staged.containsKey(h.network.session))
        h.network.generation++
        h.controller.close()
        runCurrent()
        assertEquals(1, h.count("/sign-out"))
        assertEquals(0, h.count("/cancel"))
        assertTrue(h.staged.containsKey(h.network.session))
        confirmation.complete(Unit)
        runCurrent()
        assertTrue(h.accepted.isEmpty())
        revoked.complete(Unit)
        runCurrent()
        assertTrue(h.staged.isEmpty())
    }

    @Test
    fun uncertainAcknowledgementAndRevocationNeverLoseTheCleanupOnlyBearer() = runTest {
        val h = Harness(this)
        h.onOpen = {}
        val original = h.network.respond
        h.network.respond = { request ->
            if (request.url.endsWith("/session") || request.url.endsWith("/sign-out")) error("native-connection-failure")
            original(request)
        }
        h.controller.start()
        runCurrent()
        assertEquals(PersonalSignInState.Failed(PersonalAuthProblem.Unreachable), h.controller.state.value)
        assertTrue(h.accepted.isEmpty())
        assertEquals(1, h.count("/claim"))
        assertEquals(1, h.count("/sign-out"))
        assertTrue(h.staged.containsKey(h.network.session))
        assertTrue(h.discarded.isEmpty())
        h.close()
    }

    @Test
    fun confirmationCannotChangeTheImmutableAccountOrPromotePrivateAuthority() = runTest {
        for (replacement in listOf("\"accountId\":\"different\"", "\"githubId\":99", "\"authority\":\"privateRepositories\"")) {
            val h = Harness(this)
            h.onOpen = {}
            val original = h.network.respond
            val field = replacement.substringBefore(':')
            val changed = h.network.account.replace(Regex("$field:(\"[^\"]*\"|[0-9]+)"), replacement)
            h.network.respond = { request -> if (request.url.endsWith("/session")) PersonalAuthClientTest.json(changed) else original(request) }
            h.controller.start()
            runCurrent()
            assertEquals(PersonalSignInState.Failed(PersonalAuthProblem.Reconnect), h.controller.state.value)
            assertTrue(h.accepted.isEmpty())
            assertEquals(1, h.count("/sign-out"))
            assertTrue(h.staged.isEmpty())
            h.close()
        }
    }

    private class Harness(scope: TestScope) {
        val network = PersonalAuthClientTest.Fixture()
        val browser = mutableListOf<String>()
        val accepted = mutableListOf<PersonalSession>()
        val staged = mutableMapOf<String, PersonalSession>()
        val discarded = mutableListOf<PersonalSession>()
        var owner = true
        var onOpen: () -> Unit = { controller.foreground(false) }
        var onStage: (PersonalSession) -> Unit = {}
        var onAccept: (PersonalSession) -> Unit = { accepted += it; staged.remove(it.token) }
        val controller = PersonalSignInController(network.client, scope.backgroundScope, scope.backgroundScope,
            object : PersonalSessionStaging {
                override fun savePending(session: PersonalSession) { onStage(session); staged[session.token] = session }
                override fun discardPending(session: PersonalSession) { discarded += session; staged.remove(session.token) }
            },
            { 1200 + scope.testScheduler.currentTime / 1000 }, { owner },
            { browser += it; onOpen() }, { onAccept(it) })
        fun count(suffix: String): Int = network.requests.count { it.url.endsWith(suffix) }
        fun close() = controller.close()
    }
}
