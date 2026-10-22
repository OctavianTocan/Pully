package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.ui.review.DiffHarness
import com.octaviantocan.pulls.ui.review.NoNodes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Mounts the actual remembered actions and draft state consumed by the shared Chat screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatOwnershipTest {
    @Test
    fun theMountedComposerUsesLatestPolicyAndRetiresItsActionsAndPrivateDraft() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var starts = 0
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> { starts++; JsonPrimitive("native-chat") }
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        var policy by mutableStateOf(AiCapabilities.Full)
        var mounted: ChatActions? = null
        var drawn: AiUi? = null
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        var nanos = 0L
        suspend fun frame() {
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(++nanos); runCurrent()
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalAiCapabilities provides policy) {
                    mounted = rememberChatActions(h.page)
                    drawn = chatUi(h.page)
                }
            }
            runCurrent(); frame()
            val original = assertNotNull(mounted)
            val edit = original.changeInput
            edit("Unsent private question")
            frame(); assertSame(original, mounted)
            assertEquals("Unsent private question", assertNotNull(drawn).chatInput)
            policy = AiCapabilities.ReadOnly; frame()
            assertSame(original, mounted)
            edit("Revoked old input")
            assertEquals("Unsent private question", assertNotNull(drawn).chatInput)
            assertFalse(original.send("Revoked")); assertFalse(original.newChat())
            runCurrent(); assertEquals(0, starts)
            policy = AiCapabilities.Full; frame()
            assertTrue(original.send("Native question")); runCurrent()
            assertEquals(1, starts)
            h.head = "head-b"; h.page.refresh(); runCurrent()
            h.head = "head-a"; h.page.refresh(); runCurrent(); frame()
            val replacement = assertNotNull(mounted)
            assertNotSame(original, replacement)
            assertFalse(original.send("Old button"))
            assertEquals("", assertNotNull(drawn).chatInput)
            replacement.changeInput("Replacement draft")
            edit("Old head input")
            assertEquals("Replacement draft", assertNotNull(drawn).chatInput)
            val retainedEdit = replacement.changeInput
            composition.dispose()
            retainedEdit("Disposed input")
            assertEquals("Replacement draft", assertNotNull(drawn).chatInput)
            assertFalse(replacement.send("Disposed"))
            var navigated = false
            assertFalse(replacement.read { navigated = true })
            assertFalse(navigated)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
        }
    }
}
