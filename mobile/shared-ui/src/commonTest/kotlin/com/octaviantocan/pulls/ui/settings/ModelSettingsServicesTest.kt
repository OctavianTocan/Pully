package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelSettingsServicesTest {
    @Test
    fun realCatalogRpcPersistsImmediateNamesAndIndependentEngineMemory() = runTest {
        val h = Harness()
        assertNull(h.services.cached("codex"))
        val catalog = h.services.fetch("codex").getOrThrow()
        assertEquals("Sol", catalog.models.single().name)
        assertTrue(h.body.orEmpty().contains("ai-models"))
        assertEquals("Sol", h.services.cached("codex")?.models?.single()?.name)
        h.services.remember("codex", "sol")
        h.services.remember("pi", "agy")
        assertEquals("sol", h.services.remembered("codex"))
        assertEquals("agy", h.services.remembered("pi"))
        h.services.save(ModelConfiguration("codex", "sol"))
        assertEquals(ModelConfiguration("codex", "sol"), h.config)
    }

    @Test
    fun aRetiredOwnerCannotWriteChoicesOrStartARequest() = runTest {
        val h = Harness()
        h.active = false
        assertFailsWith<CancellationException> { h.services.save(ModelConfiguration("pi", "other")) }
        assertFailsWith<CancellationException> { h.services.remember("pi", "other") }
        assertFailsWith<CancellationException> { h.services.fetch("pi") }
        assertNull(h.body)
        assertEquals(ModelConfiguration("claude-code", ""), h.config)
    }

    @Test
    fun aLateNoncancellableCatalogCannotPopulateAReplacementOwner() = runTest {
        val h = Harness()
        val waiting = CompletableDeferred<Unit>()
        h.wait = { withContext(NonCancellable) { waiting.await() } }
        val fetch = async { h.services.fetch("codex") }
        runCurrent()
        h.generation++
        waiting.complete(Unit)
        assertFailsWith<CancellationException> { fetch.await() }
        assertNull(h.prefs.getString("catalog:codex", null))
        assertNull(h.services.cached("codex"))
    }

    @Test
    fun invalidSavedServerKeepsSettingsEditableAndReportsTheCatalogFailureWithoutRpc() = runTest {
        val h = Harness("not a server")
        assertEquals(ModelConfiguration("claude-code", ""), h.services.configuration())
        assertNull(h.services.cached("codex"))
        assertTrue(h.services.fetch("codex").isFailure)
        assertNull(h.body)
        h.services.save(ModelConfiguration("codex", "sol"))
        assertEquals(ModelConfiguration("codex", "sol"), h.config)
    }

    private class Harness(server: String = "https://models.example") {
        var generation = 0L
        var active = true
        var config = ModelConfiguration("claude-code", "")
        var body: String? = null
        var wait: suspend () -> Unit = {}
        val prefs = MemorySettingsPreferences()
        val repo = Repo({ RepoConfiguration(server, generation = generation) }, RpcTransport {
            body = it.body
            wait()
            RpcResponse(200, """{"result":{"models":[{"id":"sol","name":"Sol","efforts":["high"],"defaultEffort":"high","isDefault":true}]}}""")
        })
        val services = SourceModelSettingsServices(repo, prefs, { config }, { config = it }, { active })
    }
}
