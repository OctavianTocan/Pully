package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountPagesServicesTest {
    @Test
    fun theActualKeyMethodsKeepTheServerPayloadAndCopyOnlyTheOneTimeValue() = runTest {
        val h = Harness()
        assertEquals("key-1", h.services.keys().single().id)
        val made = h.services.create("Laptop")
        assertEquals("fixture-key", made.first)
        assertEquals("Laptop", h.arguments.last()?.get("name")?.jsonPrimitive?.content)
        assertTrue(h.services.copy(made.first))
        assertEquals(listOf("fixture-key"), h.copied)
        h.services.revoke(made.second.id)
        assertEquals("key-1", h.arguments.last()?.get("id")?.jsonPrimitive?.content)
        assertEquals(listOf("agent-keys", "agent-key-create", "agent-key-revoke"), h.methods)
    }

    @Test
    fun retiringAnOwnerClearsItsOneTimeSecretAndDisablesRetainedActions() = runTest {
        val h = Harness()
        val made = h.services.create("Laptop")
        h.services.ui.made = made
        h.services.ui.opened = made.second
        h.services.ui.keys = listOf(made.second)
        h.services.close()
        assertNull(h.services.ui.made)
        assertNull(h.services.ui.opened)
        assertNull(h.services.ui.keys)
        assertFalse(h.services.copy(made.first))
        assertTrue(h.copied.isEmpty())
        assertFailsWith<CancellationException> { h.services.create("Obsolete") }
        assertFailsWith<CancellationException> { h.services.revoke("key-1") }
        assertFailsWith<CancellationException> { h.services.signOut() }
        assertEquals(0, h.signedOut)
        assertEquals(1, h.methods.size)
    }

    @Test
    fun aLateNoncancellableNewKeyCannotEnterTheReplacementAccount() = runTest {
        val h = Harness()
        val waiting = CompletableDeferred<Unit>()
        h.wait = { withContext(NonCancellable) { waiting.await() } }
        val creating = async { h.services.create("Laptop") }
        runCurrent()
        h.generation++
        h.services.close()
        waiting.complete(Unit)
        assertFailsWith<CancellationException> { creating.await() }
        assertNull(h.services.ui.made)
        assertTrue(h.copied.isEmpty())
    }

    @Test
    fun successfulSignOutCanDeliberatelyRetireItsOwnSource() = runTest {
        val h = Harness()
        h.services.signOut()
        assertEquals(1, h.signedOut)
        assertFalse(h.services.current())
    }

    @Test
    fun nativeSignOutFailureStaysVisibleAndCanBeRetriedWithoutLosingTheAccount() = runTest {
        val h = Harness()
        val page = AccountSignOutState(h.services)
        h.signOutFailure = IllegalStateException("The fixture secure store is unavailable.")
        page.signOut()
        assertEquals("Pully could not sign out. Try again.", page.problem)
        assertFalse(page.busy)
        assertTrue(h.services.current())
        assertEquals(0, h.signedOut)
        h.signOutFailure = null
        page.signOut()
        assertNull(page.problem)
        assertFalse(page.busy)
        assertFalse(h.services.current())
        assertEquals(1, h.signedOut)
    }

    @Test
    fun cancelledSignOutCannotPublishAnErrorOrBecomeAStuckBusyRow() = runTest {
        val h = Harness()
        val page = AccountSignOutState(h.services)
        h.signOutFailure = CancellationException("The fixture left the account page.")
        assertFailsWith<CancellationException> { page.signOut() }
        assertNull(page.problem)
        assertFalse(page.busy)
        page.close()
        h.signOutFailure = null
        page.signOut()
        assertEquals(0, h.signedOut)
    }

    private class Harness {
        var generation = 0L
        var signedOut = 0
        var signOutFailure: Exception? = null
        val methods = mutableListOf<String>()
        val arguments = mutableListOf<kotlinx.serialization.json.JsonObject?>()
        val copied = mutableListOf<String>()
        var wait: suspend () -> Unit = {}
        private val item = """{"id":"key-1","name":"Laptop","hint":"fixture","createdAt":"2026-10-21T17:00:00Z"}"""
        private val repo = Repo({ RepoConfiguration("https://account.example", generation = generation) }, RpcTransport {
            val request = Json.parseToJsonElement(it.body).jsonObject
            val name = request["name"]!!.jsonPrimitive.content
            methods += name
            arguments += request["args"]?.jsonObject
            wait()
            RpcResponse(200, when (name) {
                "agent-keys" -> """{"result":[$item]}"""
                "agent-key-create" -> """{"result":{"key":"fixture-key","item":$item}}"""
                else -> """{"result":null}"""
            })
        })
        val services = SourceAccountPagesServices(repo, { "reader@example.com" }, { true }, { signOutFailure?.let { throw it }; signedOut++; generation++ }, { copied += it })
    }
}
