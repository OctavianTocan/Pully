package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.core.AnalyzePreset
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.ui.review.DiffHarness
import com.octaviantocan.pulls.ui.review.NoNodes
import com.octaviantocan.pulls.ui.settings.ModelConfiguration
import com.octaviantocan.pulls.ui.settings.SourceModelSettingsServices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The same remembered setup actions that the actual sheet's buttons retain. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSetupOwnershipTest {
    @Test
    fun retainedButtonsUseLatestPolicyAndCannotReviveAfterHeadAbaOrDisposal() = runTest {
        val preferences = MemorySettingsPreferences()
        val h = DiffHarness(backgroundScope, preferences)
        val kinds = mutableListOf<String>()
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> { kinds += args.getValue("kind").jsonPrimitive.content; JsonPrimitive("task-${kinds.size}") }
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        val models = SourceModelSettingsServices(h.page.repo, MemorySettingsPreferences(),
            { ModelConfiguration("claude-code", "") }, {}, { true })
        var policy by mutableStateOf(AiCapabilities.Full)
        var mounted: AiSetupActions? = null
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
                    mounted = rememberAiSetupActions(h.page, models)
                }
            }
            runCurrent(); frame()
            val unloaded = assertNotNull(mounted)
            val quick = AnalyzeChoice.of(AnalyzePreset.Quick)
            assertTrue(unloaded.canAnalyze(quick))
            assertFalse(unloaded.canStartAnalysis(quick))
            assertEquals(AnalysisStart.Unavailable, unloaded.analyze(quick))
            runCurrent(); assertTrue(kinds.isEmpty())
            h.page.load(); runCurrent(); frame()
            val original = assertNotNull(mounted)
            assertNotSame(unloaded, original)
            assertEquals(AnalysisStart.Unavailable, unloaded.analyze(quick))
            policy = AiCapabilities.ReadOnly; frame()
            assertSame(original, mounted)
            assertEquals(AnalysisStart.Unavailable, original.analyze(quick))
            assertFalse(original.update()); runCurrent(); assertTrue(kinds.isEmpty())
            policy = AiCapabilities.Full; frame()
            assertEquals(AnalysisStart.Started, original.analyze(quick))
            assertEquals(quick, AnalyzeChoice.decode(preferences.getString("analyzeChoice", null)))
            runCurrent(); assertEquals(listOf("analyze"), kinds)
            h.head = "head-b"; h.page.refresh(); runCurrent(); frame()
            h.head = "head-a"; h.page.refresh(); runCurrent(); frame()
            val replacement = assertNotNull(mounted)
            assertNotSame(original, replacement)
            assertFalse(original.update())
            assertTrue(replacement.update()); runCurrent()
            assertEquals(listOf("analyze", "tour"), kinds)
            composition.dispose()
            assertFalse(replacement.current)
            var navigated = false
            replacement.ask { navigated = true }
            assertFalse(navigated)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
        }
    }
}
