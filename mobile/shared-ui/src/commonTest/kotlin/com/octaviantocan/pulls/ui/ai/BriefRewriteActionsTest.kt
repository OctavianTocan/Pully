package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.core.BriefBlock
import com.octaviantocan.pulls.core.BriefPage
import com.octaviantocan.pulls.data.Brief
import com.octaviantocan.pulls.data.SavedState
import com.octaviantocan.pulls.ui.review.DiffHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun installBriefFixture(h: DiffHarness): Brief = Brief(
    BriefPage(listOf(BriefBlock.Text("A saved sample brief"))), "head-a", "sample-model", "2026-10-21T00:00:00Z",
).also { h.page.saved = SavedState(emptyList(), null, emptyList(), emptyList(), null, null, brief = it) }

@OptIn(ExperimentalCoroutinesApi::class)
class BriefRewriteActionsTest {
    @Test
    fun theActualCurrentRewriteStartsOnceWithTheReadersLocale() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val brief = installBriefFixture(h)
        val starts = mutableListOf<JsonObject>()
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> { starts += args; JsonPrimitive("brief-1") }
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        val actions = BriefRewriteActions(h.page, brief) { AiCapabilities.Full }
        actions.rewrite { startBrief(h.page, AiCapabilities.Full) }
        actions.rewrite { startBrief(h.page, AiCapabilities.Full) }
        runCurrent()
        assertEquals(1, starts.size)
        assertEquals("brief", starts.single().getValue("kind").jsonPrimitive.content)
        assertEquals("pull-1", starts.single().getValue("key").jsonPrimitive.content)
        assertEquals(h.page.settings.locale, starts.single().getValue("locale").jsonPrimitive.content)
        h.page.detach()
    }

    @Test
    fun retiredResultViewPolicySourceAndDisposedButtonsCannotRewrite() = runTest {
        for (replacement in listOf("result", "head-aba", "policy", "source", "disposed")) {
            val h = DiffHarness(backgroundScope)
            h.page.load(); runCurrent()
            val brief = installBriefFixture(h)
            var policy = AiCapabilities.Full
            val actions = BriefRewriteActions(h.page, brief) { policy }
            assertTrue(actions.canRewrite)
            when (replacement) {
                "result" -> h.page.saved = h.page.saved!!.copy(brief = brief.copy(generatedAt = "2026-10-21T01:00:00Z"))
                "head-aba" -> {
                    h.head = "head-b"; h.page.refresh(); runCurrent()
                    h.head = "head-a"; h.page.refresh(); runCurrent()
                    installBriefFixture(h)
                }
                "policy" -> policy = AiCapabilities.ReadOnly
                "source" -> h.generation++
                else -> actions.close()
            }
            var starts = 0
            h.handler = { name, args ->
                if (name == "ai-task-start") { starts++; JsonPrimitive("unexpected") } else h.default(name, args)
            }
            assertFalse(actions.canRewrite, replacement)
            actions.rewrite { startBrief(h.page, policy) }
            runCurrent()
            assertEquals(0, starts, replacement)
            h.page.detach()
        }
    }
}
