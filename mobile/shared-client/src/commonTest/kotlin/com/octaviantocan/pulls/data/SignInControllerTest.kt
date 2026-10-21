package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SignInControllerTest {
    @Test
    fun passwordLockoutKeepsTheVisibleWaitRecoveryWithoutReflectedCredentials() = runTest {
        val password = "correct\\quoted\"password\nvalue"
        var locked = true
        val accepted = mutableListOf<Pair<String, String>>()
        val repo = Repo({ RepoConfiguration("https://server.example") }, RpcTransport { request ->
            if (locked) RpcResponse(429, buildJsonObject {
                // The real account handler rejects a correct password during its 15-minute lockout.
                // Deliberately append serialized input to prove the recovery never echoes it.
                put("error", "Too many wrong passwords. Wait 15 minutes. ${Json.parseToJsonElement(request.body).jsonObject["args"]}")
            }.toString())
            else RpcResponse(200, """{"result":{"session":"fixture-session","email":"test@example.com"}}""")
        })
        val controller = SignInController(repo, repo.source(), { true }) { session, email -> accepted += session to email }
        val error = assertFailsWith<ServerError> { controller.password("test@example.com", password) }
        assertEquals(429, error.status)
        assertEquals(ServerErrorKind.Server, error.kind)
        // SignInPage presents ServerError.message directly and leaves the failed attempt uncommitted.
        assertEquals("Too many sign-in attempts. Wait 15 minutes before trying again.", error.message)
        assertFalse(error.message.orEmpty().contains("password"))
        assertFalse(error.message.orEmpty().contains("test@example.com"))
        assertTrue(accepted.isEmpty())
        locked = false
        controller.password("test@example.com", password)
        assertEquals(listOf("fixture-session" to "test@example.com"), accepted)
    }

    @Test
    fun passwordUsesTheExistingRpcAndCanCommitAnIdentityChange() = runTest {
        val h = Harness()
        h.controller.password("test@example.com", "fixture-password")
        assertEquals("account-sign-in", h.calls.single()["name"]?.toString()?.trim('"'))
        assertEquals(Json.parseToJsonElement("""{"email":"test@example.com","password":"fixture-password"}"""), h.calls.single()["args"])
        assertEquals(listOf("fixture-session" to "test@example.com"), h.accepted)
        assertEquals(1L, h.generation)
    }

    @Test
    fun anObsoletePageCannotStartAPasswordRequest() = runTest {
        val h = Harness()
        h.current = false
        assertFailsWith<CancellationException> { h.controller.password("test@example.com", "fixture-password") }
        assertTrue(h.calls.isEmpty())
        assertTrue(h.accepted.isEmpty())
    }

    @Test
    fun aRetainedOldFormCannotSendItsPasswordAfterServerReplacement() = runTest {
        val h = Harness()
        // The old UI owner may survive the modal's exit animation; source ownership is
        // still revoked synchronously, before recomposition can discard the old form.
        h.server = "https://another.example"
        h.generation++
        assertFailsWith<CancellationException> { h.controller.password("old-account@example.com", "old-server-password") }
        assertTrue(h.calls.isEmpty())
        assertTrue(h.accepted.isEmpty())
    }

    @Test
    fun aPasswordReplyFromThePreviousSourceCannotInstallItsSession() = runTest {
        val h = Harness()
        val response = CompletableDeferred<Unit>()
        h.beforeResponse = { withContext(NonCancellable) { response.await() } }
        val login = async { h.controller.password("test@example.com", "fixture-password") }
        runCurrent()
        h.server = "https://another.example"
        h.generation++
        response.complete(Unit)
        assertFailsWith<CancellationException> { login.await() }
        assertTrue(h.accepted.isEmpty())
    }

    @Test
    fun aGoogleAnswerFromThePreviousServerIsNeverSentToTheNewServer() = runTest {
        val h = Harness()
        val token = CompletableDeferred<String?>()
        val login = async { h.controller.google { token.await() } }
        runCurrent()
        h.server = "https://another.example"
        h.generation++
        token.complete("fixture-google-token")
        assertFailsWith<CancellationException> { login.await() }
        assertTrue(h.calls.isEmpty())
        assertTrue(h.accepted.isEmpty())
    }

    @Test
    fun dismissingGoogleLeavesTheAccountAndNetworkUntouched() = runTest {
        val h = Harness()
        assertFalse(h.controller.google { null })
        assertTrue(h.calls.isEmpty())
        assertTrue(h.accepted.isEmpty())
    }

    @Test
    fun anInvalidatedOwnerCannotAcceptALateReplyEvenWhenTheRepoSourceIsUnchanged() = runTest {
        val h = Harness()
        val response = CompletableDeferred<Unit>()
        h.beforeResponse = { withContext(NonCancellable) { response.await() } }
        val login = async { h.controller.password("test@example.com", "fixture-password") }
        runCurrent()
        assertEquals(1, h.calls.size)
        h.current = false
        response.complete(Unit)
        assertFailsWith<CancellationException> { login.await() }
        assertTrue(h.accepted.isEmpty())
    }

    @Test
    fun callerCancellationReleasesTheRequestWithoutAcceptingASession() = runTest {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        var detached = false
        h.beforeResponse = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { detached = true }
        }
        val login = async { h.controller.password("test@example.com", "fixture-password") }
        entered.await()
        login.cancelAndJoin()
        assertTrue(detached)
        assertTrue(h.accepted.isEmpty())
    }

    private class Harness {
        var server = "https://server.example"
        var generation = 0L
        var current = true
        var beforeResponse: suspend () -> Unit = {}
        val calls = mutableListOf<JsonObject>()
        val accepted = mutableListOf<Pair<String, String>>()
        private val repo = Repo(
            { RepoConfiguration(server, generation = generation) },
            object : RpcTransport {
                override suspend fun execute(request: RpcRequest): RpcResponse {
                    calls += Json.parseToJsonElement(request.body).jsonObject
                    beforeResponse()
                    return RpcResponse(200, """{"result":{"session":"fixture-session","email":"test@example.com"}}""")
                }
            },
        )
        val controller = SignInController(repo, repo.source(), { current }) { session, email ->
            accepted += session to email
            generation++
        }
    }
}
