package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.ChatActions
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.ai.chatUi
import com.octaviantocan.pulls.ui.ai.rememberChatActions
import com.octaviantocan.pulls.ui.home.HomeBackObserver
import com.octaviantocan.pulls.ui.home.HomeLifecycleObserver
import com.octaviantocan.pulls.ui.home.HomeObservations
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.settings.ModelConfiguration
import com.octaviantocan.pulls.ui.settings.SourceModelSettingsServices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Mounts the production factory policy projection and the actual submission/Ask state owners on Native. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewDraftPauseHostTest {
    @Test
    fun aBackgroundFrameKeepsSummaryVerdictAndAskInputButActualPolicyAndSourceRetirementStillApply() = runTest {
        val h = DiffHarness(backgroundScope)
        val next = DiffHarness(backgroundScope)
        for (fixture in listOf(h, next)) {
            fixture.handler = { name, args ->
                if (name == "review-data") buildJsonObject { put("canWrite", true) } else fixture.default(name, args)
            }
            fixture.page.load()
        }
        runCurrent()
        val gate = HomeObservations()
        val installedAi = mutableStateOf(AiCapabilities.Full)
        val installedReview = mutableStateOf(ReviewCapabilities.Full)
        val port = ReviewActionReadiness({ gate.ready }, { installedAi.value }, { installedReview.value })
        val models = SourceModelSettingsServices(h.page.repo, MemorySettingsPreferences(),
            { ModelConfiguration(h.engine, h.model) }, { h.engine = it.engine; h.model = it.model }, { true })
        val factory = SharedReviewFrameActions(models, DraftNativeActions, { true }, { gate.ready }, { installedAi.value }, { installedReview.value })
        val session = mutableStateOf(h.page)
        val top = ReviewTopUi { _, _ -> }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        lateinit var draft: ReviewSubmissionState
        lateinit var submission: ReviewMutationActions
        lateinit var chat: ChatActions
        var mounted = false
        var canSubmit = false
        var canAsk = false
        var drawnAi = AiCapabilities.None
        var drawnReview = ReviewCapabilities.None
        suspend fun frame(time: Long) { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(time); runCurrent() }
        try {
            composition.setContent {
                CompositionLocalProvider(
                    LocalReviewActionReadiness provides port,
                    LocalAiCapabilities provides AiCapabilities.Full,
                    LocalReviewCapabilities provides ReviewCapabilities.Full,
                ) {
                    factory.Content {
                        assertSame(port, LocalReviewActionReadiness.current)
                        drawnAi = LocalAiCapabilities.current
                        drawnReview = LocalReviewCapabilities.current
                        ReviewSubmissionContent(session.value, top, "APPROVE") { actions, actualDraft ->
                            mounted = true
                            submission = actions
                            draft = actualDraft
                            canSubmit = actions.canSubmit
                        }
                        chat = rememberChatActions(session.value)
                        canAsk = chat.canAsk
                    }
                }
            }
            runCurrent(); frame(1L)
            assertTrue(mounted); assertTrue(canSubmit); assertTrue(canAsk)
            draft.body = "Keep the exact private review summary"
            draft.verdict = "REQUEST_CHANGES"
            chat.changeInput("Keep the unsent Ask question")
            val originalDraft = draft
            val originalSubmission = submission
            val oldInput = chat.changeInput
            val view = h.page.captureView()
            gate.pause(); h.cache.pauseObservations()
            assertFalse(submission.canSubmit); assertTrue(submission.canKeepSubmission)
            mounted = false
            frame(2L); frame(3L)
            assertTrue(mounted)
            assertSame(originalDraft, draft)
            assertSame(originalSubmission, submission)
            assertEquals(AiCapabilities.Full, drawnAi)
            assertEquals(ReviewCapabilities.Full, drawnReview)
            assertFalse(canSubmit); assertFalse(canAsk)
            oldInput("A retained edit while backgrounded")
            assertEquals("Keep the unsent Ask question", chatUi(h.page).chatInput)
            assertEquals("Keep the exact private review summary", draft.body)
            assertEquals("REQUEST_CHANGES", draft.verdict)
            assertEquals(view, h.page.captureView())
            gate.resume(); h.cache.resumeObservations(); frame(4L); frame(5L)
            assertSame(originalDraft, draft)
            assertTrue(canSubmit); assertTrue(canAsk)
            assertEquals("Keep the exact private review summary", draft.body)
            assertEquals("REQUEST_CHANGES", draft.verdict)
            assertEquals("Keep the unsent Ask question", chatUi(h.page).chatInput)

            installedReview.value = ReviewCapabilities.ReadOnly
            mounted = false; frame(6L)
            assertFalse(mounted)
            assertFalse(originalSubmission.canKeepSubmission)
            installedReview.value = ReviewCapabilities.Full
            frame(7L)
            assertTrue(mounted)
            assertNotSame(originalDraft, draft)
            assertEquals("", draft.body)
            assertEquals("APPROVE", draft.verdict)

            gate.pause(); h.cache.pauseObservations()
            h.generation++; h.cache.clear()
            session.value = next.page
            frame(8L); frame(9L)
            assertFalse(originalSubmission.current)
            assertEquals("", draft.body)
            assertEquals("", chatUi(next.page).chatInput)
            oldInput("Retired source input")
            assertEquals("", chatUi(next.page).chatInput)
        } finally {
            gate.close(); composition.dispose(); recomposer.close(); runner.cancel()
            h.page.detach(); next.page.detach()
        }
    }
}

private object DraftNativeActions : NativeHomeActions {
    override fun copyText(text: String) = error("Unexpected clipboard action")
    override fun openOnGithub(url: String) = error("Unexpected external action")
    override fun openInBrowser(url: String) = error("Unexpected browser action")
    override fun observeLifecycle(observer: HomeLifecycleObserver) = error("Unexpected lifecycle registration")
    override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver) = error("Unexpected Back registration")
}
