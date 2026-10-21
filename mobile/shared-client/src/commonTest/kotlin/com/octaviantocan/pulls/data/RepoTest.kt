package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.core.OpenTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RepoTest {
    private class FixtureTransport(private val result: (String) -> String) : RpcTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun execute(request: RpcRequest): RpcResponse {
            val envelope = Json.parseToJsonElement(request.body).jsonObject
            val name = (envelope.getValue("name") as JsonPrimitive).content
            calls += name to envelope.getValue("args").jsonObject
            return RpcResponse(200, """{"result":${result(name)}}""")
        }
    }

    @Test
    fun existingOpenBuildersKeepLiteFreshAndServerOwnedLocalTargets() = runTest {
        val transport = FixtureTransport { name ->
            if (name == "repo-info") """{"defaultBranch":{"ref":"main"}}""" else """{"key":"k","diff":{"files":[]},"groups":[]}"""
        }
        val repo = Repo({ RepoConfiguration("https://server.example") }, transport)
        assertEquals("k", repo.open(OpenTarget.Pull("owner", "repo", 42), fresh = true).key)
        assertEquals("github-pr", transport.calls[0].first)
        assertEquals(
            Json.parseToJsonElement("""{"owner":"owner","repo":"repo","number":42,"lite":true,"fresh":true}"""),
            transport.calls[0].second,
        )
        repo.open(OpenTarget.Branch("feature"))
        assertEquals(listOf("github-pr", "repo-info", "local-diff"), transport.calls.map { it.first })
        assertEquals(buildJsonObject { put("target", "main...feature") }, transport.calls.last().second)
    }

    @Test
    fun aiStartUsesTheInjectedModelEffortAndExistingFollowUps() = runTest {
        val transport = FixtureTransport { "\"task-1\"" }
        val repo = Repo({ RepoConfiguration("https://server.example", engine = "codex", model = "gpt", effort = "medium") }, transport)
        assertEquals("task-1", repo.startTask("analyze", "github:o/r#3", effort = "high") { put("then", Json.parseToJsonElement("""["tour","brief"]""")) })
        assertEquals(
            Json.parseToJsonElement("""{"kind":"analyze","key":"github:o/r#3","model":{"engine":"codex","model":"gpt","effort":"high"},"then":["tour","brief"]}"""),
            transport.calls.single().second,
        )
        repo.startTask("tour", "key")
        assertEquals("medium", (transport.calls.last().second.getValue("model") as JsonObject).getValue("effort").let { (it as JsonPrimitive).content })
    }

    @Test
    fun actionAndCritiqueBuildersRetainTheCurrentHeadAndRevision() = runTest {
        val transport = FixtureTransport { if (it == "critique-post") """{"posted":["f1"]}""" else "null" }
        val repo = Repo({ RepoConfiguration("https://server.example") }, transport)
        repo.prAction("PR_node", "merge", method = "SQUASH", expectedHeadOid = "current-head")
        assertEquals(
            Json.parseToJsonElement("""{"id":"PR_node","action":"merge","method":"SQUASH","expectedHeadOid":"current-head"}"""),
            transport.calls.single().second,
        )
        val critique = Json.parseToJsonElement("""{"headSha":"head","variant":"head:engine:lens","revision":"immutable","findings":[]}""").jsonObject.asCritique()
        assertEquals(listOf("f1"), repo.postCritique("key", critique, listOf("f1"), null))
        assertEquals(JsonPrimitive("immutable"), transport.calls.last().second["revision"])
        assertEquals(JsonPrimitive("head"), transport.calls.last().second["headSha"])
        val count = transport.calls.size
        assertFailsWith<IllegalArgumentException> { repo.postCritique("key", critique.copy(revision = null), listOf("f1"), null) }
        assertEquals(count, transport.calls.size)
    }

    @Test
    fun signinUsesTheCheckedSnapshotAndBlocksRemotePlainHttp() = runTest {
        val transport = FixtureTransport { """{"session":"accepted","email":"user@example.com"}""" }
        val insecure = Repo({ RepoConfiguration("http://server.example") }, transport)
        assertFailsWith<IllegalStateException> { insecure.signIn("user@example.com", "password") }
        assertFailsWith<IllegalStateException> { insecure.signInWithGoogle("google-id-token") }
        assertTrue(transport.calls.isEmpty())
        val local = Repo({ RepoConfiguration("http://localhost:5173") }, transport)
        assertEquals("accepted" to "user@example.com", local.signIn("user@example.com", "password"))
        assertEquals("account-sign-in", transport.calls.single().first)
    }

    @Test
    fun settingsPayloadKeepsPerFieldTimestampsAndValues() = runTest {
        val transport = FixtureTransport { """{"values":{"theme":{"value":"dark","at":30}}}""" }
        val repo = Repo({ RepoConfiguration("https://server.example") }, transport)
        val values = mapOf("theme" to SyncedSetting(JsonPrimitive("dark"), 30))
        assertEquals(values, repo.mergeAccountSettings(values))
        assertEquals(Json.parseToJsonElement("""{"values":{"theme":{"value":"dark","at":30}}}"""), transport.calls.single().second)
    }
}
