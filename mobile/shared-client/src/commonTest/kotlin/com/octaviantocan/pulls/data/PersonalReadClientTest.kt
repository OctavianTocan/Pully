package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalReadClientTest {
    @Test
    fun aSavedOrProvisionalAccountCannotIssueAReadCapabilityBeforeVerification() = runTest {
        val h = Harness(this)
        assertNull(h.runtime.personalReads())
        h.vault.savePending(h.session)
        assertNull(h.runtime.personalReads())
        h.vault.promote(h.session)
        assertNull(h.runtime.personalReads())
        h.runtime.restore(); runCurrent()
        assertTrue(h.runtime.personalReads()!!.current)
        h.runtime.close()
    }

    @Test
    fun verifiedReadsUseTheFixedPersonalRoutesAndNeverThePublicGithubTransport() = runTest {
        val h = Harness(this)
        val reader = h.verify()
        h.respond = { json(h.repositories) }
        val repo = reader.repositories().repositories.single()
        assertEquals("acme/editor", repo.fullName)
        h.respond = { json(h.repositoryPulls) }
        assertEquals("https://github.com/acme/editor/pull/7", reader.pulls(repo).pulls.single().url)
        h.respond = { json(h.inbox) }
        assertEquals(7L, reader.pulls().pulls.single().number)
        val reads = h.readRequests()
        assertEquals(listOf("/repositories?page=1", "/repositories/acme/editor/pulls?page=1", "/pulls?relation=reviewRequested&page=1"),
            reads.map { it.url.removePrefix("https://accounts.example/api/personal/v1/github") })
        for (request in reads) {
            assertEquals(RpcMethod.Get, request.method)
            assertEquals("", request.body)
            assertEquals(setOf("Accept", "Cache-Control", "Authorization"), request.headers.keys)
            assertEquals("Bearer ${h.session.token}", request.headers["Authorization"])
            assertEquals(2 * 1024 * 1024, request.responseLimit)
            assertEquals(10_000, request.connectTimeoutMs)
            assertEquals(60_000L, request.timeoutMs)
        }
        h.runtime.close()
    }

    @Test
    fun theReturnedAccountSubjectAndApplicationAuthorityMustMatchTheCapturedCapability() = runTest {
        val h = Harness(this)
        val reader = h.verify()
        for (body in listOf(
            h.repositories.replace("account-42", "another-account"),
            h.repositories.replace("\"githubId\":42", "\"githubId\":99"),
            h.repositories.replace("publicRead", "privateRepositories"),
        )) {
            h.respond = { json(body) }
            assertEquals(PersonalReadProblem.InvalidResponse, assertFailsWith<PersonalReadError> { reader.repositories() }.problem)
        }
        h.runtime.close()
    }

    @Test
    fun nameReuseAndPrivateRepositoryDataCannotReplaceASelectedPublicRepository() = runTest {
        val h = Harness(this)
        val reader = h.verify()
        h.respond = { json(h.repositories) }
        val selected = reader.repositories().repositories.single()
        for (body in listOf(
            h.repositoryPulls.replace("\"id\":42", "\"id\":99"),
            h.repositoryPulls.replace("\"id\":42", "\"private\":true,\"id\":42"),
            h.repositoryPulls.replace("\"id\":42", "\"private\":\"false\",\"id\":42"),
            h.repositoryPulls.replace("https://github.com/acme/editor/pull/7", "https://foreign.example/pull/7"),
        )) {
            h.respond = { json(body) }
            assertEquals(PersonalReadProblem.InvalidResponse, assertFailsWith<PersonalReadError> { reader.pulls(selected) }.problem)
        }
        h.runtime.close()
    }

    @Test
    fun paginationAndRelationResponsesKeepTheRequestedBoundedRead() = runTest {
        val h = Harness(this)
        val reader = h.verify()
        for (page in listOf(0, 11)) assertEquals(PersonalReadProblem.InvalidRequest,
            assertFailsWith<PersonalReadError> { reader.repositories(page) }.problem)
        assertTrue(h.readRequests().isEmpty())
        for (body in listOf(
            h.repositories.replace("\"nextPage\":null", "\"nextPage\":7"),
            h.repositories.replace("\"nextPage\":null", "\"nextPage\":\"2\""),
            h.repositories.replace("\"nextPage\":null,", ""),
        )) {
            h.respond = { json(body) }
            assertEquals(PersonalReadProblem.InvalidResponse, assertFailsWith<PersonalReadError> { reader.repositories() }.problem)
        }
        h.respond = { json(h.inbox.replace("reviewRequested", "authored")) }
        assertEquals(PersonalReadProblem.InvalidResponse, assertFailsWith<PersonalReadError> { reader.pulls() }.problem)
        assertEquals(PersonalPullRelation.Authored, reader.pulls(PersonalPullRelation.Authored).relation)
        h.runtime.close()
    }

    @Test
    fun typedServiceErrorsKeepSafeRecoveryWithoutEchoingTheServerOrExpiringOnProxyErrors() = runTest {
        for ((status, code, problem) in listOf(
            Triple(400, "invalidRequest", PersonalReadProblem.InvalidRequest),
            Triple(401, "unauthorized", PersonalReadProblem.Unauthorized),
            Triple(401, "reconnect", PersonalReadProblem.Reconnect),
            Triple(403, "forbidden", PersonalReadProblem.Forbidden),
            Triple(429, "rateLimited", PersonalReadProblem.RateLimited),
            Triple(503, "unavailable", PersonalReadProblem.Unavailable),
            Triple(502, "invalidResponse", PersonalReadProblem.InvalidResponse),
        )) {
            val h = Harness(this)
            val reader = h.verify()
            h.respond = { json("""{"error":{"code":"$code","message":${JsonPrimitive("server-reflection=${h.session.token}")},"retryAfterSeconds":30}}""", status) }
            val error = assertFailsWith<PersonalReadError> { reader.repositories() }
            assertEquals(problem, error.problem)
            assertEquals(30, error.retryAfterSeconds)
            assertFalse(error.message.orEmpty().contains(h.session.token))
            assertEquals(problem != PersonalReadProblem.Unauthorized && problem != PersonalReadProblem.Reconnect, reader.current)
            h.runtime.close()
        }
        val h = Harness(this)
        val reader = h.verify()
        for ((reply, problem) in listOf(
            RpcResponse(401, "<html>private-proxy", "text/html") to PersonalReadProblem.WebPage,
            json("{}", 401) to PersonalReadProblem.HttpRejected,
            json("""{"error":{"code":"unauthorized"}}""", 401) to PersonalReadProblem.HttpRejected,
            RpcResponse(302, "", "text/plain", "https://foreign.example/?token=secret") to PersonalReadProblem.Redirect,
        )) {
            h.respond = { reply }
            assertEquals(problem, assertFailsWith<PersonalReadError> { reader.repositories() }.problem)
            assertTrue(reader.current)
            assertEquals(h.session.token, h.vault.accepted(PersonalSessionVaultTest.origin)!!.token)
        }
        h.runtime.close()
    }

    @Test
    fun aRetiredOwnerRejectsEvenAnUncancellableLateErrorResponse() = runTest {
        val h = Harness(this)
        val reader = h.verify()
        val gate = CompletableDeferred<Unit>()
        h.respond = { withContext(NonCancellable) { gate.await() }; json("""{"error":{"code":"unauthorized","message":"expired","retryAfterSeconds":null}}""", 401) }
        val read = async { reader.repositories() }
        runCurrent()
        h.runtime.close()
        gate.complete(Unit)
        assertFailsWith<CancellationException> { read.await() }
        assertFalse(reader.current)
        assertNull(h.runtime.personalReads())
    }

    @Test
    fun anOldHeldSessionRejectionCannotRetireANewlyVerifiedBearerForTheSameAccount() = runTest {
        val h = Harness(this)
        val old = h.verify()
        val gate = CompletableDeferred<Unit>()
        h.respond = {
            withContext(NonCancellable) { gate.await() }
            json("""{"error":{"code":"reconnect","message":"fixed","retryAfterSeconds":null}}""", 401)
        }
        val pending = async { old.repositories() }
        runCurrent()
        val replacement = PersonalSessionVaultTest.session(4)
        h.vault.savePending(replacement); h.vault.promote(replacement)
        h.runtime.restore(); runCurrent()
        val current = h.runtime.personalReads()!!
        assertTrue(current.current)
        gate.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertTrue(current.current)
        assertEquals(replacement.token, h.vault.accepted(PersonalSessionVaultTest.origin)!!.token)
        assertEquals(42L, h.runtime.view.value.account!!.githubId)
        h.runtime.close()
    }

    @Test
    fun replacingEvenTheSameAccountsVaultTokenRetiresAnOldReadCapabilityBeforeSending() = runTest {
        val h = Harness(this)
        val reader = h.verify()
        val replacement = PersonalSessionVaultTest.session(4)
        h.vault.savePending(replacement); h.vault.promote(replacement)
        assertFalse(reader.current)
        assertNull(h.runtime.personalReads(), "A new bearer requires its own verification, even for the same account")
        assertFailsWith<CancellationException> { reader.repositories() }
        assertFailsWith<CancellationException> { reader.repositories(page = 0) }
        assertTrue(h.readRequests().isEmpty())
        h.runtime.close()
    }

    @Test
    fun atMostThreeReadsOwnNetworkWorkAndCallerCancellationReleasesAdmission() = runTest {
        val h = Harness(this)
        val reader = h.verify()
        val gate = CompletableDeferred<Unit>()
        h.respond = { gate.await(); json(h.repositories) }
        val reads = List(3) { async { reader.repositories() } }
        runCurrent()
        assertEquals(3, h.readRequests().size)
        assertEquals(PersonalReadProblem.Unavailable, assertFailsWith<PersonalReadError> { reader.repositories() }.problem)
        reads.first().cancelAndJoin()
        val replacement = async { reader.repositories() }
        runCurrent()
        assertEquals(4, h.readRequests().size)
        gate.complete(Unit)
        reads.drop(1).forEach { assertEquals(42L, it.await().repositories.single().id) }
        assertEquals(42L, replacement.await().repositories.single().id)
        h.runtime.close()
    }

    @Test
    fun readDeadlinesAndBodyBoundsKeepFixedErrorsWithoutHoldingAdmission() = runTest {
        val h = Harness(this)
        val reader = h.verify()
        h.respond = { delay(60_001); json(h.repositories) }
        assertEquals(PersonalReadProblem.Timeout, assertFailsWith<PersonalReadError> { reader.repositories() }.problem)
        h.respond = { json("x".repeat(2 * 1024 * 1024 + 1)) }
        assertEquals(PersonalReadProblem.InvalidResponse, assertFailsWith<PersonalReadError> { reader.repositories() }.problem)
        h.respond = { json(h.repositories) }
        assertEquals(1, reader.repositories().repositories.size)
        h.runtime.close()
    }

    private class Harness(private val scope: TestScope) {
        val session = PersonalSessionVaultTest.session(3)
        val vault = PersonalSessionVault(PersonalSessionVaultTest.MemoryBlob())
        val network = PersonalAuthClientTest.Fixture()
        private val auth = network.respond
        private val owner = """"accountId":"account-42","githubId":42,"authority":"publicRead""""
        private val repository = """{"id":42,"owner":"acme","name":"editor","fullName":"acme/editor","description":null,"stars":3,"language":"Kotlin","url":"https://github.com/acme/editor"}"""
        private val pull = """"number":7,"title":"Improve reviews","url":"https://github.com/acme/editor/pull/7","author":"contributor","draft":false,"createdAt":"2026-10-21T10:00:00Z","updatedAt":"2026-10-22T10:00:00Z""""
        val repositories = """{$owner,"repositories":[$repository],"nextPage":null,"incomplete":false}"""
        val repositoryPulls = """{$owner,"repository":$repository,"pulls":[{$pull}],"nextPage":null,"incomplete":false}"""
        val inbox = """{$owner,"relation":"reviewRequested","pulls":[{"repository":$repository,$pull}],"nextPage":null,"incomplete":false}"""
        var respond: suspend (RpcRequest) -> RpcResponse = { json(repositories) }
        val runtime = PersonalAccountRuntime(PersonalSessionVaultTest.origin, vault, scope.backgroundScope,
            scope.backgroundScope, { 1200 + scope.testScheduler.currentTime / 1000 }, { true }, {}, RpcTransport { request ->
                network.requests += request
                if (request.url.startsWith("https://accounts.example/api/personal/v1/github/")) respond(request) else auth(request)
            })

        fun verify(): PersonalReadClient {
            vault.savePending(session); vault.promote(session)
            runtime.restore(); scope.runCurrent()
            return runtime.personalReads()!!
        }
        fun readRequests() = network.requests.filter { it.url.startsWith("https://accounts.example/api/personal/v1/github/") }
    }

    companion object {
        private fun json(body: String, status: Int = 200) = RpcResponse(status, body, "application/json")
    }
}
