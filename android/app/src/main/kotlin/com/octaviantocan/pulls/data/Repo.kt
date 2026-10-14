package com.octaviantocan.pulls.data

import android.content.Context
import com.octaviantocan.pulls.core.OpenTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Every server call the phone makes, named after the RPC it runs. All run on IO and
 * throw the server's `{ error }` text on failure. Route contracts are in
 * `packages/cli/src/native-api.ts` and `native-review.ts`.
 */
class Repo(private val context: Context) {
    private fun api(): PullsApi {
        val config = ServerStore.load(context)
        return PullsApi(config.url, config.headers())
    }

    suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement = withContext(Dispatchers.IO) { api().call(name, args) }

    suspend fun call(name: String, build: JsonObjectBuilder.() -> Unit): JsonElement = call(name, buildJsonObject(build))

    private fun JsonObjectBuilder.pull(target: OpenTarget.Pull) {
        put("owner", target.owner); put("repo", target.repo); put("number", target.number)
    }

    // --- Opening a diff ---

    suspend fun open(target: OpenTarget): OpenedReview = when (target) {
        is OpenTarget.Pull -> call("github-pr") { pull(target) }.asOpened()
        is OpenTarget.Compare -> call("github-compare") { put("owner", target.owner); put("repo", target.repo); put("base", target.base); put("head", target.head) }.asOpened()
        is OpenTarget.Commit -> call("github-commit") { put("owner", target.owner); put("repo", target.repo); put("sha", target.sha) }.asOpened()
        is OpenTarget.Branch -> {
            val base = call("repo-info").jsonObject.obj("defaultBranch")?.s("ref")?.ifBlank { null } ?: "HEAD"
            call("local-diff") { put("target", "$base...${target.name}") }.asOpened()
        }
        is OpenTarget.Local -> call("local-diff") { put("target", target.target) }.asOpened()
        OpenTarget.Worktree -> call("local-diff") { put("target", "") }.asOpened()
    }

    suspend fun savedState(key: String): SavedState = call("review-state") { put("key", key) }.asSavedState()

    /** Reviewed file shas, and paths flagged "changed since reviewed". */
    suspend fun reviewed(key: String): Pair<Set<String>, Set<String>> {
        val o = call("reviewed-get") { put("key", key) }.jsonObject
        return o.strings("reviewed").toSet() to o.strings("changedSinceReviewed").toSet()
    }

    suspend fun setReviewed(key: String, shas: Collection<String>, reviewed: Boolean) {
        call("reviewed-set") { put("key", key); put("shas", JsonArray(shas.map { JsonPrimitive(it) })); put("reviewed", reviewed) }
    }

    suspend fun fullFile(key: String, path: String): FullFile = call("file-content") { put("key", key); put("path", path) }.asFullFile()

    /** Shiki spans with the pierre theme, like the web diff. */
    suspend fun highlight(path: String, lines: List<String>, dark: Boolean): JsonObject =
        call("highlight") { put("path", path); put("dark", dark); put("lines", JsonArray(lines.map { JsonPrimitive(it) })) }.jsonObject

    // --- GitHub pull request: overview, actions, reviews ---

    suspend fun overview(target: OpenTarget.Pull): JsonObject = call("pr-overview") { pull(target) }.jsonObject

    /** `action`: merge, update-branch, close, reopen, ready, draft. */
    suspend fun prAction(id: String, action: String, method: String? = null, title: String? = null, body: String? = null, expectedHeadOid: String? = null) {
        call("pr-action") {
            put("id", id); put("action", action)
            method?.let { put("method", it) }; title?.let { put("title", it) }; body?.let { put("body", it) }; expectedHeadOid?.let { put("expectedHeadOid", it) }
        }
    }

    suspend fun reviewData(target: OpenTarget.Pull): ReviewData = call("review-data") { pull(target) }.asReviewData()

    /** `mode`: `single` posts now; `review` adds to (or starts) the viewer's pending review. */
    suspend fun comment(target: OpenTarget.Pull, path: String, side: String, line: Int, headSha: String, body: String, mode: String = "single", startLine: Int? = null) {
        call("review-comment") {
            pull(target); put("path", path); put("side", side); put("line", line); put("headSha", headSha); put("body", body); put("mode", mode)
            startLine?.let { put("startLine", it) }
        }
    }

