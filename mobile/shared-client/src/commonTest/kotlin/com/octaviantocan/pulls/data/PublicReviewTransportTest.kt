package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.core.OpenTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicReviewTransportTest {
    private class GitHub {
        val requests = mutableListOf<RpcRequest>()
        var now = 0L
        var answer: (RpcRequest) -> RpcResponse = { request ->
            val path = request.url.removePrefix("https://api.github.com")
            when {
                path == "/repos/public-owner/public-repo/pulls/7" -> ok(PULL)
                path.startsWith("/repos/public-owner/public-repo/pulls/7/files") -> ok(FILES)
                path.startsWith("/repos/public-owner/public-repo/pulls/7/commits") -> ok(COMMITS)
                path.startsWith("/repos/public-owner/public-repo/issues/7/comments") -> ok(COMMENTS)
                path.startsWith("/repos/public-owner/public-repo/contents/src/App.kt?ref=head-sha") -> ok("fun app() = 2\n")
                path.startsWith("/repos/public-owner/public-repo/contents/src/App.kt?ref=base-sha") -> ok("fun app() = 1\n")
                else -> RpcResponse(404, "{}", "application/json")
            }
        }
        val transport = RpcTransport { request -> requests += request; answer(request) }
        val repo = Repo({ RepoConfiguration(PUBLIC_REVIEW_SERVER) }, PublicReviewTransport(transport) { now })
    }

    @Test
    fun aPublicPullRequestOpensFromTokenlessGitHubReads() = runTest {
        val h = GitHub()
        val opened = h.repo.open(OpenTarget.Pull("public-owner", "public-repo", 7, "github.com"))
        assertEquals("Make the app say two", opened.title)
        assertEquals("head-sha", opened.headSha)
        assertEquals(listOf("src/App.kt"), opened.files.map { it.path })
        assertEquals(listOf('-', '+'), opened.files.single().patchLines.map { it.kind })
        assertTrue(h.requests.all { it.method == RpcMethod.Get && it.url.startsWith("https://api.github.com/") })
        assertTrue(h.requests.none { request -> request.headers.keys.any { it.equals("Authorization", true) || it.startsWith("CF-", true) } })
        val file = h.repo.fullFile(opened.key, "src/App.kt")
        assertEquals("fun app() = 1\n", file.old)
        assertEquals("fun app() = 2\n", file.new)
    }

    @Test
    fun theOverviewCarriesTheDescriptionCommitsAndCommentsAndNoWriteRights() = runTest {
        val h = GitHub()
        val overview = h.repo.overview(OpenTarget.Pull("public-owner", "public-repo", 7, "github.com"))["overview"]!!.jsonObject
        assertEquals("Body text", overview["body"]!!.jsonPrimitive.content)
        assertEquals("open", overview["state"]!!.jsonPrimitive.content)
        assertEquals("<p>Nice</p>", overview["timeline"]!!.let { it as kotlinx.serialization.json.JsonArray }.single().jsonObject["bodyHTML"]!!.jsonPrimitive.content)
        assertEquals("false", overview["viewer"]!!.jsonObject["canUpdate"]!!.jsonPrimitive.content)
    }

    @Test
    fun readsAreKeptForAFewMinutesToSpareGitHubsHourlyLimit() = runTest {
        val h = GitHub()
        val target = OpenTarget.Pull("public-owner", "public-repo", 7, "github.com")
        h.repo.open(target)
        val first = h.requests.size
        h.repo.prText(target)
        h.repo.open(target)
        assertEquals(first + 1, h.requests.size) // Only the comments were new.
        h.now = 6 * 60_000L
        h.repo.open(target)
        assertEquals(first + 1 + 3, h.requests.size)
    }

    @Test
    fun writesAndAiAreRefusedAndGitHubLimitsSaySo() = runTest {
        val h = GitHub()
        val refused = assertFailsWith<ServerError> { h.repo.call("pr-action") { } }
        assertEquals(PublicReviewTransport.SIGN_IN, refused.message)
        h.answer = { RpcResponse(403, "{}", "application/json", rateLimitRemaining = 0) }
        val limited = assertFailsWith<ServerError> { h.repo.open(OpenTarget.Pull("public-owner", "public-repo", 7, "github.com")) }
        assertEquals(PublicBrowseError(PublicBrowseProblem.RateLimited).message, limited.message)
    }

    @Test
    fun githubPatchesBecomeTheServersHunks() {
        val hunks = publicHunks("@@ -1,2 +1,2 @@ fun\n a\n-b\n+c\n@@ -10 +10 @@\n-x\n+y")
        assertEquals(2, hunks.size)
        assertEquals(" a\n-b\n+c", hunks[0]["patch"]!!.jsonPrimitive.content)
        assertEquals(1, hunks[1]["oldLines"]!!.jsonPrimitive.content.toInt())
    }

    companion object {
        private fun ok(body: String) = RpcResponse(200, body, "application/json")
        private val PULL = """{"id":99,"number":7,"title":"Make the app say two","body":"Body text","body_html":"<p>Body text</p>",
            "html_url":"https://github.com/public-owner/public-repo/pull/7","state":"open","draft":false,"merged_at":null,"closed_at":null,
            "created_at":"2026-10-21T10:00:00Z","updated_at":"2026-10-22T10:00:00Z","user":{"login":"reader","avatar_url":"https://avatars.example/u","type":"User"},
            "base":{"sha":"base-sha","ref":"main","repo":{"private":false}},"head":{"sha":"head-sha","ref":"two","repo":{"owner":{"login":"reader"}}}}"""
        private val FILES = """[{"filename":"src/App.kt","status":"modified","additions":1,"deletions":1,"sha":"blob","patch":"@@ -1 +1 @@\n-fun app() = 1\n+fun app() = 2"}]"""
        private val COMMITS = """[{"sha":"head-sha","html_url":"https://github.com/c","commit":{"message":"Say two\n\nBecause.","committer":{"date":"2026-10-22T10:00:00Z"},"author":{"name":"Reader"}},"author":{"login":"reader"}}]"""
        private val COMMENTS = """[{"id":5,"created_at":"2026-10-22T11:00:00Z","html_url":"https://github.com/c5","body_html":"<p>Nice</p>","user":{"login":"friend"}}]"""
    }
}
