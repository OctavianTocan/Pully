package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalRepositoryConsentTest {
    @Test
    fun aPausedNativeOwnerCannotStartFreshConsentBeforeAUiFrame() = runTest {
        val h = Harness(this)
        h.verify()
        h.runtime.foreground(false)
        val action = h.runtime.openRepositoryConsent(backgroundScope) { error("A paused owner opened a browser") }!!
        assertFalse(action.controller.start())
        assertTrue(h.requests.none { it.url.endsWith("/repositories/start") })
        h.runtime.foreground(true)
        h.runtime.close()
    }

    @Test
    fun repositoryConsentIsADistinctAuthenticatedPurposeWithTheSameProofAndCallback() = runTest {
        val h = Harness(this)
        h.verify()
        val browser = mutableListOf<String>()
        val action = h.runtime.openRepositoryConsent(backgroundScope, browser::add)!!
        action.controller.start(); runCurrent()
        assertTrue(action.repositoryConsent)
        assertEquals(listOf(h.privateAuthorize), browser)
        val start = h.requests.single { it.url.endsWith("/repositories/start") }
        assertEquals("Bearer ${h.original.token}", start.headers["Authorization"])
        assertEquals(setOf("nativeChallenge"), Json.parseToJsonElement(start.body).jsonObject.keys)
        assertEquals(setOf("Accept", "Cache-Control", "Authorization"), start.headers.keys)
        assertFalse(start.body.contains(h.original.token))
        assertEquals(PersonalAuthority.PrivateRepositories, h.runtime.view.value.account!!.authority)
        assertEquals(PersonalAuthority.PrivateRepositories, h.vault.accepted(h.origin)!!.authority)
        assertEquals(1, h.identityChanges)
        assertTrue(h.requests.none { it.url.endsWith("/cancel") })
        assertEquals("Bearer ${h.original.token}", h.requests.single { it.url.endsWith("/sign-out") }.headers["Authorization"])
        h.runtime.close()
    }

    @Test
    fun anUpgradeCannotPublishPrivateReadsBeforeSecureStagingAndBearerConfirmation() = runTest {
        val h = Harness(this)
        h.verify()
        val originalReader = h.runtime.personalReads()!!
        val held = CompletableDeferred<Unit>()
        h.confirmationGate = held
        val action = h.runtime.openRepositoryConsent(backgroundScope) {}!!
        action.controller.start(); runCurrent()
        assertEquals(PersonalSignInState.Finishing, action.controller.state.value)
        assertEquals(h.original.token, h.vault.accepted(h.origin)!!.token)
        assertNull(h.runtime.accountReads())
        assertTrue(originalReader.current)
        val restarted = PersonalSessionVault(h.storage)
        assertEquals(h.original.token, restarted.accepted(h.origin)!!.token)
        assertEquals(PersonalAuthority.PrivateRepositories, restarted.pending().single().authority)
        var retired = false
        h.runtime.onReadsRetired { retired = true }
        held.complete(Unit); runCurrent()
        assertTrue(retired)
        assertFalse(originalReader.current)
        assertTrue(h.runtime.accountReads()!!.current)
        assertTrue(h.vault.pending().isEmpty())
        h.runtime.close()
    }

    @Test
    fun wrongSubjectReducedGrantOrWrongConsentUrlCannotReplaceTheExistingPublicAccount() = runTest {
        for (change in listOf("subject", "grant", "url")) {
            val h = Harness(this)
            h.verify()
            when (change) {
                "subject" -> h.claimStatus = h.claimStatus.replace("\"githubId\":42", "\"githubId\":99")
                "grant" -> h.claimStatus = h.claimStatus.replace("privateRepositories", "publicRead")
                "url" -> h.authorize = h.privateAuthorize.replace("repo+offline_access", "repo+admin%3Aorg+offline_access")
            }
            val browser = mutableListOf<String>()
            val action = h.runtime.openRepositoryConsent(backgroundScope, browser::add)!!
            action.controller.start(); runCurrent()
            assertTrue(action.controller.state.value is PersonalSignInState.Failed)
            assertEquals(h.original.token, h.vault.accepted(h.origin)!!.token)
            assertEquals(PersonalAuthority.PublicRead, h.runtime.view.value.account!!.authority)
            assertNull(h.runtime.accountReads())
            assertEquals(0, h.identityChanges)
            if (change != "url") assertEquals(1, h.requests.count { it.url.endsWith("/cancel") })
            else assertTrue(browser.isEmpty())
            h.runtime.close()
        }
    }

    @Test
    fun signOutDuringAnUncancellableUpgradeCannotPromoteItsLateClaim() = runTest {
        val h = Harness(this)
        h.verify()
        val held = CompletableDeferred<Unit>()
        h.claimGate = held
        val action = h.runtime.openRepositoryConsent(backgroundScope) {}!!
        action.controller.start(); runCurrent()
        h.runtime.signOut(); runCurrent()
        held.complete(Unit); runCurrent()
        assertNull(h.runtime.view.value.account)
        assertNull(h.vault.accepted(h.origin))
        assertNull(h.runtime.accountReads())
        assertEquals(1, h.requests.count { it.url.endsWith("/cancel") })
        h.runtime.close()
    }

    @Test
    fun olderVaultRecordsStayPublicAndPrivateAuthorityMustMatchTheSecureRecord() = runTest {
        val h = Harness(this)
        h.verify()
        val root = Json.parseToJsonElement(h.storage.bytes!!.decodeToString()).jsonObject
        h.storage.bytes = JsonObject(root + ("accepted" to JsonObject(root["accepted"]!!.jsonObject - "authority"))).toString().encodeToByteArray()
        val reopened = PersonalSessionVault(h.storage)
        assertEquals(PersonalAuthority.PublicRead, reopened.accepted(h.origin)!!.authority)
        h.runtime.close()
        val unsupported = h.makeRuntime(reopened)
        h.originalStatus = h.privateStatus
        unsupported.restore(); runCurrent()
        assertNull(unsupported.view.value.account)
        assertNull(unsupported.accountReads())
        assertEquals(PersonalAuthProblem.Reconnect, unsupported.view.value.problem)
        unsupported.close()
    }

    @Test
    fun reducedVerifiedAuthoritySynchronouslyRetiresPrivateHandlesAndKeepsPublicReads() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val old = h.runtime.accountReads()!!
        var retirements = 0
        h.runtime.onReadsRetired { retirements++ }
        h.originalStatus = h.publicStatus
        h.runtime.restore(); runCurrent()
        assertEquals(1, retirements)
        assertFalse(old.current)
        assertNull(h.runtime.accountReads())
        assertTrue(h.runtime.personalReads()!!.current)
        assertEquals(PersonalAuthority.PublicRead, h.runtime.view.value.account!!.authority)
        assertEquals(PersonalAuthority.PrivateRepositories, h.vault.accepted(h.origin)!!.authority, "Stored maximum may be reduced by the server, never inferred upward")
        assertFailsWith<CancellationException> { old.repositories() }
        h.runtime.close()
    }

    @Test
    fun privateAccountPagesRequireFixedRoutesVisibilityAndSelectedImmutableRepositoryIdentity() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val reader = h.runtime.accountReads()!!
        h.readReply = h.repositoryPage
        val repository = reader.repositories().repositories.single()
        assertTrue(repository.private)
        h.readReply = h.pullPage
        assertEquals(7L, reader.pulls(repository).pulls.single().number)
        h.readReply = h.inboxPage
        assertEquals("https://github.com/acme/editor/pull/7", reader.pulls().pulls.single().url)
        assertEquals(listOf("/repositories?page=1", "/repositories/acme/editor/pulls?page=1", "/pulls?relation=reviewRequested&page=1"),
            h.requests.filter { it.url.contains("/github/account/") }.map { it.url.substringAfter("/github/account") })
        for (body in listOf(h.pullPage.replace("\"id\":42", "\"id\":99"), h.pullPage.replace("\"private\":true", "\"private\":false"),
            h.pullPage.replace("\"private\":true,", ""), h.pullPage.replace("privateRepositories", "publicRead"))) {
            h.readReply = body
            assertEquals(PersonalReadProblem.InvalidResponse, assertFailsWith<PersonalReadError> { reader.pulls(repository) }.problem)
        }
        h.runtime.close()
    }

    @Test
    fun accountCapabilitiesDoNotElevatePublicSessionsOrPermitAnUnboundResponse() = runTest {
        val h = Harness(this)
        h.verify()
        val reader = h.runtime.personalReads()!!
        h.readReply = """{${h.owner("publicRead")},"privateRepositories":{"consentAvailable":true,"readEnabled":false}}"""
        assertEquals(PersonalRepositoryCapabilities(true, false), reader.capabilities())
        assertNull(h.runtime.accountReads())
        for (body in listOf(h.readReply.replace("\"readEnabled\":false", "\"readEnabled\":true"),
            h.readReply.replace("publicRead", "privateRepositories"), h.readReply.replace("account-42", "other-account"))) {
            val previous = h.readReply
            h.readReply = body
            assertEquals(PersonalReadProblem.InvalidResponse, assertFailsWith<PersonalReadError> { reader.capabilities() }.problem)
            h.readReply = previous
        }
        h.runtime.close()
    }

    @Test
    fun privateReadsRejectALateResponseAfterTheCapturedAccountIsRetired() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val reader = h.runtime.accountReads()!!
        val held = CompletableDeferred<Unit>()
        h.readGate = held
        h.readReply = h.repositoryPage
        val response = async { reader.repositories() }
        runCurrent()
        h.runtime.close()
        held.complete(Unit)
        assertFailsWith<CancellationException> { response.await() }
        assertNull(h.runtime.accountReads())
    }

    private class Harness(private val scope: TestScope) {
        val origin = PersonalSessionVaultTest.origin
        val f = PersonalAuthClientTest.Fixture()
        val original = PersonalSessionVaultTest.session(4)
        val storage = PersonalSessionVaultTest.MemoryBlob()
        val vault = PersonalSessionVault(storage)
        val publicStatus = f.account
        val privateStatus = publicStatus.replace("publicRead", "privateRepositories")
        val privateAuthorize = f.authorize.replace("scope=offline_access", "scope=repo+offline_access")
        var authorize = privateAuthorize
        var originalStatus = publicStatus
        var claimStatus = f.claimAccount.replace("publicRead", "privateRepositories")
        var claimGate: CompletableDeferred<Unit>? = null
        var confirmationGate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        var identityChanges = 0
        val requests = mutableListOf<RpcRequest>()
        private val repository = """{"private":true,"id":42,"owner":"acme","name":"editor","fullName":"acme/editor","description":null,"stars":0,"language":null,"url":"https://github.com/acme/editor"}"""
        private val pull = """"number":7,"title":"Private review","url":"https://github.com/acme/editor/pull/7","author":"reader","draft":false,"createdAt":"2026-10-22","updatedAt":"2026-10-22""""
        fun owner(authority: String = "privateRepositories") = """"accountId":"account-42","githubId":42,"authority":"$authority""""
        val repositoryPage = """{${owner()},"repositories":[$repository],"nextPage":null,"incomplete":false}"""
        val pullPage = """{${owner()},"repository":$repository,"pulls":[{$pull}],"nextPage":null,"incomplete":false}"""
        val inboxPage = """{${owner()},"relation":"reviewRequested","pulls":[{"repository":$repository,$pull}],"nextPage":null,"incomplete":false}"""
        var readReply = repositoryPage
        val runtime = makeRuntime(vault)

        fun makeRuntime(store: PersonalSessionVault) = PersonalAccountRuntime(origin, store, scope.backgroundScope,
            scope.backgroundScope, { 1200 + scope.testScheduler.currentTime / 1000 }, { true }, { identityChanges++ }, RpcTransport { request ->
                requests += request
                when {
                    request.url.contains("/github/account/") -> { readGate?.let { withContext(NonCancellable) { it.await() } }; json(readReply) }
                    request.url.endsWith("/repositories/start") -> f.startReply(authorize)
                    request.url.endsWith("/claim") -> { claimGate?.let { withContext(NonCancellable) { it.await() } }; json("""{"session":"${f.session}","status":$claimStatus}""") }
                    request.url.endsWith("/session") -> if (request.headers["Authorization"] == "Bearer ${original.token}") json(originalStatus)
                        else { confirmationGate?.await(); json(privateStatus) }
                    else -> f.respond(request)
                }
            })

        fun verify(private: Boolean = false) {
            val session = if (private) PersonalSession(original.token, original.status.copy(authority = PersonalAuthority.PrivateRepositories), original.origin) else original
            if (private) originalStatus = privateStatus
            vault.savePending(session); vault.promote(session)
            runtime.restore(); scope.runCurrent()
        }
    }

    companion object { private fun json(body: String) = RpcResponse(200, body, "application/json") }
}
