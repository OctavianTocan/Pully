package com.octaviantocan.pulls.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountSettingsSyncTest {
    @Test
    fun oldLocalChoicesSeedAtOneAndAnExistingRemoteChoiceWins() = runTest {
        val h = Harness(this)
        h.settings.updateTheme("dark")
        h.response = """{"result":{"values":{"theme":{"value":"light","at":20}}}}"""
        h.sync.sync()
        val theme = h.outgoing().getValue("theme").jsonObject
        assertEquals("dark", theme.getValue("value").jsonPrimitive.content)
        assertEquals(1L, theme.getValue("at").jsonPrimitive.long)
        assertEquals("light", h.settings.theme)
        assertEquals(20L, h.store.timestamps["theme"])
        assertTrue(h.store.seeded)
        assertEquals("first-owner", h.store.owner)
    }

    @Test
    fun changingAccountsRetimesLocalChoicesSoTheNewAccountsValuesWin() = runTest {
        val h = Harness(this)
        h.store.seeded = true
        h.store.owner = "previous-owner"
        h.store.timestamps["theme"] = 500L
        h.settings.updateTheme("dark")
        h.response = """{"result":{"values":{"theme":{"value":"light","at":20}}}}"""
        h.sync.sync()
        assertEquals(1L, h.outgoing().getValue("theme").jsonObject.getValue("at").jsonPrimitive.long)
        assertEquals("light", h.settings.theme)
        assertEquals("first-owner", h.store.owner)
    }

    @Test
    fun aLocalEditDuringTheRequestCannotBeOverwrittenByItsReply() = runTest {
        val h = Harness(this)
        h.store.timestamps["theme"] = 10L
        h.response = """{"result":{"values":{"theme":{"value":"light","at":20}}}}"""
        val release = CompletableDeferred<Unit>()
        h.beforeResponse = { release.await() }
        val sync = backgroundScope.async { h.sync.sync() }
        runCurrent()
        h.settings.updateTheme("dark")
        h.sync.touched("theme", 30L)
        release.complete(Unit)
        runCurrent()
        assertTrue(sync.isCompleted)
        assertEquals("dark", h.settings.theme)
        assertEquals(30L, h.store.timestamps["theme"])
    }

    @Test
    fun anOldOwnersLateReplyCannotChangeTheReplacementOwnersLocalChoices() = runTest {
        val h = Harness(this)
        h.settings.updateTheme("dark")
        h.response = """{"result":{"values":{"theme":{"value":"light","at":500}}}}"""
        val release = CompletableDeferred<Unit>()
        h.beforeResponse = { withContext(NonCancellable) { release.await() } }
        val sync = backgroundScope.async { h.sync.sync() }
        runCurrent()
        h.owner = "replacement-owner"
        release.complete(Unit)
        runCurrent()
        assertTrue(sync.isCompleted)
        assertEquals("dark", h.settings.theme)
        assertEquals(1L, h.store.timestamps["theme"])
    }

    @Test
    fun aChangedServerSourceRejectsTheReplyEvenWhenTheEmailIsTheSame() = runTest {
        val h = Harness(this)
        h.settings.updateTheme("dark")
        h.response = """{"result":{"values":{"theme":{"value":"light","at":500}}}}"""
        val release = CompletableDeferred<Unit>()
        h.beforeResponse = { withContext(NonCancellable) { release.await() } }
        val sync = backgroundScope.async { h.sync.sync() }
        runCurrent()
        h.generation++
        release.complete(Unit)
        runCurrent()
        assertTrue(sync.isCancelled)
        assertEquals("dark", h.settings.theme)
    }

    @Test
    fun rapidLocalChoicesUseTheOriginalDebounceAndSendOnlyTheLatestValue() = runTest {
        val h = Harness(this)
        h.settings.updateTheme("light")
        h.sync.touched("theme", 10L)
        advanceTimeBy(500)
        h.settings.updateTheme("dark")
        h.sync.touched("theme", 20L)
        advanceTimeBy(1_499)
        runCurrent()
        assertTrue(h.requests.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, h.requests.size)
        assertEquals("dark", h.outgoing().getValue("theme").jsonObject.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun aDebounceForTheOldAccountDoesNotSendToTheNewAccount() = runTest {
        val h = Harness(this)
        h.sync.touched("theme", 10L)
        h.owner = "replacement-owner"
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(h.requests.isEmpty())
        assertEquals(10L, h.store.timestamps["theme"])
    }

    @Test
    fun unsupportedRemoteValuesAreNotAppliedOrMarkedAsAccepted() = runTest {
        val h = Harness(this)
        h.store.timestamps["theme"] = 10L
        h.response = """{"result":{"values":{"theme":{"value":"unknown-theme","at":20},"future-setting":{"value":true,"at":100}}}}"""
        h.sync.sync()
        assertEquals("system", h.settings.theme)
        assertEquals(10L, h.store.timestamps["theme"])
        assertFalse("future-setting" in h.store.timestamps)
    }

    @Test
    fun localOnlyChoicesNeverEnterTheWireEnvelope() = runTest {
        val h = Harness(this)
        h.settings.updateUsageStats(false)
        h.settings.updateUpdateNotifications(false)
        h.sync.touched("usageStats", 100L)
        h.sync.touched("updateNotifications", 100L)
        h.sync.sync()
        assertFalse("usageStats" in h.outgoing())
        assertFalse("updateNotifications" in h.outgoing())
        assertFalse("usageStats" in h.store.timestamps)
        assertFalse("updateNotifications" in h.store.timestamps)
    }

    @Test
    fun anOfflineMergeLeavesExistingChoicesAndTimesAvailableForRetry() = runTest {
        val h = Harness(this)
        h.settings.updateTheme("dark")
        h.store.timestamps["theme"] = 10L
        h.beforeResponse = { error("The fixture is offline.") }
        h.sync.sync()
        assertEquals("dark", h.settings.theme)
        assertEquals(10L, h.store.timestamps["theme"])
    }

    private class Harness(scope: TestScope) {
        val settings = AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
            override fun touched(name: String) {}
            override fun settingsChanged(key: String, value: String) {}
            override fun usageStatsEnabled(enabled: Boolean) {}
        }) { "en" }
        val store = Store()
        var owner: String? = "first-owner"
        var generation = 0L
        val requests = mutableListOf<RpcRequest>()
        var response = """{"result":{"values":{}}}"""
        var beforeResponse: suspend () -> Unit = {}
        private val repo = Repo({ RepoConfiguration("https://fixture.example", generation = generation) }, object : RpcTransport {
            override suspend fun execute(request: RpcRequest): RpcResponse {
                requests += request
                beforeResponse()
                return RpcResponse(200, response)
            }
        })
        val sync = AccountSettingsSync(settings, repo, store, { owner }, scope.backgroundScope) { 100L }
        fun outgoing(): JsonObject = Json.parseToJsonElement(requests.last().body).jsonObject.getValue("args").jsonObject.getValue("values").jsonObject
    }

    private class Store : SettingsSyncStore {
        override var seeded = false
        override var owner: String? = null
        val timestamps = mutableMapOf<String, Long>()
        override fun times(names: List<String>): Map<String, Long> = names.associateWith { timestamps[it] ?: 0L }
        override fun write(times: Map<String, Long>, seeded: Boolean?, owner: String?) {
            timestamps.putAll(times)
            seeded?.let { this.seeded = it }
            owner?.let { this.owner = it }
        }
    }
}
