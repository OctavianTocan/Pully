package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountConnectionTest {
    private fun repo(reply: (String) -> RpcResponse) = Repo({ RepoConfiguration("https://server.example") }, RpcTransport {
        val method = ((Json.parseToJsonElement(it.body) as JsonObject)["name"] as JsonPrimitive).content
        reply(method)
    })
    private fun result(body: String) = RpcResponse(200, """{"result":$body}""", "application/json")

    @Test
    fun freshInstallRequiresActualAccountCapabilitiesBeforeShowingSignIn() = runTest {
        val methods = mutableListOf<String>()
        val check = checkAccountConnection(repo { name ->
            methods += name
            result("""{"hasAccount":true,"googleClientId":"pully-client"}""")
        }, null)
        assertEquals(true, check.status?.hasAccount)
        assertEquals("pully-client", check.status?.googleClientId)
        assertFalse(check.sessionExpired)
        assertEquals(listOf("account-status"), methods)
        assertFailsWith<ServerError> {
            checkAccountConnection(repo { RpcResponse(200, "<!DOCTYPE html><html>Login</html>") }, null)
        }
    }

    @Test
    fun anOlderServerMustProveConnectivityWithoutInventingAccountCapabilities() = runTest {
        val methods = mutableListOf<String>()
        val check = checkAccountConnection(repo { name ->
            methods += name
            if (name == "account-status") RpcResponse(404, """{"error":"Unknown method"}""")
            else result("""{"mode":"github"}""")
        }, null)
        assertNull(check.status)
        assertFalse(check.sessionExpired)
        assertEquals(listOf("account-status", "repo-info"), methods)
    }

    @Test
    fun aVerifiedAccountSurvivesProxyHtmlUnavailableAndMalformedResponses() = runTest {
        for (response in listOf(
            RpcResponse(401, "<html>Access</html>"),
            RpcResponse(503, "down"),
            result("""{"unexpected":"shape"}"""),
            result("""{"email":42}"""),
        )) {
            val server = repo { name -> if (name == "account-status") result("""{"hasAccount":true}""") else response }
            assertFailsWith<ServerError> { checkAccountConnection(server, "person@example.com") }
        }
        val rejected = checkAccountConnection(repo { name ->
            if (name == "account-status") result("""{"hasAccount":true}""") else RpcResponse(401, """{"error":"Sign in first."}""")
        }, "person@example.com")
        assertTrue(rejected.sessionExpired)
        val wrongOwner = checkAccountConnection(repo { name ->
            result(if (name == "account-status") """{"hasAccount":true}""" else """{"email":"other@example.com"}""")
        }, "person@example.com")
        assertTrue(wrongOwner.sessionExpired)
    }

    @Test
    fun malformedDiscoveryCannotBeMistakenForAServerWithNoAccounts() = runTest {
        for (body in listOf("null", "{}", "[]", """{"hasAccount":"false"}""", """{"hasAccount":false,"googleClientId":7}""")) {
            val failure = assertFailsWith<ServerError> { repo { result(body) }.accountStatus() }
            assertEquals(ServerErrorKind.InvalidResponse, failure.kind)
        }
        assertEquals(false, repo { result("""{"hasAccount":false}""") }.accountStatus().hasAccount)
    }

    @Test
    fun recoveryDoesNotTreatGenericHttpRejectionAsCloudflareAccess() {
        for (status in listOf(401, 403)) {
            assertEquals(ConnectionProblem.HttpRejected, connectionProblem(ServerError(status, "refused", kind = ServerErrorKind.Http)))
            assertEquals(ConnectionProblem.WebPage, connectionProblem(ServerError(status, "page", kind = ServerErrorKind.HtmlResponse)))
        }
        assertEquals(ConnectionProblem.Access, connectionProblem(ServerError(302, "Access redirect", kind = ServerErrorKind.AccessRequired)))
        assertEquals(ConnectionProblem.SignIn, connectionProblem(ServerError(401, "RPC unauthorized", kind = ServerErrorKind.Unauthorized)))
        assertEquals(ConnectionProblem.Unavailable, connectionProblem(ServerError(503, "down", kind = ServerErrorKind.HtmlResponse)))
    }

    @Test
    fun sourceChangesBetweenDiscoveryAndVerificationNeverReturnAnotherOwnersState() = runTest {
        var configuration = RepoConfiguration("https://server.example", generation = 1)
        val server = Repo({ configuration }, RpcTransport {
            configuration = RepoConfiguration("https://other.example", generation = 2)
            result("""{"hasAccount":true}""")
        })
        assertFailsWith<CancellationException> { checkAccountConnection(server, "person@example.com") }
    }
}
