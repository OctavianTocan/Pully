package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.data.PullsApi
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.data.asPulls
import com.octaviantocan.pulls.ui.home.asRepoInfo
import com.octaviantocan.pulls.ui.home.asRepoPullsPage
import com.octaviantocan.pulls.ui.home.asSnapshot
import com.octaviantocan.pulls.ui.settings.asCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reads the real routes the home, repo, checkout and settings screens use. Runs only with
 * `PULLS_LIVE_SERVER=http://127.0.0.1:8793` set; read-only calls.
 */
class LiveServerTest {
    private val server = System.getenv("PULLS_LIVE_SERVER")

    private fun api(): PullsApi {
        assumeTrue("set PULLS_LIVE_SERVER to run", !server.isNullOrBlank())
        return PullsApi(server!!)
    }

    @Test
    fun myPullsParseAndTriage() {
        val api = api()
        val snapshot = (api.call("my-pulls-cached", buildJsonObject { put("state", "open") }) as JsonObject).asSnapshot()!!
        val fresh = api.call("my-pulls", buildJsonObject { put("state", "open") }).asPulls()
        val now = System.currentTimeMillis()
        val filtered = Triage.filterPulls(fresh, "all", "all", "", now)
        val groups = Triage.groupByRepo(filtered.pulls)
        println("my-pulls-cached: ${snapshot.pulls.size} rows, fetchedAt=${snapshot.fetchedAt}; my-pulls: ${fresh.size} rows in ${groups.size} repos")
        println("queues=${filtered.queueCounts} roles=${filtered.roleCounts}")
        println("first rows: " + fresh.take(3).map { "${it.owner}/${it.repo}#${it.number} ${Triage.status(it)} checks=${it.checks} +${it.additions}/-${it.deletions} files=${it.changedFiles} requested=${it.requestedFromMe}" })
        assertTrue(fresh.isNotEmpty() && fresh.all { it.number > 0 && it.owner.isNotEmpty() && it.updatedAtMs != null })
        assertTrue(filtered.roleCounts.getValue("all") == fresh.size)
        val closed = api.call("my-pulls", buildJsonObject { put("state", "closed") }).asPulls()
        println("closed: ${closed.size}, merged=${closed.count { it.merged }}, statuses=${closed.groupingBy { Triage.status(it) }.eachCount()}")
    }

    @Test
    fun repoPullsPagesThroughTheCursor() {
        val api = api()
        val first = api.call("repo-pulls", buildJsonObject { put("owner", "antfu"); put("repo", "pulls.review") }).jsonObject.asRepoPullsPage()
        println("repo-pulls antfu/pulls.review: total=${first.totalCount} items=${first.items.size} next=${first.next}")
        println(first.items.take(3).map { "#${it.number} ${it.state} by ${it.author?.login} labels=${it.labels.map { l -> l.name }} checks=${it.checks} review=${it.reviewDecision} +${it.additions}/-${it.deletions} comments=${it.comments}" })
        assertTrue(first.items.isNotEmpty() && first.items.all { it.number > 0 && it.createdAt.isNotEmpty() })
        val big = api.call("repo-pulls", buildJsonObject { put("owner", "vuejs"); put("repo", "core") }).jsonObject.asRepoPullsPage()
        println("repo-pulls vuejs/core: total=${big.totalCount} items=${big.items.size} next=${big.next}; labelled=${big.items.count { it.labels.isNotEmpty() }}")
        if (big.next != null) {
            // The server's GraphQL continuation currently answers GitHub 502 for large repos; the
            // screen shows that error with a Retry under the list, so only record it here.
            runCatching { api.call("repo-pulls", buildJsonObject { put("owner", "vuejs"); put("repo", "core"); put("next", big.next!!) }).jsonObject.asRepoPullsPage() }
                .onSuccess { second -> println("page 2: items=${second.items.size} next=${second.next} overlap=${second.items.map { it.number }.intersect(big.items.map { it.number }.toSet()).size}") }
                .onFailure { println("page 2 failed on the server: ${it.message}") }
        }
    }

    @Test
    fun repoInfoAndModels() {
        val api = api()
        val info = api.call("repo-info").jsonObject.asRepoInfo()
        println("repo-info: current=${info.currentBranch} default=${info.defaultBranch} branches=${info.pickerBranches()} commits=${info.commits.size}")
        assertTrue(info.defaultBranch != null && info.commits.isNotEmpty())
        for (engine in listOf("claude-code", "codex")) {
            val catalog = api.call("ai-models", buildJsonObject { put("engine", engine) }).jsonObject.asCatalog()
            println("ai-models $engine: ${catalog.models.size} models, line='${catalog.line}', default=${catalog.models.firstOrNull { it.isDefault }?.id}, efforts=${catalog.models.firstOrNull()?.efforts}")
            assertTrue(catalog.models.isNotEmpty())
        }
    }
}
