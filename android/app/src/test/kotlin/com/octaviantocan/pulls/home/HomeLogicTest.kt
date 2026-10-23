package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.ui.home.GhLabel
import com.octaviantocan.pulls.ui.home.GhUser
import com.octaviantocan.pulls.ui.home.HomeLink
import com.octaviantocan.pulls.ui.home.HomeLinks
import com.octaviantocan.pulls.ui.home.KeyValueStore
import com.octaviantocan.pulls.ui.home.LabelStyle
import com.octaviantocan.pulls.ui.home.MyPullsSync
import com.octaviantocan.pulls.ui.home.PullListQuery
import com.octaviantocan.pulls.ui.home.PullsSnapshot
import com.octaviantocan.pulls.ui.home.RepoPull
import com.octaviantocan.pulls.ui.home.TimeUnit
import com.octaviantocan.pulls.ui.home.asRepoInfo
import com.octaviantocan.pulls.ui.home.asRepoPullsPage
import com.octaviantocan.pulls.ui.home.asSnapshot
import com.octaviantocan.pulls.ui.home.relativeTime
import com.octaviantocan.pulls.ui.home.inShownRepos
import com.octaviantocan.pulls.ui.home.repoChoices
import com.octaviantocan.pulls.ui.settings.AiSettingsLogic
import com.octaviantocan.pulls.ui.settings.asCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSettingsLogicTest {
    private val catalog = Json.parseToJsonElement("""{"engine":"codex","version":"0.159.3","account":"Pro","models":[{"id":"gpt-6.1-sol","name":"GPT-6.1-Sol","description":"d","efforts":["low","medium","ultra"],"defaultEffort":"low","isDefault":true},{"id":"mini","name":"Mini","efforts":[]}]}""").jsonObject.asCatalog()

    @Test
    fun readsTheCatalogAndItsLine() {
        assertEquals(2, catalog.models.size)
        assertEquals("Pro · 0.159.3", catalog.line)
        assertTrue(catalog.models[0].isDefault)
        assertEquals("low", catalog.models[0].defaultEffort)
        // The CLIs send no group; Pi's server does (K2).
        assertNull(catalog.models[0].group)
        val pi = Json.parseToJsonElement("""{"engine":"pi","models":[{"id":"agy-gemini-3.8-flash","name":"Gemini 3.8 Flash","group":"AGY","efforts":[]}]}""").jsonObject.asCatalog()
        assertEquals("AGY", pi.models[0].group)
    }

    @Test
    fun keepsAnEffortOnlyWhenTheNewModelListsIt() {
        assertEquals("medium", AiSettingsLogic.keptEffort(catalog, "gpt-6.1-sol", "medium"))
        assertEquals("", AiSettingsLogic.keptEffort(catalog, "mini", "medium"))
        assertEquals("", AiSettingsLogic.keptEffort(catalog, "gpt-6.1-sol", ""))
    }

    @Test
    fun labelsEfforts() {
        assertEquals("ai.effort.xhigh", AiSettingsLogic.effortLabel("xhigh") { it })
        assertEquals("Turbo", AiSettingsLogic.effortLabel("turbo") { it })
    }

    @Test
    fun aBlankModelIsTheDefault() {
        assertEquals("gpt-6.1-sol", AiSettingsLogic.current(catalog, "")?.id)
        assertEquals("mini", AiSettingsLogic.current(catalog, "mini")?.id)
        assertNull(AiSettingsLogic.current(catalog, "custom"))
    }
}

/** The search pill's ✕ turns into the send circle only for a question (recording 16 Oct 12:10, 05:03 to 05:47; N8). */
class PullStateTest {
    @Test fun aRepositoryStateNamesItsTile() {
        assertEquals(com.octaviantocan.pulls.ui.home.PullState.Draft, com.octaviantocan.pulls.ui.home.pullStateOf("draft"))
        assertEquals(com.octaviantocan.pulls.ui.home.PullState.Merged, com.octaviantocan.pulls.ui.home.pullStateOf("merged"))
        assertEquals(com.octaviantocan.pulls.ui.home.PullState.Closed, com.octaviantocan.pulls.ui.home.pullStateOf("closed"))
        assertEquals(com.octaviantocan.pulls.ui.home.PullState.Open, com.octaviantocan.pulls.ui.home.pullStateOf("open"))
    }
}
