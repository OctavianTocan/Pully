package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/*
 * Typed views of the server answers the review page reads. Field names follow the core
 * TypeScript types they come from (`types/analyze.ts`, `types/comment-threads.ts`,
 * `local-rpc.ts`), so a port can be checked against the web source line by line.
 */

internal fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.sOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
internal fun JsonObject.i(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.l(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull ?: (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong()
internal fun JsonObject.d(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.b(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.arr(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** `AiUsage`. */
data class Usage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val costUsd: Double? = null,
    val durationMs: Long? = null,
    val model: String? = null,
)

fun JsonObject?.asUsage(): Usage? = this?.let {
    Usage(it.l("inputTokens"), it.l("outputTokens"), it.l("cacheReadTokens"), cacheWriteTokens = it.l("cacheWriteTokens"), reasoningTokens = it.l("reasoningTokens"), costUsd = it.d("costUsd"), durationMs = it.l("durationMs"), model = it.sOrNull("model"))
}

/** `AiActivity`: one work-log entry; entries update in place, merge by [id]. */
data class Activity(
    val id: String,
    val kind: String,
    val title: String,
    val text: String? = null,
    val tool: String? = null,
    val status: String = "done",
    val startedAt: Long = 0,
    val endedAt: Long? = null,
)

fun JsonObject.asActivity(): Activity = Activity(s("id"), s("kind"), s("title"), sOrNull("text"), sOrNull("tool"), s("status").ifBlank { "done" }, l("startedAt") ?: 0, l("endedAt"))

/** `AiTaskSnapshot`, with activities already merged across polls. */
data class TaskState(
    val id: String,
    val kind: String,
    val key: String,
    val label: String = "",
    val engine: String = "",
    val model: String? = null,
    val effort: String? = null,
    val status: String = "running",
    val rev: Int = 0,
    val activities: List<Activity> = emptyList(),
    val error: String? = null,
    val usage: Usage? = null,
    val result: String? = null,
    val startedAt: Long = 0,
    val endedAt: Long? = null,
    val lens: String? = null,
    val threadId: String? = null,
    val text: String? = null,
) {
    val running: Boolean get() = status == "running"

    /** Applies a newer snapshot: fields replace, activities merge by id. */
    fun merge(next: JsonObject): TaskState {
        val byId = activities.associateBy { it.id }.toMutableMap()
        next.arr("activities").map { it.asActivity() }.forEach { byId[it.id] = it }
        return copy(
            status = next.s("status").ifBlank { status },
            rev = next.i("rev") ?: rev,
            activities = byId.values.sortedBy { it.startedAt },
            error = next.sOrNull("error") ?: error,
            usage = next.obj("usage").asUsage() ?: usage,
            result = next.sOrNull("result") ?: result,
            endedAt = next.l("endedAt") ?: endedAt,
        )
    }
}

fun JsonObject.asTaskState(): TaskState = TaskState(
    id = s("id"), kind = s("kind"), key = s("key"), label = s("label"), engine = s("engine"),
    model = sOrNull("model"), effort = sOrNull("effort"), status = s("status").ifBlank { "running" }, rev = i("rev") ?: 0,
    activities = arr("activities").map { it.asActivity() }, error = sOrNull("error"), usage = obj("usage").asUsage(),
    result = sOrNull("result"), startedAt = l("startedAt") ?: 0, endedAt = l("endedAt"), lens = sOrNull("lens"),
    threadId = sOrNull("threadId"), text = sOrNull("text"),
)

/** `CritiqueFinding`. `side` is `additions` or `deletions`. */
data class CritiqueFinding(
    val id: String,
    val path: String,
    val side: String,
    val line: Int,
    val startLine: Int?,
    val severity: String,
    val title: String,
    val body: String,
    val suggestion: String?,
)

/** `CritiqueResult`. */
data class Critique(
    val summary: String,
    val findings: List<CritiqueFinding>,
    val dropped: Int,
    val headSha: String?,
    val engine: String,
    val model: String?,
    val effort: String?,
    val lens: String?,
    val generatedAt: String,
    val usage: Usage?,
)

fun JsonObject.asCritique(): Critique = Critique(
    summary = s("summary"),
    findings = arr("findings").map {
        CritiqueFinding(it.s("id"), it.s("path"), it.s("side").ifBlank { "additions" }, it.i("line") ?: 0, it.i("startLine"), it.s("severity"), it.s("title"), it.s("body"), it.sOrNull("suggestion"))
    },
    dropped = i("dropped") ?: 0,
    headSha = sOrNull("headSha"),
    engine = s("engine"),
    model = sOrNull("model"),
    effort = sOrNull("effort"),
    lens = sOrNull("lens"),
    generatedAt = s("generatedAt"),
    usage = obj("usage").asUsage(),
)

/** `AiAskTurn` and `AiAskThread`. */
data class AskTurn(val question: String, val answer: String?, val error: String?, val usage: Usage?, val activities: List<Activity>, val startedAt: Long?, val outcome: String?, val taskId: String?)

data class AskThread(val id: String, val path: String, val side: String, val startLine: Int, val endLine: Int, val label: String, val turns: List<AskTurn>)

fun JsonObject.asAskThread(): AskThread {
    val selection = obj("selection") ?: JsonObject(emptyMap())
    return AskThread(
        id = s("id"), path = selection.s("path"), side = selection.s("side"), startLine = selection.i("startLine") ?: 0, endLine = selection.i("endLine") ?: 0, label = s("label"),
        turns = arr("turns").map { AskTurn(it.s("question"), it.sOrNull("answer"), it.sOrNull("error"), it.obj("usage").asUsage(), it.arr("activities").map { a -> a.asActivity() }, it.l("startedAt"), it.sOrNull("outcome"), it.sOrNull("taskId")) },
    )
}

/** One chat message after the analysis turn. */
data class ChatMessage(val role: String, val text: String)

/** `FileNote` and its `HunkNote`s. */
data class FileNote(val path: String, val summary: String, val hunks: Map<Int, String>)

/** The saved AI analysis: overall summary, file notes, model, and who shared it. */
data class Analysis(val overallSummary: String?, val files: List<FileNote>, val model: String?, val generatedAt: String?, val sharedBy: String?)

/** `review-state`: everything AI-made that is saved for this diff. */
data class SavedState(
    val asks: List<AskThread>,
    val critique: Critique?,
    val chat: List<ChatMessage>,
    val aiGroups: List<FileGroup>,
    val analysis: Analysis?,
    val sharedCommentUrl: String?,
    /** The newest tour of this diff (preferring the current head), if one was made. */
    val tour: Tour? = null,
)

/** One piece of a tour stop: `text` (Markdown), `shape` (monospace sketch of kind [lang]) or `diagram` (Mermaid). */
data class TourBlock(val kind: String, val text: String, val lang: String = "", val body: String = "")

data class TourSource(val path: String, val line: Int, val why: String)

data class TourStop(
    val title: String,
    val purpose: String,
    val files: List<String>,
    val takeaway: String,
    val blocks: List<TourBlock>,
    val sources: List<TourSource>,
    val error: String?,
)

/** `TourResult`: a guided tour of the PR with no diffs. */
data class Tour(
    val title: String,
    val overview: String,
    val stops: List<TourStop>,
    val glossary: List<Pair<String, String>>,
    val headSha: String?,
    val model: String?,
    val locale: String,
    val generatedAt: String,
    val usage: Usage?,
)

fun JsonObject.asTour(): Tour = Tour(
    title = s("title"),
    overview = s("overview"),
    stops = arr("stops").map { st ->
        TourStop(
            title = st.s("title"), purpose = st.s("purpose"), files = st.strings("files"), takeaway = st.s("takeaway"),
            blocks = st.arr("blocks").map { b -> TourBlock(b.s("kind"), b.s("text"), b.s("lang"), b.s("body")) },
            sources = st.arr("sources").map { so -> TourSource(so.s("path"), so.i("line") ?: 0, so.s("why")) },
            error = st.sOrNull("error"),
        )
    },
    glossary = arr("glossary").map { it.s("term") to it.s("meaning") },
    headSha = sOrNull("headSha"),
    model = sOrNull("model"),
    locale = s("locale"),
    generatedAt = s("generatedAt"),
    usage = obj("usage").asUsage(),
)

fun JsonElement.asSavedState(): SavedState {
    val o = jsonObject
    return SavedState(
        asks = (o["threads"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.asAskThread() },
        critique = o.obj("critique")?.asCritique(),
        chat = o.arr("chat").map { ChatMessage(it.s("role"), it.s("text")) },
        aiGroups = o.arr("aiGroups").map { it.asGroup() },
        analysis = o.obj("analysis")?.let { a ->
            Analysis(
                overallSummary = a.sOrNull("overallSummary"),
                files = a.arr("files").map { f -> FileNote(f.s("path"), f.s("summary"), f.arr("hunks").associate { h -> (h.i("index") ?: 0) to h.s("note") }) },
                model = a.sOrNull("model"), generatedAt = a.sOrNull("generatedAt"), sharedBy = a.sOrNull("sharedBy"),
            )
        },
        sharedCommentUrl = o.obj("sharedComment")?.sOrNull("url"),
        tour = o.obj("tour")?.asTour(),
    )
}

/** `ReviewComment`. */
data class ThreadComment(val id: Long, val author: String, val avatarUrl: String?, val body: String, val createdAt: String, val url: String?, val pending: Boolean)

/** `CommentThread` (GitHub review thread). `side` is `additions` or `deletions`. */
data class ReviewThread(
    val rootId: Long,
    val path: String,
    val side: String,
    val line: Int?,
    val startLine: Int?,
    val outdated: Boolean,
    val resolved: Boolean,
    val threadId: String?,
    val pending: Boolean,
    val comments: List<ThreadComment>,
)

/** `ReviewSummary`. `state`: approved, changes_requested, commented, dismissed. */
data class ReviewSummary(val id: Long, val author: String, val avatarUrl: String?, val state: String, val body: String, val submittedAt: String?, val url: String?)

/** `ReviewData` plus who the server's token acts as. */
data class ReviewData(val threads: List<ReviewThread>, val summaries: List<ReviewSummary>, val pendingReviewId: Long?, val viewer: String?, val canWrite: Boolean = false) {
    val pendingCount: Int get() = threads.sumOf { t -> t.comments.count { it.pending } }
}

fun JsonElement.asReviewData(): ReviewData {
    val o = jsonObject
    return ReviewData(
        threads = o.arr("threads").map { t ->
            ReviewThread(
                rootId = t.l("rootId") ?: 0, path = t.s("path"), side = t.s("side"), line = t.i("line"), startLine = t.i("startLine"),
                outdated = t.b("outdated"), resolved = t.b("resolved"), threadId = t.sOrNull("threadId"), pending = t.b("pending"),
                comments = t.arr("comments").map { c -> ThreadComment(c.l("id") ?: 0, c.obj("author")?.s("login") ?: "ghost", c.obj("author")?.sOrNull("avatarUrl"), c.s("body"), c.s("createdAt"), c.sOrNull("url"), c.b("pending")) },
            )
        },
        summaries = o.arr("summaries").map { r -> ReviewSummary(r.l("id") ?: 0, r.obj("author")?.s("login") ?: "ghost", r.obj("author")?.sOrNull("avatarUrl"), r.s("state"), r.s("body"), r.sOrNull("submittedAt"), r.sOrNull("url")) },
        pendingReviewId = o.obj("pendingReview")?.l("id"),
        viewer = o.sOrNull("viewer"),
        canWrite = o.b("canWrite"),
    )
}

/** `file-content`: both sides in full; `null` where the side does not exist. */
data class FullFile(val old: String?, val new: String?)

fun JsonElement.asFullFile(): FullFile = jsonObject.let { FullFile(it.sOrNull("old"), it.sOrNull("new")) }
