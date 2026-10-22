package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.ConnectionProblem
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectionCheckTest {
    @Test
    fun aSavedConnectionUsesActualAccountDiscoveryAndKeepsTheOlderPullyFallback() = runTest {
        for (legacy in listOf(false, true)) {
            val names = mutableListOf<String>()
            val repo = Repo({ RepoConfiguration("https://server.example") }, RpcTransport { request ->
                val name = Json.parseToJsonElement(request.body).jsonObject.getValue("name").jsonPrimitive.content
                names += name
                if (legacy && name == "account-status") RpcResponse(404, """{"error":"Unknown RPC"}""")
                else RpcResponse(200, if (name == "account-status") """{"result":{"hasAccount":true}}""" else """{"result":{}}""")
            })
            assertEquals(ServerConnectionOutcome.Connected, serverConnectionCheck(repo).verify())
            assertEquals(if (legacy) listOf("account-status", "repo-info") else listOf("account-status"), names)
        }
    }

    @Test
    fun anHtmlFailureKeepsItsFixedRecoveryCategoryInsteadOfReportingSaved() = runTest {
        val repo = Repo({ RepoConfiguration("https://server.example") }, RpcTransport {
            RpcResponse(401, "<html>Sign in</html>", "text/html")
        })
        assertEquals(ServerConnectionOutcome.Failed(ConnectionProblem.WebPage), serverConnectionCheck(repo).verify())
    }

    @Test
    fun aRetainedSaveProbeCannotDiscoverItsReplacementConnection() = runTest {
        var configuration = RepoConfiguration("https://first.example", generation = 1)
        val requests = mutableListOf<String>()
        val repo = Repo({ configuration }, RpcTransport { request -> requests += request.url; RpcResponse(200, """{"result":{"hasAccount":false}}""") })
        val probe = serverConnectionCheck(repo)
        configuration = RepoConfiguration("https://second.example", generation = 2)
        assertFailsWith<CancellationException> { probe.verify() }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun anUncooperativeOldReplyCannotTurnSourceReplacementIntoSuccessOrAConnectionError() = runTest {
        var generation = 0L
        val response = CompletableDeferred<Unit>()
        val repo = Repo({ RepoConfiguration("https://server.example", generation = generation) }, RpcTransport {
            withContext(NonCancellable) { response.await() }
            RpcResponse(200, """{"result":{"hasAccount":false}}""")
        })
        val probe = serverConnectionCheck(repo)
        val result = async { probe.verify() }
        runCurrent()
        generation++
        response.complete(Unit)
        assertFailsWith<CancellationException> { result.await() }
    }
}