    suspend fun reply(target: OpenTarget.Pull, rootId: Long, body: String) = call("review-reply") { pull(target); put("rootId", rootId); put("body", body) }
    suspend fun resolve(target: OpenTarget.Pull, threadId: String) = call("review-resolve") { pull(target); put("threadId", threadId) }
    suspend fun editComment(target: OpenTarget.Pull, commentId: Long, body: String) = call("review-edit") { pull(target); put("commentId", commentId); put("body", body) }
    suspend fun deleteComment(target: OpenTarget.Pull, commentId: Long) = call("review-delete") { pull(target); put("commentId", commentId) }

    /** `verdict`: APPROVE, REQUEST_CHANGES or COMMENT. Submits the pending review when there is one. */
    suspend fun submitReview(target: OpenTarget.Pull, verdict: String, body: String) = call("review-submit") { pull(target); put("verdict", verdict); put("body", body) }
    suspend fun discardReview(target: OpenTarget.Pull) = call("review-discard") { pull(target) }

    // --- AI ---

    /** Starts a server task; returns its id. Model and effort come from the phone settings unless [effort] is given. */
    suspend fun startTask(kind: String, key: String, effort: String? = null, extra: JsonObjectBuilder.() -> Unit = {}): String {
        val config = ServerStore.load(context)
        val chosen = effort ?: AppSettings.get(context).effort
        return call("ai-task-start") {
            put("kind", kind); put("key", key)
            put("model", buildJsonObject {
                put("engine", config.engine)
                if (config.model.isNotBlank()) put("model", config.model)
                if (chosen.isNotBlank()) put("effort", chosen)
            })
            extra()
        }.jsonPrimitive.content
    }

    /** Long-polls a task for changes after `after`. */
    suspend fun waitTask(id: String, after: Int): JsonObject = call("ai-task-wait") { put("id", id); put("after", after) }.jsonObject

    suspend fun cancelTask(id: String) { runCatching { call("ai-task-cancel") { put("id", id) } } }

    /** This diff's tasks, running or recently finished, newest first: how a reopened page resumes them. */
    suspend fun tasks(key: String): List<TaskState> = (call("ai-task-list") { put("key", key) } as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.asTaskState() }

    /** Every diff's running and recent tasks, newest first, with the pull request's title and no work log or result. */
    suspend fun allTasks(): List<TaskState> = (call("ai-task-list-all") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.asTaskState() }

    suspend fun askThreads(key: String): List<AskThread> = (call("ask-threads") { put("key", key) } as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.asAskThread() }
    suspend fun closeAsk(key: String, threadId: String) { call("ask-thread-close") { put("key", key); put("threadId", threadId) } }

    suspend fun lenses(): List<Pair<String, String?>> = (call("lens-list") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> o.s("name") to o.sOrNull("description") } }

    /** `ai-models` for an engine: `{ models: [{ id, name, description, efforts, defaultEffort, isDefault }], account, version }`. */
    suspend fun models(engine: String, refresh: Boolean = false): JsonObject = call("ai-models") { put("engine", engine); put("refresh", refresh) }.jsonObject

    suspend fun postCritique(key: String, ids: List<String>, body: String?): List<String> =
        call("critique-post") { put("key", key); put("ids", JsonArray(ids.map { JsonPrimitive(it) })); body?.let { put("body", it) } }.jsonObject.strings("posted")

    /** Returns the shared comment's URL. */
    suspend fun share(key: String): String = call("share-analysis") { put("key", key) }.jsonObject.s("url")

    suspend fun clearChat(key: String) { call("chat-clear") { put("key", key) } }

    /** A Mermaid diagram as a flat SVG string (theme colours resolved on the server). */
    suspend fun mermaidSvg(source: String, dark: Boolean): String = call("mermaid-svg") { put("source", source); put("dark", dark) }.jsonObject.s("svg")

    // --- Lists ---

    /** `my-pulls-cached` first for an instant list, then `my-pulls` for a fresh one. */
    suspend fun myPullsCached(state: String): JsonObject? = call("my-pulls-cached") { put("state", state) } as? JsonObject
    suspend fun myPulls(state: String): List<PullRow> = call("my-pulls") { put("state", state) }.asPulls()

    suspend fun repoPulls(owner: String, repo: String, next: String? = null): JsonObject = call("repo-pulls") { put("owner", owner); put("repo", repo); next?.let { put("next", it) } }.jsonObject

    suspend fun recent(): List<JsonObject> = (call("recent-diffs") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    suspend fun repoInfo(): JsonObject = call("repo-info").jsonObject
}

internal fun JsonElement.objects(): List<JsonObject> = (this as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

internal fun JsonElement.arrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

@Suppress("unused")
private fun JsonElement.firstArray(): JsonArray = jsonArray
