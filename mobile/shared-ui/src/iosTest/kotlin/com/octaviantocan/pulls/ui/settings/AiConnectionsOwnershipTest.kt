package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Actual shared remember/field-action owners in the Native Compose runtime; no provider or UIKit execution is implied. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiConnectionsOwnershipTest {
    @Test
    fun pauseKeepsTheMountedDraftWhileDisposedAndReplacedOwnersRetireItsActualFieldSetter() = runTest {
        val first = AiConnectionsHarness(backgroundScope)
        val next = AiConnectionsHarness(backgroundScope, "another-pully-account")
        val source = mutableStateOf(first.state)
        val shown = mutableStateOf(true)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(AiConnectionsNoNodes(), recomposer)
        lateinit var actions: AiConnectionsActions
        lateinit var actualFieldSetter: (String) -> Unit
        var time = 0L
        suspend fun frame() { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(++time); runCurrent() }
        try {
            composition.setContent {
                if (shown.value) {
                    actions = rememberAiConnectionsActions(source.value, AiConnectionKind.PiEndpoint)
                    actualFieldSetter = actions::credential
                }
            }
            runCurrent(); frame(); frame()
            val original = actions
            val originalSetter = actualFieldSetter
            original.endpoint("https://pi.example.com/v1"); originalSetter("current-unsent-key")
            first.reads.pause()
            assertFalse(original.current)
            originalSetter("paused-old-ime-value"); original.savePi()
            assertEquals("current-unsent-key", first.state.credentialInput)
            frame()
            assertSame(original, actions)
            assertEquals("https://pi.example.com/v1", first.state.endpointInput)
            first.reads.resume(); frame(); frame()
            assertSame(original, actions)
            actions.savePi(); runCurrent(); frame()
            assertEquals(1, first.configurations.size)
            assertEquals("", first.state.credentialInput)
            assertEquals(AiConnectionStatus.Unverified, first.state.view(AiConnectionKind.PiEndpoint)?.status)
            val accepted = actions
            val acceptedSetter = actualFieldSetter
            accepted.credentialMode(AiPiCredentialMode.Replace); acceptedSetter("next-unsent-key")
            shown.value = false; frame()
            assertFalse(accepted.current)
            assertEquals("", first.state.credentialInput)
            acceptedSetter("disposed-old-ime-value")
            assertEquals("", first.state.credentialInput)
            source.value = next.state; shown.value = true; frame(); frame()
            originalSetter("another-source-ime-value"); acceptedSetter("another-source-ime-value")
            assertEquals("", next.state.credentialInput)
            assertEquals("", next.state.endpointInput)
            assertTrue(next.configurations.isEmpty())
            assertTrue(actions.current)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); first.close(); next.close()
        }
    }

    @Test
    fun disposingTheActualPagePreventsQueuedWritesButRetainsAnAlreadyAcceptedErrorReceipt() = runTest {
        for (accepted in listOf(false, true)) {
            val h = AiConnectionsHarness(backgroundScope)
            val reply = CompletableDeferred<Unit>()
            h.configure = { _, _ ->
                withContext(NonCancellable) { reply.await() }
                throw AiConnectionException(AiConnectionProblem.CredentialRejected)
            }
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(AiConnectionsNoNodes(), recomposer)
            var actions: AiConnectionsActions? = null
            try {
                composition.setContent { actions = rememberAiConnectionsActions(h.state, AiConnectionKind.PiEndpoint) }
                runCurrent(); Snapshot.sendApplyNotifications(); clock.sendFrame(1L); runCurrent()
                val drawn = assertNotNull(actions)
                drawn.endpoint("https://pi.example.com/v1"); drawn.credential("sample-key"); drawn.savePi()
                if (accepted) runCurrent()
                h.reads.pause(); composition.dispose()
                assertEquals("", h.state.credentialInput)
                reply.complete(Unit); runCurrent()
                assertEquals(if (accepted) 1 else 0, h.configurations.size)
                if (accepted) assertEquals(AiConnectionProblem.CredentialRejected, h.state.error(AiConnectionKind.PiEndpoint))
                else assertNull(h.state.error(AiConnectionKind.PiEndpoint))
                assertTrue(h.cues.isEmpty())
            } finally {
                composition.dispose(); recomposer.close(); runner.cancel(); h.close(); reply.complete(Unit)
            }
        }
    }
}

private class AiConnectionsNoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
