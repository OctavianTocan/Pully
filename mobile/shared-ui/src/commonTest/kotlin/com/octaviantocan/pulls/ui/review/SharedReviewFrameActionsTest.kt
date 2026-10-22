package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.home.HomeLifecycleObserver
import com.octaviantocan.pulls.ui.home.HomeBackObserver
import com.octaviantocan.pulls.ui.settings.ModelConfiguration
import com.octaviantocan.pulls.ui.settings.SourceModelSettingsServices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedReviewFrameActionsTest {
    @Test
    fun theSharedFactoryUsesTheSameModelOwnerAndLivePolicyForTheActualCritiqueRequest() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val starts = mutableListOf<JsonObject>()
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> { starts += args; JsonPrimitive("shared-critique") }
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        val models = SourceModelSettingsServices(h.page.repo, MemorySettingsPreferences(),
            { ModelConfiguration(h.engine, h.model) }, { h.engine = it.engine; h.model = it.model }, { true })
        var ready = false
        var policy = AiCapabilities.Full
        val factory = SharedReviewFrameActions(models, TestNativeHomeActions, { true }, { ready }, { policy })
        factory.critique(h.page, "security"); runCurrent()
        assertTrue(starts.isEmpty())
        ready = true; policy = AiCapabilities.ReadOnly
        factory.critique(h.page, "security"); runCurrent()
        assertTrue(starts.isEmpty())
        policy = AiCapabilities.Full
        models.save(ModelConfiguration("claude-code", "same-owner-model"))
        factory.critique(h.page, "security"); runCurrent()
        val actual = starts.single()
        assertEquals("critique", actual.getValue("kind").jsonPrimitive.content)
        assertEquals("security", actual.getValue("lens").jsonPrimitive.content)
        assertEquals("same-owner-model", actual.getValue("model").jsonObject.getValue("model").jsonPrimitive.content)
        h.page.detach()
    }
}

private object TestNativeHomeActions : NativeHomeActions {
    override fun copyText(text: String) = error("Unexpected clipboard action")
    override fun openOnGithub(url: String) = error("Unexpected external action")
    override fun openInBrowser(url: String) = error("Unexpected browser action")
    override fun observeLifecycle(observer: HomeLifecycleObserver) = error("Unexpected lifecycle registration")
    override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver) = error("Unexpected Back registration")
}
