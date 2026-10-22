package com.octaviantocan.pulls.data

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PersonalSignInReentrancyTest {
    @Test
    fun aConnectedObserverCanCloseTheRouteOnlyAfterItsSessionWasAccepted() = runBlocking {
        withTimeout(5_000) {
            val ui = ImmediateUi()
            val scope = CoroutineScope(SupervisorJob() + ui)
            val cleanup = CoroutineScope(SupervisorJob() + ui)
            try {
                val h = Harness()
                lateinit var controller: PersonalSignInController
                val connected = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                val observer = withContext(ui) {
                    controller = h.controller(scope, cleanup) {
                        events += "accepted"
                        assertTrue(h.current, "A retired route cannot accept a new account")
                        h.staged.clear()
                    }
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.state.collect { state ->
                            if (state is PersonalSignInState.Connected) {
                                events += "connected"
                                h.current = false
                                controller.close()
                                events += "retired"
                                connected.complete(Unit)
                            }
                        }
                    }.also { controller.start() }
                }
                h.ackStarted.await()
                // Resume from outside the UI dispatcher, just as a native HTTP completion does.
                h.ack.complete(Unit)
                connected.await()
                withContext(ui) {
                    assertEquals(listOf("accepted", "connected", "retired"), events)
                    assertTrue(h.staged.isEmpty())
                    assertFalse(h.network.requests.any { it.url.endsWith("/cancel") || it.url.endsWith("/sign-out") })
                }
                observer.cancelAndJoin()
            } finally { scope.cancel(); cleanup.cancel(); ui.close() }
        }
    }

    @Test
    fun failedAcceptanceNeverPublishesConnectedAndRevokesItsCapturedBearer() = runBlocking {
        withTimeout(5_000) {
            val ui = ImmediateUi()
            val scope = CoroutineScope(SupervisorJob() + ui)
            val cleanup = CoroutineScope(SupervisorJob() + ui)
            try {
                val h = Harness()
                val failed = CompletableDeferred<Unit>()
                val states = mutableListOf<PersonalSignInState>()
                lateinit var controller: PersonalSignInController
                val observer = withContext(ui) {
                    controller = h.controller(scope, cleanup) { error("fixture secure promotion failed") }
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.state.collect { state ->
                            states += state
                            if (state is PersonalSignInState.Failed) failed.complete(Unit)
                        }
                    }.also { controller.start() }
                }
                h.ackStarted.await()
                h.ack.complete(Unit)
                failed.await()
                h.revoked.await()
                withContext(ui) {
                    assertFalse(states.any { it is PersonalSignInState.Connected })
                    assertEquals(PersonalAuthProblem.Unavailable, assertIs<PersonalSignInState.Failed>(controller.state.value).problem)
                    assertTrue(h.staged.isEmpty())
                    assertEquals(1, h.network.requests.count { it.url.endsWith("/sign-out") })
                    assertFalse(h.network.requests.any { it.url.endsWith("/cancel") })
                    controller.close()
                }
                observer.cancelAndJoin()
            } finally { scope.cancel(); cleanup.cancel(); ui.close() }
        }
    }

    private class Harness {
        val network = PersonalAuthClientTest.Fixture()
        val ackStarted = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()
        val revoked = CompletableDeferred<Unit>()
        val staged = mutableListOf<PersonalSession>()
        var current = true
        init {
            val original = network.respond
            network.respond = { request ->
                if (request.url.endsWith("/session")) { ackStarted.complete(Unit); ack.await() }
                original(request).also { if (request.url.endsWith("/sign-out")) revoked.complete(Unit) }
            }
        }
        fun controller(scope: CoroutineScope, cleanup: CoroutineScope, accept: (PersonalSession) -> Unit) =
            PersonalSignInController(network.client, scope, cleanup, object : PersonalSessionStaging {
                override fun savePending(session: PersonalSession) { staged += session }
                override fun discardPending(session: PersonalSession) { staged.removeAll { it.token == session.token } }
            }, { 1200 }, { current }, {}, accept)
    }

    /** A serial native UI loop with immediate dispatch when a resumed collector already owns it. */
    private class ImmediateUi : CoroutineDispatcher(), AutoCloseable {
        private val thread = AtomicReference<Thread>()
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "personal-auth-test-ui").also(thread::set) }
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = Thread.currentThread() !== thread.get()
        override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute(block) }
        override fun close() { executor.shutdownNow() }
    }
}
