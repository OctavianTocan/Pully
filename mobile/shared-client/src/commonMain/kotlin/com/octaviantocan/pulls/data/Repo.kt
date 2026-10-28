package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.core.OpenTarget
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A drawn confirmation expects this pending review; null explicitly means there was no pending review. */
data class ExpectedPendingReview(val id: Long?) {
    init { require(id == null || id > 0) { "A pending review needs a positive id." } }
}

/**
 * Every server call the phone makes, named after the RPC it runs. Native transports handle IO and
 * throw the server's `{ error }` text on failure. Route contracts are in
 * `backend/src/transport.rs` and `backend/src/review/`.
 */
class Repo(
    private val configuration: () -> RepoConfiguration,
    private val transport: RpcTransport,
    private val observer: RpcObserver = RpcObserver { _, _, _, _ -> },
    private val clock: RpcClock = MonotonicRpcClock,
) {
    fun source(): RpcSource = configuration().source

    fun isCurrent(source: RpcSource): Boolean = runCatching { source == this.source() }.getOrDefault(false)

    /** Times only the method/outcome. A response from a superseded account/server is never returned. */
    suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement =
        call(configuration(), name, args)

    private suspend fun call(config: RepoConfiguration, name: String, args: JsonObject): JsonElement {
        // Invalid current configuration is actionable; a later invalid replacement is a superseded source.
        config.source
        val started = clock.nowMillis()
        fun ms() = (clock.nowMillis() - started).coerceAtLeast(0)
        try {
            val result = RpcClient(config.server, transport, config.headers).call(name, args)
            if (!config.sameSource(configuration())) throw CancellationException("The server or account changed.")
            observer.completed(name, ms(), true, null)
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!config.sameSource(configuration())) throw CancellationException("The server or account changed.")
            observer.completed(name, ms(), false, (e as? ServerError)?.status)
            throw e
        }
    }

    private suspend fun call(config: RepoConfiguration, name: String, build: JsonObjectBuilder.() -> Unit): JsonElement =
        call(config, name, buildJsonObject(build))

    suspend fun call(name: String, build: JsonObjectBuilder.() -> Unit): JsonElement = call(name, buildJsonObject(build))

    private fun JsonObjectBuilder.pull(target: OpenTarget.Pull) {
        put("owner", target.owner); put("repo", target.repo); put("number", target.number)
    }

    // --- Opening a diff ---

    /**
     * The diff. A GitHub one comes from the server's stored copy when its head is still current, and [fresh] asks
     * GitHub again. It is lite: past a size the server leaves patches out ([ReviewFile.patchOmitted]) and
     * [diffPatches] brings them as the files are shown, so a huge pull request opens at once.
     */
    suspend fun open(target: OpenTarget, fresh: Boolean = false): OpenedReview = when (target) {
        is OpenTarget.Pull -> call("github-pr") { pull(target); lite(fresh) }.asOpened()
        is OpenTarget.Compare -> call("github-compare") { put("owner", target.owner); put("repo", target.repo); put("base", target.base); put("head", target.head); lite(fresh) }.asOpened()
        is OpenTarget.Commit -> call("github-commit") { put("owner", target.owner); put("repo", target.repo); put("sha", target.sha); lite(fresh) }.asOpened()
        is OpenTarget.Branch -> {
            val base = call("repo-info").jsonObject.obj("defaultBranch")?.s("ref")?.ifBlank { null } ?: "HEAD"
            call("local-diff") { put("target", "$base...${target.name}") }.asOpened()
        }
        is OpenTarget.Local -> call("local-diff") { put("target", target.target) }.asOpened()
        OpenTarget.Worktree -> call("local-diff") { put("target", "") }.asOpened()
        is OpenTarget.Posted -> call("posted-diff") { put("id", target.id) }.asOpened()
    }

    private fun JsonObjectBuilder.lite(fresh: Boolean) {
        put("lite", true)
        if (fresh) put("fresh", true)
    }

    /** The patches [open] left out, by path, for the files of the diff stored under [key]. */
    suspend fun diffPatches(key: String, paths: List<String>): Map<String, FilePatch> =
        call("diff-patches") { put("key", key); put("paths", JsonArray(paths.map { JsonPrimitive(it) })) }.asPatches()

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

    /** Which files of an opened diff need a look, by Jev; `enabled` is false while the server has no Jev. */
    suspend fun jevFiles(key: String): JevFiles = (call("jev-files") { put("key", key) } as? JsonObject)?.asJevFiles() ?: JevFiles(false, emptyMap(), 0)

    /** Shiki spans with the pierre theme, like the web diff. */
    suspend fun highlight(path: String, lines: List<String>, dark: Boolean): JsonObject =
        call("highlight") { put("path", path); put("dark", dark); put("lines", JsonArray(lines.map { JsonPrimitive(it) })) }.jsonObject

    // --- GitHub pull request: overview, actions, reviews ---

    suspend fun overview(target: OpenTarget.Pull): JsonObject = call("pr-overview") { pull(target) }.jsonObject

    /** The stack the pull request is a layer of, bottom first; null when it is in none. */
    suspend fun prStack(target: OpenTarget.Pull): PrStack? = (call("pr-stack") { pull(target) } as? JsonObject)?.get("stack")?.asPrStack()
    /** The overview the server stored when it last refreshed this pull request, without asking GitHub; null when it has none. */
    suspend fun storedOverview(target: OpenTarget.Pull): JsonObject? = call("pr-overview") { pull(target); put("cached", true) } as? JsonObject

    /**
     * The title and description as GitHub holds them now, with the pull request's `updatedAt`: one small query that
     * answers well before [overview], so a page showing a kept overview puts an edited description in place at once.
     */
    suspend fun prText(target: OpenTarget.Pull): JsonObject? = call("pr-text") { pull(target) } as? JsonObject

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
    suspend fun submitReview(target: OpenTarget.Pull, verdict: String, body: String, headSha: String? = null, pending: ExpectedPendingReview? = null) = call("review-submit") {
        pull(target); put("verdict", verdict); put("body", body); headSha?.let { put("headSha", it) }
        pending?.let { put("pendingReviewId", it.id?.let(::JsonPrimitive) ?: JsonNull) }
    }
    suspend fun discardReview(target: OpenTarget.Pull, pending: ExpectedPendingReview? = null) = call("review-discard") {
        pull(target); pending?.let { put("pendingReviewId", it.id?.let(::JsonPrimitive) ?: JsonNull) }
    }

    // --- AI ---

    /** Starts a server task; returns its id. Model and effort come from the phone settings unless [effort] is given. */
    suspend fun startTask(kind: String, key: String, effort: String? = null, extra: JsonObjectBuilder.() -> Unit = {}): String {
        val config = configuration()
        val chosen = effort ?: config.effort
        return call(config, "ai-task-start") {
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

    suspend fun cancelTask(id: String, bestEffort: Boolean = true) {
        try { call("ai-task-cancel") { put("id", id) } }
        catch (e: CancellationException) { throw e }
        catch (failure: Exception) { if (!bestEffort) throw failure }
    }

    /** This diff's tasks, running or recently finished, newest first: how a reopened page resumes them. */
    suspend fun tasks(key: String): List<TaskState> = (call("ai-task-list") { put("key", key) } as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.asTaskState() }

    /** Every diff's running and recent tasks, newest first, with the pull request's title and no work log or result. */
    suspend fun allTasks(): List<TaskState> = (call("ai-task-list-all") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.asTaskState() }

    /** The conversation about the inbox, asked from the Home search. */
    suspend fun inboxChat(): InboxChat = (call("inbox-chat") as? JsonObject)?.asInboxChat() ?: InboxChat(emptyList(), emptyList())

    /** Starts that conversation afresh; an answer still on its way stops. */
    suspend fun clearInboxChat() { call("inbox-chat-clear") }

    suspend fun askThreads(key: String): List<AskThread> = (call("ask-threads") { put("key", key) } as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.asAskThread() }
    suspend fun closeAsk(key: String, threadId: String) { call("ask-thread-close") { put("key", key); put("threadId", threadId) } }

    /** `lens-list`: the review skills curated on the server, by title. */
    suspend fun lenses(): List<ReviewLens> = (call("lens-list") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.asReviewLens()?.takeIf { lens -> lens.name.isNotEmpty() } }

    /** `ai-models` for an engine: `{ models: [{ id, name, description, efforts, defaultEffort, isDefault }], account, version }`. */
    suspend fun models(engine: String, refresh: Boolean = false): JsonObject = call("ai-models") { put("engine", engine); put("refresh", refresh) }.jsonObject

    // --- Claude Code and Codex sign-ins, each person's own (ai-kit/src/login.rs) ---

    suspend fun aiSignIns(): AiSignIns = call("ai-login-status").jsonObject.asAiSignIns()
    suspend fun startAiSignIn(cli: AiCli): AiSignInStart = call("ai-login-start") { put("engine", cli.engine) }.jsonObject.asAiSignInStart()
    /** True once signed in; false while Codex still waits for the code to be entered. */
    suspend fun finishAiSignIn(cli: AiCli, code: String? = null): Boolean =
        call("ai-login-finish") { put("engine", cli.engine); code?.let { put("code", it) } }.jsonObject.b("signedIn")
    suspend fun cancelAiSignIn(cli: AiCli) { call("ai-login-cancel") { put("engine", cli.engine) } }
    suspend fun signOutAi(cli: AiCli) { call("ai-login-sign-out") { put("engine", cli.engine) } }

    suspend fun postCritique(key: String, critique: Critique, ids: List<String>, body: String?): List<String> =
        call("critique-post") {
            put("key", key)
            put("variant", requireNotNull(critique.variant))
            put("revision", requireNotNull(critique.revision))
            put("headSha", requireNotNull(critique.headSha))
            put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
            body?.let { put("body", it) }
        }.jsonObject.strings("posted")

    /** Returns the shared comment's URL. */
    suspend fun share(key: String): String = call("share-analysis") { put("key", key) }.jsonObject.s("url")

    suspend fun clearChat(key: String) { call("chat-clear") { put("key", key) } }

    /** This diff's earlier chats, newest first: each one's id, its first question as a title, when it ended and how many questions it had. */
    suspend fun chatHistory(key: String): List<ChatHistoryEntry> =
        call("chat-history") { put("key", key) }.jsonObject.arr("chats").map { ChatHistoryEntry(it.s("id"), it.s("title"), it.s("at"), it.i("turns") ?: 0) }

    /** Brings an earlier chat back as the current one; the current one, if it asked anything, goes into the history. */
    suspend fun restoreChat(key: String, id: String) { call("chat-restore") { put("key", key); put("id", id) } }

    /** A Mermaid diagram as a flat SVG string (theme colours resolved on the server). */
    suspend fun mermaidSvg(source: String, dark: Boolean): String = call("mermaid-svg") { put("source", source); put("dark", dark) }.jsonObject.s("svg")

    // --- The account (plan 13) ---

    /** Signs in with the account's email and password; answers the session the phone keeps, and the email. */
    suspend fun signIn(email: String, password: String): Pair<String, String> {
        val config = configuration()
        if (!carriesSecrets(config.server))
            throw IllegalStateException("Pully signs in only over HTTPS. Change the server address in Settings, Server to start with https://.")
        val o = call(config, "account-sign-in") { put("email", email); put("password", password) }.jsonObject
        return o.s("session") to o.s("email")
    }

    /** Signs in with the ID token Google gave for the server's client: the session and the account's email. */
    suspend fun signInWithGoogle(idToken: String): Pair<String, String> {
        val config = configuration()
        if (!carriesSecrets(config.server))
            throw IllegalStateException("Pully signs in only over HTTPS. Change the server address in Settings, Server to start with https://.")
        val o = call(config, "account-google-sign-in") { put("idToken", idToken) }.jsonObject
        return o.s("session") to o.s("email")
    }

    suspend fun signOut() { call("account-sign-out") }

    /**
     * Whether the server has an account to sign in to, and the client ID Google signs in for when it takes Google
     * sign-in; asked without a session, so a server never set up locks nobody out.
     */
    suspend fun accountStatus(): AccountStatus {
        val result = call("account-status") as? JsonObject ?: invalidAccountResponse()
        val account = (result["hasAccount"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalidAccountResponse()
        val client = result["googleClientId"]?.takeUnless { it == JsonNull }?.let {
            (it as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: invalidAccountResponse()
        }
        return AccountStatus(account, client)
    }

    /** The signed-in account's email, or null when the session is not one the server knows. */
    suspend fun accountEmail(): String? {
        val result = call("account-me")
        if (result == JsonNull) return null
        val account = result as? JsonObject ?: invalidAccountResponse()
        return (account["email"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: invalidAccountResponse()
    }

    private fun invalidAccountResponse(): Nothing = throw ServerError(
        200, "Pully could not read the server's account response. Try again.", kind = ServerErrorKind.InvalidResponse, format = RpcResponseFormat.Json,
    )

    suspend fun agentKeys(): List<AgentKey> = call("agent-keys").objects().map { it.asAgentKey() }

    /** A new key: the key itself, shown once, and how the list shows it. */
    suspend fun createAgentKey(name: String): Pair<String, AgentKey> {
        val o = call("agent-key-create") { put("name", name) }.jsonObject
        return o.s("key") to o.getValue("item").jsonObject.asAgentKey()
    }

    suspend fun revokeAgentKey(id: String) { call("agent-key-revoke") { put("id", id) } }

    /** The account's synced settings: each one's value and when it was changed. */
    suspend fun accountSettings(): Map<String, SyncedSetting> = call("account-settings").jsonObject.asSyncedSettings()

    /** Sends [values]; each one stays only if it is newer than the account's. Answers the merged set. */
    suspend fun mergeAccountSettings(values: Map<String, SyncedSetting>): Map<String, SyncedSetting> =
        call("account-settings-merge") {
            put("values", buildJsonObject { values.forEach { (name, setting) -> put(name, buildJsonObject { put("value", setting.value); put("at", setting.at) }) } })
        }.jsonObject.asSyncedSettings()

    /** The diffs agents posted to the account, newest first. */
    suspend fun postedDiffs(): List<PostedRow> = call("posted-list").objects().map { it.asPostedRow() }

    suspend fun deletePosted(id: String) { call("posted-delete") { put("id", id) } }

    /** Keeps [report] in the account's feedback, for agents to read and act on later. */
    suspend fun sendFeedback(report: FeedbackPayload) { call("feedback-send", report.toJson()) }

    /** The account's feedback, newest first. */
    suspend fun feedback(): List<FeedbackItem> = call("feedback-list").jsonObject.arr("items").map { it.asFeedbackItem() }

    // --- Lists ---

    /**
     * `my-pulls-cached` first for an instant list, then `my-pulls` for a fresh one. [revalidate] false never has the server
     * ask GitHub. [classify] has Jev sort the open ones it has not sorted yet, in the background.
     */
    suspend fun myPullsCached(state: String, revalidate: Boolean = true, classify: Boolean = false): JsonObject? =
        call("my-pulls-cached") { put("state", state); if (!revalidate) put("revalidate", false); if (classify) put("classify", true) } as? JsonObject
    suspend fun myPulls(state: String): List<PullRow> = call("my-pulls") { put("state", state) }.asPulls()

    /** One page of a repository's open pull requests; the first comes from the server's copy unless [fresh] asks GitHub. */
    suspend fun repoPulls(owner: String, repo: String, next: String? = null, fresh: Boolean = false): JsonObject =
        call("repo-pulls") { put("owner", owner); put("repo", repo); next?.let { put("next", it) }; if (fresh) put("fresh", true) }.jsonObject

    suspend fun recent(): List<JsonObject> = (call("recent-diffs") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    suspend fun repoInfo(): JsonObject = call("repo-info").jsonObject

    /** `analytics-summary`: what the server counted over the last [days] days (7, 30 or 90), for Settings, Stats. */
    suspend fun analyticsSummary(days: Int): AnalyticsSummary = analyticsSummaryRaw(days).asAnalyticsSummary()

    /** The answer as the server sent it, so the Stats page can keep it on the phone and open on it next time (O10). */
    suspend fun analyticsSummaryRaw(days: Int): JsonElement = call("analytics-summary") { put("days", days) }
}

internal fun JsonElement.objects(): List<JsonObject> = (this as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

internal fun JsonElement.arrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

@Suppress("unused")
private fun JsonElement.firstArray(): JsonArray = jsonArray

/** An earlier chat of a diff, as `chat-history` lists it. */
data class ChatHistoryEntry(val id: String, val title: String, val at: String, val turns: Int)
