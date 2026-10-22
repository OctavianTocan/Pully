package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.core.AnalyzePreset
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.AiSetupActions
import com.octaviantocan.pulls.ui.ai.AnalysisStart
import com.octaviantocan.pulls.ui.ai.BriefRewriteActions
import com.octaviantocan.pulls.ui.ai.ChatActions
import com.octaviantocan.pulls.ui.ai.CritiqueActions
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.ai.chatUi
import com.octaviantocan.pulls.ui.ai.installBriefFixture
import com.octaviantocan.pulls.ui.ai.installCritique
import com.octaviantocan.pulls.ui.ai.rememberAiSetupActions
import com.octaviantocan.pulls.ui.ai.rememberBriefRewriteActions
import com.octaviantocan.pulls.ui.ai.rememberChatActions
import com.octaviantocan.pulls.ui.ai.rememberCritiqueActions
import com.octaviantocan.pulls.ui.ai.startBrief
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real owners consumed by the existing readers keep one live port without requiring a composition frame. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewActionReadinessHostTest {
    @Test
    fun mountedControlsRefuseFreshDispatchSynchronouslyWhileTheirSessionAndDraftRemainCurrent() = runTest {
        val h = DiffHarness(backgroundScope)
        val calls = mutableListOf<String>()
        h.handler = { name, args ->
            calls += name
            if (name == "review-data") buildJsonObject { put("canWrite", true) } else h.default(name, args)
        }
        h.page.load(); runCurrent()
        val brief = installBriefFixture(h)
        installCritique(h)
        h.page.saved = h.page.saved!!.copy(brief = brief)
        val ui = DiffUi(h.page)
        val file = h.page.files.single()
        val models = SourceModelSettingsServices(h.page.repo, MemorySettingsPreferences(),
            { ModelConfiguration(h.engine, h.model) }, { h.engine = it.engine; h.model = it.model }, { true })
        var ready = true
        val readiness = ReviewActionReadiness({ ready })
        val top = ReviewTopUi { _, _ -> }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        lateinit var setup: AiSetupActions
        lateinit var chat: ChatActions
        lateinit var rewrite: BriefRewriteActions
        lateinit var findings: CritiqueActions
        lateinit var mutation: ReviewMutationActions
        lateinit var commands: ReviewCommandActions
        lateinit var frame: ReviewFrameOwner
        lateinit var progress: ReviewProgressActions
        lateinit var thread: ReviewThreadActions
        lateinit var diff: DiffPageActions
        lateinit var finding: FindingFocusAction
        try {
            composition.setContent {
                CompositionLocalProvider(
                    LocalReviewActionReadiness provides readiness,
                    LocalAiCapabilities provides AiCapabilities.Full,
                    LocalReviewCapabilities provides ReviewCapabilities.Full,
                ) {
                    setup = rememberAiSetupActions(h.page, models)
                    chat = rememberChatActions(h.page)
                    rewrite = rememberBriefRewriteActions(h.page, brief)
                    findings = rememberCritiqueActions(h.page)
                    mutation = rememberReviewMutationActions(h.page, top)
                    commands = rememberReviewCommandActions(h.page)
                    frame = rememberReviewFrameOwner(h.page, { true }, true)
                    progress = rememberReviewProgressActions(h.page)
                    thread = rememberReviewThreadActions(h.page)
                    diff = rememberDiffPageActions(h.page, ui, file, backgroundScope) { true }
                    finding = rememberFindingFocusAction(h.page, h.page.saved!!.critique!!.findings.single())
                }
            }
            runCurrent()
            assertTrue(setup.canCritique); assertTrue(chat.canAsk); assertTrue(rewrite.canRewrite)
            assertTrue(findings.canPublish); assertTrue(mutation.canSubmit); assertTrue(thread.canWrite)
            chat.changeInput("Keep this private draft")
            val retainedInput = chat.changeInput
            val view = h.page.captureView()
            calls.clear()
            ready = false // Deliberately do not run recomposition before any retained callback.
            assertTrue(setup.current); assertTrue(chat.current); assertTrue(mutation.current)
            assertTrue(commands.current); assertTrue(frame.current)
            assertEquals(AnalysisStart.Unavailable, setup.analyze(AnalyzeChoice.of(AnalyzePreset.Quick)))
            assertFalse(setup.update()); assertFalse(chat.send("A background question")); assertFalse(chat.newChat())
            retainedInput("An old field edit")
            rewrite.rewrite { startBrief(h.page, AiCapabilities.Full) }
            findings.post(listOf("finding-1"))
            finding.open()
            assertFalse(mutation.submit("COMMENT", "A retained confirmation", null) {})
            commands.toggleReviewed(file)
            assertFalse(frame.critique(null, { h.page.startTask("critique") }) {})
            assertFalse(progress.mark(file, true))
            thread.run({ pull -> h.page.repo.reply(pull, 11, "A retained reply") })
            var navigations = 0
            diff.ask { navigations++ }
            diff.toggleReviewed(h.page.files, {}, { navigations++ })
            runCurrent()
            assertTrue(calls.isEmpty())
            assertEquals(view, h.page.captureView())
            assertEquals("Keep this private draft", chatUi(h.page).chatInput)
            assertEquals(0, navigations)
            assertTrue(h.page.reviewed.isEmpty())
            assertNull(h.page.focus)
            ready = true
            assertTrue(setup.canCritique); assertTrue(chat.canAsk); assertTrue(rewrite.canRewrite)
            assertTrue(mutation.canSubmit); assertTrue(thread.canWrite)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
        }
    }
}
