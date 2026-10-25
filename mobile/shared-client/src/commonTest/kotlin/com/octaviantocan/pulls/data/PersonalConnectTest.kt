package com.octaviantocan.pulls.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalConnectTest {
    @Test
    fun oneLoginIncludesRepositoriesButCannotPublishBeforeSecureConfirmation() = runTest {
        val f = PersonalAuthClientTest.Fixture()
        val storage = Storage()
        val vault = PersonalSessionVault(storage)
        val confirmed = CompletableDeferred<Unit>()
        var changes = 0
        val origin = PersonalAuthOrigin.parse("https://accounts.example")
        val runtime = PersonalAccountRuntime(origin, vault, backgroundScope, backgroundScope,
            { 1200 }, { true }, { changes++ }, RpcTransport { request ->
                f.requests += request
                if (request.url.endsWith("/session")) confirmed.await()
                repositoryReply(f, request)
            })
        try {
            val action = assertNotNull(runtime.openConnect(backgroundScope) {})
            assertTrue(action.controller.start()); runCurrent()
            assertEquals(PersonalSignInState.Finishing, action.controller.state.value)
            assertNull(runtime.view.value.account)
            assertNull(runtime.accountReads())
            assertNull(vault.accepted(origin))
            assertEquals(PersonalAuthority.PrivateRepositories, PersonalSessionVault(storage).pending().single().authority)
            assertEquals(0, changes)
            val start = f.requests.single { it.url.endsWith("/start") }
            assertTrue(start.url.endsWith("/auth/github/connect/start"))
            assertFalse("Authorization" in start.headers)
            assertEquals(setOf("nativeChallenge"), Json.parseToJsonElement(start.body).jsonObject.keys)
            confirmed.complete(Unit); runCurrent()
            assertEquals(PersonalAuthority.PrivateRepositories, runtime.view.value.account!!.authority)
            assertEquals(PersonalAuthority.PrivateRepositories, vault.accepted(origin)!!.authority)
            assertNotNull(runtime.accountReads())
            assertEquals(1, changes)
            assertTrue(vault.pending().isEmpty())
            assertTrue(f.requests.none { it.url.endsWith("/repositories/start") })
        } finally { runtime.close() }
    }

    @Test
    fun declinedOrPublicOnlyApprovalNeverBecomesAConnectedRepositoryAccount() = runTest {
        for (failure in listOf("denied", "claim", "confirmation")) {
            val f = PersonalAuthClientTest.Fixture()
            val vault = PersonalSessionVault(Storage())
            val origin = PersonalAuthOrigin.parse("https://accounts.example")
            val runtime = PersonalAccountRuntime(origin, vault, backgroundScope, backgroundScope,
                { 1200 }, { true }, {}, RpcTransport { request ->
                    f.requests += request
                    when {
                        failure == "denied" && request.url.endsWith("/status") ->
                            PersonalAuthClientTest.json("""{"phase":"failed","failure":"denied"}""")
                        failure == "claim" && request.url.endsWith("/claim") -> f.respond(request)
                        failure == "confirmation" && request.url.endsWith("/session") -> f.respond(request)
                        else -> repositoryReply(f, request)
                    }
                })
            try {
                val action = assertNotNull(runtime.openConnect(backgroundScope) {})
                action.controller.start(); runCurrent()
                assertTrue(action.controller.state.value is PersonalSignInState.Failed)
                assertNull(runtime.view.value.account)
                assertNull(runtime.accountReads())
                assertNull(vault.accepted(origin))
            } finally { runtime.close() }
        }
    }

    @Test
    fun anOlderPublicConnectionFinishesWithItsCapturedAccountAndOneRepositoryApproval() = runTest {
        val f = PersonalAuthClientTest.Fixture()
        val origin = PersonalAuthOrigin.parse("https://accounts.example")
        var repositories = false
        val runtime = PersonalAccountRuntime(origin, PersonalSessionVault(Storage()), backgroundScope, backgroundScope,
            { 1200 }, { true }, {}, RpcTransport { request ->
                f.requests += request
                if (repositories) repositoryReply(f, request) else f.respond(request)
            })
        try {
            runtime.openSignIn(backgroundScope) {}!!.controller.start(); runCurrent()
            val original = assertNotNull(runtime.view.value.account)
            repositories = true
            val action = assertNotNull(runtime.openConnect(backgroundScope) {})
            assertTrue(action.repositoryConsent)
            action.controller.start(); runCurrent()
            val start = f.requests.single { it.url.endsWith("/repositories/start") }
            assertEquals("Bearer ${f.session}", start.headers["Authorization"])
            assertTrue(f.requests.none { it.url.endsWith("/connect/start") })
            val connected = assertNotNull(runtime.view.value.account)
            assertEquals(original.accountId, connected.accountId)
            assertEquals(original.githubId, connected.githubId)
            assertEquals(PersonalAuthority.PrivateRepositories, connected.authority)
        } finally { runtime.close() }
    }

    @Test
    fun aPausedOwnerCannotStartCombinedRepositoryConsent() = runTest {
        val f = PersonalAuthClientTest.Fixture()
        val runtime = PersonalAccountRuntime(PersonalAuthOrigin.parse("https://accounts.example"),
            PersonalSessionVault(Storage()), backgroundScope, backgroundScope, { 1200 }, { true }, {},
            RpcTransport { request -> f.requests += request; repositoryReply(f, request) })
        try {
            runtime.foreground(false)
            assertFalse(runtime.openConnect(backgroundScope) {}!!.controller.start())
            assertTrue(f.requests.isEmpty())
        } finally { runtime.close() }
    }

    private val privateSession = "pully_" + "B".repeat(42) + "A"

    private suspend fun repositoryReply(f: PersonalAuthClientTest.Fixture, request: RpcRequest): RpcResponse = when {
        request.url.endsWith("/start") -> f.startReply(f.authorize.replace("scope=offline_access", "scope=repo+offline_access"))
        request.url.endsWith("/claim") -> PersonalAuthClientTest.json("""{"session":"${privateSession}","status":${f.claimAccount.replace("publicRead", "privateRepositories")}}""")
        request.url.endsWith("/session") -> PersonalAuthClientTest.json(f.account.replace("publicRead", "privateRepositories"))
        else -> f.respond(request)
    }

    private class Storage : PersonalSessionBlobStorage {
        private var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(contents: ByteArray) { bytes = contents.copyOf() }
    }
}
