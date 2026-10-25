package com.octaviantocan.pulls.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalWorkspaceTest {
    @Test
    fun onlyFreshRepositoryAuthorizedIdentityCanCreateTheFullNativeWorkspace() = runTest {
        val h = Harness(this)
        assertNull(h.runtime.workspace())
        h.runtime.restore(); runCurrent()
        assertNull(h.runtime.workspace(), "A verified public-only account has no full workspace")
        h.private = true
        h.acceptPrivate()
        h.runtime.restore(); runCurrent()
        val workspace = assertNotNull(h.runtime.workspace())
        assertEquals("account-42", workspace.accountId)
        assertEquals("reader", workspace.login)
        workspace.repo.accountStatus()
        val rpc = h.requests.last()
        assertEquals("https://accounts.example/workspace/api/rpc", rpc.url)
        assertEquals("Bearer ${h.vault.accepted(h.origin)!!.token}", rpc.headers["Authorization"])
        assertEquals("fixture-access", rpc.headers["CF-Access-Client-Secret"])
        assertFalse("X-Pully-Session" in rpc.headers)
        assertTrue(h.requests.filter { "/api/personal/" in it.url }.all { request ->
            request.headers.keys.none { it.startsWith("CF-Access", true) }
        })
        h.runtime.close()
    }

    @Test
    fun backgroundAndSignOutRetireCapturedWorkspaceAndRejectLateData() = runTest {
        val h = Harness(this)
        h.private = true; h.acceptPrivate(); h.runtime.restore(); runCurrent()
        val old = assertNotNull(h.runtime.workspace())
        h.runtime.foreground(false)
        assertFalse(old.current)
        assertNull(h.runtime.workspace())
        h.runtime.foreground(true)
        val resumed = assertNotNull(h.runtime.workspace())
        assertFalse(old.current, "A retired capability never revives")
        val held = CompletableDeferred<Unit>()
        h.hold = held
        val result = backgroundScope.async { resumed.repo.accountStatus() }
        runCurrent()
        h.runtime.signOut()
        assertFalse(resumed.current)
        assertNull(h.runtime.workspace())
        held.complete(Unit); runCurrent()
        assertTrue(result.isCancelled, "A late successful RPC cannot return after sign-out")
        assertNull(h.vault.accepted(h.origin))
        h.runtime.close()
    }

    @Test
    fun productionHomeToursProfileSettingsAndReviewUseOneCapturedAccountWithoutAnotherLogin() = runTest {
        val h = Harness(this)
        h.private = true; h.acceptPrivate(); h.runtime.restore(); runCurrent()
        val workspace = assertNotNull(h.runtime.workspace())
        for (method in listOf("my-pulls-cached", "my-pulls", "recent-diffs", "github-stats", "account-me", "account-settings", "ai-login-status", "github-pr", "ai-task-start"))
            workspace.repo.call(method)
        val calls = h.requests.filter { "/workspace/" in it.url }
        assertEquals(9, calls.size)
        assertTrue(calls.all { it.url == "https://accounts.example/workspace/api/rpc" && "Authorization" in it.headers && "X-Pully-Session" !in it.headers })
        assertFalse(calls.any { Json.parseToJsonElement(it.body).jsonObject.getValue("name").jsonPrimitive.content in
            setOf("account-sign-in", "account-google-sign-in") })
        h.runtime.close()
    }

    @Test
    fun accountReplacementAndSourceRetirementCannotReuseTheOldRepository() = runTest {
        val h = Harness(this)
        h.private = true; h.acceptPrivate(); h.runtime.restore(); runCurrent()
        val first = assertNotNull(h.runtime.workspace())
        var retired = false
        first.onRetired { retired = true }
        h.acceptPrivate(number = 3, accountId = "account-99", githubId = 99)
        assertFalse(first.current)
        h.accountId = "account-99"; h.githubId = 99
        h.runtime.restore(); runCurrent()
        assertTrue(retired)
        val second = assertNotNull(h.runtime.workspace())
        assertEquals("account-99", second.accountId)
        assertNotEquals(first.accountId, second.accountId)
        val before = h.requests.size
        assertFailsWith<kotlinx.coroutines.CancellationException> { first.repo.accountStatus() }
        assertEquals(before, h.requests.size)
        h.sourceCurrent = false
        assertFalse(second.current)
        assertFailsWith<kotlinx.coroutines.CancellationException> { second.repo.accountStatus() }
        assertNull(h.runtime.workspace())
        h.runtime.close()
    }

    @Test
    fun anAccessWebPageKeepsIdentityButAnActualWorkspaceRejectionRetiresIt() = runTest {
        val h = Harness(this)
        h.private = true; h.acceptPrivate(); h.runtime.restore(); runCurrent()
        val workspace = assertNotNull(h.runtime.workspace())
        h.rpcResponse = RpcResponse(401, "<html>Access</html>", "text/html")
        assertFailsWith<ServerError> { workspace.repo.accountStatus() }
        assertTrue(workspace.current)
        assertNotNull(h.runtime.view.value.account)
        h.rpcResponse = RpcResponse(401, """{"error":"Unauthorized"}""", "application/json")
        assertFailsWith<kotlinx.coroutines.CancellationException> { workspace.repo.accountStatus() }
        assertFalse(workspace.current)
        assertNull(h.runtime.view.value.account)
        assertNull(h.runtime.workspace())
        h.runtime.close()
    }

    @Test
    fun retryableAndScopeErrorsKeepTheAcceptedSessionAndRetryWithTheSameBearer() = runTest {
        for (status in listOf(503, 429, 403)) {
            val h = Harness(this)
            h.private = true; h.acceptPrivate(); h.runtime.restore(); runCurrent()
            val workspace = assertNotNull(h.runtime.workspace())
            val accepted = assertNotNull(h.vault.accepted(h.origin))
            h.rpcResponse = RpcResponse(status, """{"error":"Temporarily unavailable or insufficient scope"}""", "application/json")
            assertEquals(status, assertFailsWith<ServerError> { workspace.repo.accountStatus() }.status)
            assertTrue(workspace.current, "HTTP $status must not impersonate sign-out")
            assertEquals(accepted.token, assertNotNull(h.vault.accepted(h.origin)).token)
            assertNotNull(h.runtime.view.value.account)
            h.rpcResponse = RpcResponse(200, """{"result":{"hasAccount":true}}""", "application/json")
            workspace.repo.accountStatus()
            assertEquals(listOf("Bearer ${accepted.token}", "Bearer ${accepted.token}"),
                h.requests.filter { "/workspace/" in it.url }.map { it.headers["Authorization"] })
            h.runtime.close()
        }
    }

    @Test
    fun presentationRetainsItsSourceAcrossPauseButEachTransportIsIrrevocablyRetired() = runTest {
        val h = Harness(this)
        h.private = true; h.acceptPrivate(); h.runtime.restore(); runCurrent()
        val owner = assertNotNull(h.runtime.workspaceOwner())
        val repo = owner.repository()
        val source = repo.source()
        val old = assertNotNull(h.runtime.workspace())
        var retired = false
        owner.onRetired { retired = true }
        val held = CompletableDeferred<Unit>()
        h.hold = held
        val pending = backgroundScope.async { repo.accountStatus() }
        runCurrent()
        h.runtime.foreground(false)
        assertFalse(old.current)
        assertTrue(owner.current)
        assertSame(owner, h.runtime.workspaceOwner())
        assertEquals(source, repo.source(), "Pausing dispatch must retain presentation identity")
        assertFalse(retired)
        val count = h.requests.size
        assertFailsWith<kotlinx.coroutines.CancellationException> { repo.accountStatus() }
        assertEquals(count, h.requests.size)
        h.runtime.foreground(true)
        assertNotSame(old, h.runtime.workspace())
        held.complete(Unit); runCurrent()
        assertTrue(pending.isCancelled, "A resumed transport must not admit an older response")
        h.hold = null
        repo.accountStatus()
        assertEquals(source, repo.source())
        h.runtime.signOut()
        assertTrue(retired)
        assertFalse(owner.current)
        assertNull(h.runtime.workspaceOwner())
        assertFailsWith<kotlinx.coroutines.CancellationException> { repo.accountStatus() }
        h.runtime.close()
    }

    @Test
    fun nativeMountRetirementRejectsItsLateReplyEvenWhenTheSameSessionStillOwnsExplore() = runTest {
        val h = Harness(this)
        h.private = true; h.acceptPrivate(); h.runtime.restore(); runCurrent()
        val owner = assertNotNull(h.runtime.workspaceOwner())
        var admitted = true
        val repo = owner.repository(admitted = { admitted })
        val source = repo.source()
        val held = CompletableDeferred<Unit>()
        h.hold = held
        val pending = backgroundScope.async { repo.accountStatus() }
        runCurrent()
        admitted = false
        assertFalse(repo.isCurrent(source))
        held.complete(Unit); runCurrent()
        assertTrue(pending.isCancelled)
        assertTrue(owner.current, "Explore may retain the separately verified account")
        assertFailsWith<kotlinx.coroutines.CancellationException> { repo.accountStatus() }
        h.runtime.close()
    }

    @Test
    fun replacementRetiresThePresentationBeforeAnotherAccountCanDispatch() = runTest {
        val h = Harness(this)
        h.private = true; h.acceptPrivate(); h.runtime.restore(); runCurrent()
        val old = assertNotNull(h.runtime.workspaceOwner())
        val repo = old.repository()
        var cleared = false
        old.onRetired { cleared = true }
        h.acceptPrivate(3, "account-99", 99)
        h.accountId = "account-99"; h.githubId = 99
        h.runtime.restore(); runCurrent()
        assertTrue(cleared)
        assertFalse(old.current)
        val replacement = assertNotNull(h.runtime.workspaceOwner())
        assertEquals("account-99", replacement.accountId)
        val count = h.requests.size
        assertFailsWith<kotlinx.coroutines.CancellationException> { repo.accountStatus() }
        assertEquals(count, h.requests.size)
        replacement.repository().accountStatus()
        h.sourceCurrent = false
        assertFalse(replacement.current)
        assertNull(h.runtime.workspaceOwner())
        h.runtime.close()
    }

    @Test
    fun sharedUsageQueueSendsOnlyItsOwnSanitizedBatchToWorkspaceEvents() = runTest {
        val h = Harness(this)
        h.private = true; h.acceptPrivate(); h.runtime.restore(); runCurrent()
        val workspace = assertNotNull(h.runtime.workspace())
        var saved = emptyList<String>()
        val metadata = mutableMapOf<String, String>()
        val queue = UsageAnalyticsQueue(object : AnalyticsDisk {
            override fun read() = saved
            override fun replace(lines: List<String>) { saved = lines }
            override fun metadata(name: String): String? = metadata[name]
            override fun metadata(name: String, value: String?) { if (value == null) metadata.remove(name) else metadata[name] = value }
        }, AnalyticsIo { it() }, "android", "fixture", { "2026-10-25T12:00:00Z" },
            { "12345678-1234-1234-1234-123456789abc" }, { workspace.current }, workspace::postEvents, true)
        queue.track("screen.view", mapOf("screen" to "home", "authorization" to "must-not-upload"))
        h.rpcResponse = RpcResponse(204, "")
        assertTrue(queue.flush())
        val events = h.requests.last()
        assertEquals("https://accounts.example/workspace/api/events", events.url)
        assertEquals("Bearer ${h.vault.accepted(h.origin)!!.token}", events.headers["Authorization"])
        assertFalse(events.body.contains("must-not-upload"))
        assertTrue(events.body.contains("\"events\""))
        assertEquals(0, queue.size)
        h.runtime.foreground(false)
        val before = h.requests.size
        queue.track("screen.view", mapOf("screen" to "settings"))
        assertFalse(queue.flush())
        assertEquals(before, h.requests.size)
        queue.close(); h.runtime.close()
    }

    private class Harness(scope: TestScope) {
        val origin = PersonalSessionVaultTest.origin
        val session = PersonalSessionVaultTest.session(1)
        val vault = PersonalSessionVault(PersonalSessionVaultTest.MemoryBlob())
        val requests = mutableListOf<RpcRequest>()
        var private = false
        var sourceCurrent = true
        var accountId = "account-42"
        var githubId = 42L
        var rpcResponse = RpcResponse(200, """{"result":{"hasAccount":true}}""")
        var hold: CompletableDeferred<Unit>? = null
        val runtime = PersonalAccountRuntime(origin, vault, scope.backgroundScope, scope.backgroundScope,
            { 1200 }, { sourceCurrent }, {}, RpcTransport { request ->
                requests += request
                if ("/workspace/" in request.url) {
                    hold?.let { withContext(NonCancellable) { it.await() } }
                    rpcResponse
                } else if (request.url.endsWith("/sign-out")) RpcResponse(200, "true", "application/json")
                else RpcResponse(200, """{"accountId":"$accountId","githubId":$githubId,"login":"reader","authority":"${if (private) "privateRepositories" else "publicRead"}","sessionExpiresAt":3600,"accessExpiresAt":null,"refreshAvailable":false}""", "application/json")
            }, workspaceHeaders = { mapOf("CF-Access-Client-Secret" to "fixture-access") })
        init { vault.savePending(session); vault.promote(session) }
        fun acceptPrivate(number: Int = 2, accountId: String = "account-42", githubId: Long = 42) {
            val updated = PersonalSession(session.token, session.status.copy(authority = PersonalAuthority.PrivateRepositories, accountId = accountId, githubId = githubId), session.origin)
            // A new confirmed token upgrades the canonical secure-vault grant.
            val upgraded = PersonalSession(PersonalSessionVaultTest.session(number).token, updated.status, updated.origin)
            vault.savePending(upgraded); vault.promote(upgraded)
        }
    }
}
