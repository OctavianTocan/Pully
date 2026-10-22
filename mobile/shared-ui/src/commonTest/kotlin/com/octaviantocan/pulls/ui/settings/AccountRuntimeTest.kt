package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.data.RpcObserver
import com.octaviantocan.pulls.data.RpcConnectionException
import com.octaviantocan.pulls.ui.home.HomeObservations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountRuntimeTest {
    @Test
    fun restoredTokenIsSentOnlyToVerificationUntilTheSameAccountIsConfirmed() = runTest {
        val h = Harness(this, restored = true)
        h.runtime.start()
        runCurrent()
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertEquals("reader@example.com", h.runtime.state.email)
        assertNull(h.requests.first().headers["X-Pully-Session"])
        val verification = h.requests.first { it.name() == "account-me" }
        assertEquals("fixture-session", verification.headers["X-Pully-Session"])
        assertFalse(h.storage.restored)
        assertEquals(1, h.cleanups)
        assertTrue(h.synchronizations > 0)
    }

    @Test
    fun anOfflineRestoredSessionStaysSavedButNeverBecomesAnOrdinaryCredential() = runTest {
        val h = Harness(this, restored = true)
        h.reply = { request ->
            if (request.name() == "account-me") error("The fixture is offline.")
            RpcResponse(200, """{"result":{"hasAccount":true}}""")
        }
        h.runtime.start()
        runCurrent()
        assertEquals(AccountPhase.Problem, h.runtime.state.phase)
        assertTrue(h.storage.restored)
        assertEquals("fixture-session", h.storage.token)
        assertNull(h.storage.connection(false)?.headers?.get("X-Pully-Session"))
        assertEquals(0, h.cleanups)
    }

    @Test
    fun anExplicitRestoredSessionRejectionReturnsToTheRealSignInPage() = runTest {
        val h = Harness(this, restored = true)
        h.reply = { request ->
            if (request.name() == "account-me") RpcResponse(401, """{"error":"Sign in again."}""")
            else RpcResponse(200, """{"result":{"hasAccount":true}}""")
        }
        h.runtime.start()
        runCurrent()
        assertEquals(AccountPhase.SignIn, h.runtime.state.phase)
        assertNull(h.storage.token)
        assertEquals(1, h.cleanups)
    }

    @Test
    fun anHttp401WebPageCannotRejectARestoredSessionAndVerificationCanRecover() = runTest {
        for (response in nonSessionRejections()) {
            val h = Harness(this, restored = true)
            val generation = h.storage.generation
            h.reply = { request -> if (request.name() == "account-me") response else h.normalReply(request) }
            h.runtime.start()
            runCurrent()
            assertEquals(AccountPhase.Problem, h.runtime.state.phase)
            assertTrue(h.runtime.state.problem != null)
            assertTrue(h.storage.restored)
            assertEquals("fixture-session", h.storage.token)
            assertEquals(generation, h.storage.generation)
            assertNull(h.storage.connection(false)?.headers?.get("X-Pully-Session"))
            assertEquals("fixture-session", h.storage.connection(true)?.headers?.get("X-Pully-Session"))
            assertEquals(0, h.cleanups)
            assertEquals(0, h.synchronizations)

            h.reply = h::normalReply
            h.runtime.refresh()
            runCurrent()
            assertEquals(AccountPhase.Ready, h.runtime.state.phase)
            assertNull(h.runtime.state.problem)
            assertFalse(h.storage.restored)
            assertEquals("fixture-session", h.storage.token)
            assertEquals("reader@example.com", h.runtime.state.email)
            assertEquals(1, h.cleanups)
            assertTrue(h.synchronizations > 0)
            h.runtime.close()
        }
    }

    @Test
    fun anHttp401WebPageCannotForgetAnAlreadyVerifiedSession() = runTest {
        for (response in nonSessionRejections()) {
            val h = Harness(this)
            h.runtime.start()
            runCurrent()
            val generation = h.storage.generation
            h.reply = { request -> if (request.name() == "account-me") response else h.normalReply(request) }
            h.runtime.refresh()
            runCurrent()
            assertEquals(AccountPhase.Ready, h.runtime.state.phase)
            assertTrue(h.runtime.state.problem != null)
            assertEquals("reader@example.com", h.runtime.state.email)
            assertEquals("fixture-session", h.storage.token)
            assertEquals(generation, h.storage.generation)
            assertEquals("fixture-session", h.storage.connection(false)?.headers?.get("X-Pully-Session"))
            assertEquals(0, h.cleanups)

            h.reply = h::normalReply
            h.runtime.refresh()
            runCurrent()
            assertEquals(AccountPhase.Ready, h.runtime.state.phase)
            assertNull(h.runtime.state.problem)
            assertEquals("fixture-session", h.storage.token)
            assertEquals(0, h.cleanups)
            h.runtime.close()
        }
    }

    @Test
    fun anExplicitVerifiedSessionRejectionStillForgetsCredentials() = runTest {
        val h = Harness(this)
        h.runtime.start()
        runCurrent()
        h.reply = { request ->
            if (request.name() == "account-me") RpcResponse(401, """{"error":"Sign in again."}""")
            else h.normalReply(request)
        }
        h.runtime.refresh()
        runCurrent()
        assertEquals(AccountPhase.SignIn, h.runtime.state.phase)
        assertNull(h.storage.token)
        assertNull(h.storage.email)
        assertEquals(1, h.cleanups)
    }

    @Test
    fun sourceCleanupFinishesBeforeTheNativeHostPublishesReplacementCredentials() = runTest {
        val h = Harness(this)
        h.runtime.start()
        runCurrent()
        var checkedBeforePublication = false
        h.storage.beforePublication = {
            assertEquals(1, h.cleanups)
            assertEquals(AccountPhase.Checking, h.runtime.state.phase)
            assertEquals("https://old.example", h.storage.connection(false)?.server)
            checkedBeforePublication = true
        }
        h.storage.replace("https://new.example", "new@example.com", "new-session")
        runCurrent()
        assertTrue(checkedBeforePublication)
        assertEquals("new@example.com", h.runtime.state.email)
        assertEquals(1L, h.runtime.sourceEpoch)
    }

    @Test
    fun aLateOldAccountRejectionCannotForgetTheReplacementAccount() = runTest {
        val h = Harness(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.reply = { request ->
            if (request.name() == "account-me" && request.url.startsWith("https://old.example")) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                RpcResponse(200, """{"result":null}""")
            } else h.normalReply(request)
        }
        h.runtime.start()
        runCurrent()
        assertTrue(entered.isCompleted)
        h.storage.replace("https://new.example", "new@example.com", "new-session")
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertEquals("new@example.com", h.runtime.state.email)
        assertEquals("new-session", h.storage.token)
        assertEquals(1, h.cleanups)
    }

    @Test
    fun resumeNetworkFailureRetainsTheAlreadyVerifiedAccountAndApp() = runTest {
        val h = Harness(this)
        h.runtime.start()
        runCurrent()
        h.reply = { error("The fixture is offline.") }
        h.runtime.refresh()
        runCurrent()
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertEquals("reader@example.com", h.runtime.state.email)
        assertEquals("fixture-session", h.storage.token)
        assertEquals(RpcConnectionException().message, h.runtime.state.problem)
        assertEquals(0, h.cleanups)
    }

    @Test
    fun nativeGoogleRegistrationIsRequiredEvenWhenTheServerOffersGoogle() = runTest {
        val h = Harness(this, signedIn = false)
        h.runtime.start()
        runCurrent()
        assertEquals(AccountPhase.SignIn, h.runtime.state.phase)
        assertNull(h.runtime.state.googleClientId)
        assertNull(h.runtime.signInServices().googleClientId)
        assertEquals(0, h.storage.googleRequests)
    }

    @Test
    fun passwordCommitAdvancesTheSourceThenChecksTheRealAccountAndSynchronizes() = runTest {
        val h = Harness(this, signedIn = false)
        h.runtime.start()
        runCurrent()
        h.runtime.signInServices().password("reader@example.com", "fixture-password")
        runCurrent()
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertEquals("fixture-session", h.storage.token)
        assertTrue(h.requests.any { it.name() == "account-sign-in" })
        assertTrue(h.requests.any { it.name() == "account-me" })
        assertTrue(h.synchronizations > 0)
    }

    @Test
    fun signOutWhileOfflineStillForgetsLocalCredentials() = runTest {
        val h = Harness(this)
        h.reply = { request ->
            if (request.name() == "account-sign-out") error("The fixture is offline.")
            else h.normalReply(request)
        }
        h.runtime.start()
        runCurrent()
        h.runtime.signOut()
        runCurrent()
        assertEquals(AccountPhase.SignIn, h.runtime.state.phase)
        assertNull(h.storage.token)
    }

    @Test
    fun lateSignOutDoesNotClearANewerAccount() = runTest {
        val h = Harness(this)
        h.runtime.start()
        runCurrent()
        val release = CompletableDeferred<Unit>()
        h.reply = { request ->
            if (request.name() == "account-sign-out") withContext(NonCancellable) { release.await(); RpcResponse(200, """{"result":null}""") }
            else h.normalReply(request)
        }
        val logout = backgroundScope.async { h.runtime.signOut() }
        runCurrent()
        h.storage.replace("https://new.example", "new@example.com", "new-session")
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertTrue(logout.isCancelled)
        assertEquals("new-session", h.storage.token)
        assertEquals("new@example.com", h.runtime.state.email)
    }

    @Test
    fun unavailableSecureStorageNeverStartsAnAnonymousRequest() = runTest {
        val h = Harness(this)
        h.storage.problem = "Unlock the device and try again."
        h.runtime.start()
        runCurrent()
        assertEquals(AccountPhase.Problem, h.runtime.state.phase)
        assertEquals("Unlock the device and try again.", h.runtime.state.problem)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun failedSupersededRefreshCannotOverwriteANewerReadyStateForTheSameSource() = runTest {
        val h = Harness(this)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var first = true
        h.reply = { request ->
            if (first) {
                first = false
                firstEntered.complete(Unit)
                withContext(NonCancellable) { releaseFirst.await() }
                error("An obsolete refresh failed.")
            }
            h.normalReply(request)
        }
        h.runtime.start()
        runCurrent()
        assertTrue(firstEntered.isCompleted)
        h.runtime.refresh()
        runCurrent()
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertNull(h.runtime.state.problem)
    }

    @Test
    fun immediateRestoreReentrancyRetainsTheNewProbeForCancellation() = runTest {
        val h = Harness(this, restored = true, immediate = true)
        var entered = false
        var detached = false
        h.synchronize = {
            entered = true
            try { awaitCancellation() } finally { detached = true }
        }
        h.runtime.start()
        assertTrue(entered)
        h.runtime.close()
        assertTrue(detached)
    }

    @Test
    fun changingTheServerAccountBoundaryRetiresPrivateSourceState() = runTest {
        val h = Harness(this)
        h.runtime.start()
        runCurrent()
        assertEquals(true, h.runtime.serverHasAccount)
        h.hasAccount = false
        h.runtime.refresh()
        runCurrent()
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertEquals(false, h.runtime.serverHasAccount)
        assertEquals(1, h.cleanups)
        assertEquals(1L, h.runtime.sourceEpoch)
        // The existing Android policy retains the token until account-me explicitly rejects it.
        assertEquals("fixture-session", h.storage.token)
        assertTrue(h.observedMethods.contains("account-status"))
    }

    @Test
    fun aRetainedResetConfirmationCannotEraseTheReplacementOwner() = runTest {
        val h = Harness(this)
        h.runtime.start()
        runCurrent()
        val openingEpoch = h.runtime.sourceEpoch
        h.storage.replace("https://new.example", "new@example.com", "new-session")
        runCurrent()
        assertFailsWith<CancellationException> { h.runtime.resetCredentials(openingEpoch) }
        assertEquals(0, h.storage.resets)
        assertEquals("new-session", h.storage.token)
        assertEquals("new@example.com", h.runtime.state.email)
    }

    @Test
    fun aCurrentResetStillClearsCredentialsAndReturnsToSignIn() = runTest {
        val h = Harness(this)
        h.runtime.start()
        runCurrent()
        h.runtime.resetCredentials(h.runtime.sourceEpoch)
        runCurrent()
        assertEquals(1, h.storage.resets)
        assertNull(h.storage.token)
        assertEquals(AccountPhase.SignIn, h.runtime.state.phase)
    }

    @Test
    fun aClosedApplicationCannotExecuteItsOldResetConfirmation() = runTest {
        val h = Harness(this)
        val openingEpoch = h.runtime.sourceEpoch
        h.runtime.close()
        assertFailsWith<CancellationException> { h.runtime.resetCredentials(openingEpoch) }
        assertEquals(0, h.storage.resets)
        assertEquals("fixture-session", h.storage.token)
    }

    @Test
    fun foregroundWaitFollowsReentrantRestorationThroughTheVerifiedSettingsSync() = runTest {
        val h = Harness(this, restored = true, immediate = true)
        val sync = CompletableDeferred<Unit>()
        h.synchronize = { sync.await() }
        val waiting = backgroundScope.async { h.runtime.refreshAndWait() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertFalse(h.storage.restored)
        assertEquals(0, h.synchronizations)
        sync.complete(Unit)
        runCurrent()
        assertTrue(waiting.await())
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertEquals(1, h.synchronizations)
    }

    @Test
    fun sourceReplacementSettlesBeforeAnObsoleteNonCancellableReplyReturns() = runTest {
        val h = Harness(this)
        val releaseOld = CompletableDeferred<Unit>()
        h.reply = { request ->
            if (request.url.startsWith("https://old.example")) {
                withContext(NonCancellable) { releaseOld.await() }
            }
            h.normalReply(request)
        }
        val waiting = backgroundScope.async { h.runtime.refreshAndWait() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        h.storage.replace("https://new.example", "new@example.com", "new-session")
        runCurrent()
        try {
            assertTrue(waiting.isCompleted)
            assertTrue(waiting.await())
            assertEquals("new@example.com", h.runtime.state.email)
        } finally {
            releaseOld.complete(Unit)
        }
        runCurrent()
        assertEquals("new@example.com", h.runtime.state.email)
    }

    @Test
    fun cancellingAForegroundWaiterLeavesTheRuntimeVerificationAlive() = runTest {
        val h = Harness(this)
        val sync = CompletableDeferred<Unit>()
        h.synchronize = { sync.await() }
        val waiting = backgroundScope.async { h.runtime.refreshAndWait() }
        runCurrent()
        waiting.cancel()
        runCurrent()
        assertTrue(waiting.isCancelled)
        sync.complete(Unit)
        runCurrent()
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertNull(h.runtime.state.problem)
        assertEquals(1, h.synchronizations)
    }

    @Test
    fun aRetainedOfflineReadyPageDoesNotAuthorizeFreshObservation() = runTest {
        val h = Harness(this)
        h.runtime.start()
        runCurrent()
        h.reply = { error("The fixture is offline.") }
        val waiting = backgroundScope.async { h.runtime.refreshAndWait() }
        runCurrent()
        assertFalse(waiting.await())
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertEquals("fixture-session", h.storage.token)
        assertEquals(0, h.cleanups)
    }

    @Test
    fun closingWakesTheWaiterBeforeAnObsoleteNonCancellableReplyReturns() = runTest {
        val h = Harness(this)
        val release = CompletableDeferred<Unit>()
        h.reply = { request ->
            withContext(NonCancellable) { release.await() }
            h.normalReply(request)
        }
        val waiting = backgroundScope.async { h.runtime.refreshAndWait() }
        runCurrent()
        h.runtime.close()
        runCurrent()
        try {
            assertTrue(waiting.isCompleted)
            assertFalse(waiting.await())
        } finally {
            release.complete(Unit)
        }
        runCurrent()
    }

    @Test
    fun theActiveSceneFollowsAReplacementWithoutWaitingForItsOldTransport() = runTest {
        val h = Harness(this)
        val release = CompletableDeferred<Unit>()
        h.reply = { request ->
            if (request.url.startsWith("https://old.example")) withContext(NonCancellable) { release.await() }
            h.normalReply(request)
        }
        val reads = HomeObservations(false)
        val resumedFor = mutableListOf<String?>()
        val owner = AccountObservationOwner(backgroundScope,
            { refresh -> if (refresh) h.runtime.refreshAndWait() else h.runtime.awaitCurrentVerification() },
            reads::pause, { resumedFor += h.runtime.state.email; reads.resume() })
        h.invalidation = owner::sourceChanged
        owner.resumed(); runCurrent()
        assertFalse(reads.ready)
        h.storage.replace("https://new.example", "new@example.com", "new-session")
        runCurrent()
        try {
            assertTrue(reads.ready)
            assertEquals(listOf<String?>("new@example.com"), resumedFor)
            owner.stopped()
            assertFalse(reads.ready)
        } finally {
            release.complete(Unit)
        }
        runCurrent()
        assertFalse(reads.ready)
        assertEquals(listOf<String?>("new@example.com"), resumedFor)
        owner.close(); h.runtime.close(); reads.close()
    }

    @Test
    fun anExplicitAccountRetryCanResumeReadsWithoutAnotherForegroundEvent() = runTest {
        val h = Harness(this)
        h.runtime.start(); runCurrent()
        val reads = HomeObservations(false)
        val owner = AccountObservationOwner(backgroundScope,
            { refresh -> if (refresh) h.runtime.refreshAndWait() else h.runtime.awaitCurrentVerification() },
            reads::pause, reads::resume)
        h.invalidation = owner::sourceChanged
        h.reply = { error("The fixture is offline.") }
        owner.resumed(); runCurrent()
        assertFalse(reads.ready)
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        h.reply = h::normalReply
        h.runtime.refresh(); runCurrent()
        val verifiedRequests = h.requests.size
        owner.settled(); runCurrent()
        assertTrue(reads.ready)
        assertEquals(verifiedRequests, h.requests.size)
        owner.stopped(); owner.settled(); runCurrent()
        assertFalse(reads.ready)
        owner.close(); h.runtime.close(); reads.close()
    }

    @Test
    fun finishingVerificationAfterSceneStopOrCloseCannotResumeReads() = runTest {
        val h = Harness(this)
        val release = CompletableDeferred<Unit>()
        h.synchronize = { withContext(NonCancellable) { release.await() } }
        val reads = HomeObservations(false)
        var grants = 0
        val owner = AccountObservationOwner(backgroundScope,
            { refresh -> if (refresh) h.runtime.refreshAndWait() else h.runtime.awaitCurrentVerification() },
            reads::pause, { grants++; reads.resume() })
        owner.resumed(); runCurrent()
        owner.stopped()
        release.complete(Unit); runCurrent()
        owner.settled(); runCurrent()
        assertEquals(AccountPhase.Ready, h.runtime.state.phase)
        assertFalse(reads.ready)
        assertEquals(0, grants)
        owner.close()
        val previousRequests = h.requests.size
        owner.resumed(); owner.settled(); runCurrent()
        assertFalse(reads.ready)
        assertEquals(previousRequests, h.requests.size)
        h.runtime.close(); reads.close()
    }

    private fun nonSessionRejections() = listOf(
        RpcResponse(401, "<html>Access authorization required</html>", "text/html; charset=utf-8"),
        RpcResponse(401, "<html>Access authorization required</html>"),
        RpcResponse(401, "A proxy refused this request."),
    )

    private class Harness(scope: TestScope, restored: Boolean = false, signedIn: Boolean = true, immediate: Boolean = false) {
        val storage = Storage(restored, signedIn)
        val requests = mutableListOf<RpcRequest>()
        var cleanups = 0
        var invalidation: () -> Unit = {}
        var synchronizations = 0
        var hasAccount = true
        val observedMethods = mutableListOf<String>()
        var synchronize: suspend () -> Unit = {}
        var reply: suspend (RpcRequest) -> RpcResponse = ::normalReply
        val runtime = AccountRuntime(storage, object : RpcTransport {
            override suspend fun execute(request: RpcRequest): RpcResponse {
                requests += request
                return reply(request)
            }
        }, if (immediate) CoroutineScope(scope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(scope.testScheduler)) else scope.backgroundScope,
            { "" }, { cleanups++; invalidation() }, { synchronize(); synchronizations++ }, RpcObserver { name, _, _, _ -> observedMethods += name })

        fun normalReply(request: RpcRequest): RpcResponse = RpcResponse(200, when (request.name()) {
            "account-status" -> """{"result":{"hasAccount":$hasAccount,"googleClientId":"fixture-web-client"}}"""
            "account-me" -> """{"result":{"email":"${storage.email}"}}"""
            "account-sign-in" -> """{"result":{"session":"fixture-session","email":"reader@example.com"}}"""
            else -> """{"result":null}"""
        })
    }

    private class Storage(var restored: Boolean, signedIn: Boolean) : AccountStorage {
        var server = "https://old.example"
        var token: String? = if (signedIn) "fixture-session" else null
        var email: String? = if (signedIn) "reader@example.com" else null
        var generation = 0L
        var problem: String? = null
        var googleRequests = 0
        var resets = 0
        var beforePublication: () -> Unit = {}
        private var observer: AccountStorageObserver? = null
        override fun connection(verification: Boolean): AccountConnection? = if (problem != null) null else AccountConnection(
            server, if (token != null && (verification || !restored)) mapOf("X-Pully-Session" to token!!) else emptyMap(), "claude-code", "", generation,
        )
        override fun account() = StoredAccount(if (restored) null else email, restored, problem)
        override fun observe(observer: AccountStorageObserver): AccountStorageSubscription {
            this.observer = observer
            return object : AccountStorageSubscription { override fun cancel() { this@Storage.observer = null } }
        }
        fun replace(server: String, email: String?, token: String?) {
            observer?.invalidating()
            beforePublication()
            this.server = server; this.email = email; this.token = token; restored = false; generation++
            observer?.changed()
        }
        override fun acceptSession(session: String, email: String, expectedGeneration: Long): String? {
            if (generation != expectedGeneration) return "The account source changed."
            replace(server, email, session)
            return null
        }
        override fun confirmRestoredSession(email: String?, expectedGeneration: Long): String? {
            if (generation != expectedGeneration) return "The account source changed."
            if (email == null || email != this.email) return forgetSession()
            replace(server, email, token)
            return null
        }
        override fun forgetSession(): String? { replace(server, null, null); return null }
        override fun saveServer(server: String, accessId: String, accessSecret: String): String? { replace(server, null, null); return null }
        override fun saveAnalysis(engine: String, model: String, expectedGeneration: Long): String? =
            if (generation == expectedGeneration) null else "The account source changed."
        override fun resetCredentials(): String? { resets++; return forgetSession() }
        override fun retryStorage(): String? = null
        override fun googleAvailable(serverClientId: String) = false
        override fun googleToken(serverClientId: String, expectedGeneration: Long, completed: AccountTokenObserver): AccountStorageSubscription {
            googleRequests++
            completed.completed(null, null)
            return object : AccountStorageSubscription { override fun cancel() {} }
        }
    }
}

private fun RpcRequest.name(): String = Json.parseToJsonElement(body).jsonObject.getValue("name").jsonPrimitive.content
