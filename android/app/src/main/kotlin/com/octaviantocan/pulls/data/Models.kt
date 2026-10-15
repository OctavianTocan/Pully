package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One row of `my-pulls` (`MyPull` in core `local-rpc.ts`). */
data class PullRow(
    val owner: String,
    val repo: String,
    val number: Int,
    val title: String,
    val author: String,
    val updatedAt: String,
    val isDraft: Boolean,
    /** `review-requested`, `authored`, `involved` or `owned`. */
    val role: String = "",
    /** `success`, `failure`, `pending`, `none`; empty when unknown. */
    val checks: String = "",
    /** `approved`, `changes-requested`, `review-required`; empty when none. */
    val reviewDecision: String = "",
    /** `mergeable`, `conflicting`, `unknown`; empty when unknown. */
    val mergeable: String = "",
    /** Null when the server did not say (the web then falls back to the role). */
    val requestedFromMe: Boolean? = null,
    val labels: List<String> = emptyList(),
    /** Epoch ms of [updatedAt]; null when it does not parse. */
    val updatedAtMs: Long? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
    val commentsCount: Int = 0,
    val url: String = "",
    /** `open` or `closed`. */
    val state: String = "open",
    val merged: Boolean = false,
    val headSha: String = "",
    val reviewRequests: List<String> = emptyList(),
    /** The branch it merges into; empty when the server did not say. */
    val baseRef: String = "",
    /** The branch it merges from; empty when the server did not say. */
    val headRef: String = "",
    /** Open pull requests only: its place in a stack, null when it is in none. */
    val stack: PullStack? = null,
    /** Jev's classification of its head commit and title; null until the server has one, and while Jev is off. */
    val jev: PullJev? = null,
)

/**
 * What Jev, the server's classifier, made of a listed pull request (`MyPullJev` in core `local-rpc.ts`): its [kind]
 * (`feature`, `fix`, `refactor`, `performance`, `docs`, `tests`, `dependencies` or `infra`), how sure it is of that
 * from 0 to 1, and its [risk] from 0 (trivial) to 3 (high), which can sit between two levels.
 */
data class PullJev(val kind: String, val kindConfidence: Double, val risk: Double)

/**
 * Where a listed pull request sits in a stack (`MyPullStack` in core `local-rpc.ts`): GitHub's own when
 * [native], else inferred from branch names. [position] 1 sits on the base branch; [below] is the
 * number of the pull request this one sits on, when the server knows it.
 */
data class PullStack(val id: String, val position: Int, val size: Int, val native: Boolean, val below: Int?)

data class ReviewFile(
    val path: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val sha: String = "",
    val patchLines: List<DiffLine>,
    val previousPath: String? = null,
    val isBinary: Boolean = false,
    val truncated: Boolean = false,
    val hunks: List<Hunk> = emptyList(),
    /** The server left this file's patch out of a large diff; [Repo.diffPatches] brings it when the file is shown. */
    val patchOmitted: Boolean = false,
)

/**
 * One line of a file's patch. [gapBefore] is how many unmodified lines the patch skips
 * right before this line (the web's "N unmodified lines" separator); [hunk] is the
 * 0-based hunk index, the one the AI file notes refer to.
 */
data class DiffLine(val kind: Char, val text: String, val newLine: Int? = null, val oldLine: Int? = null, val gapBefore: Int = 0, val hunk: Int = 0)

data class Hunk(val header: String, val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int)

/** A rule-based or AI group (`DiffGroup`): one level of children at most, like the web. */
data class FileGroup(
    val label: String,
    val summary: String,
    val paths: List<String>,
    val key: String = label,
    val category: String = "other",
    val children: List<FileGroup> = emptyList(),
) {
    /** Every path in this group and its children, in display order. */
    val allPaths: List<String> get() = paths + children.flatMap { it.paths }
}

data class Author(val login: String, val avatarUrl: String = "")

data class CommitInfo(val sha: String, val message: String)

data class OpenedReview(
    val key: String,
    val headSha: String,
    val files: List<ReviewFile>,
    val groups: List<FileGroup>,
    val baseSha: String = "",
    /** The PR, compare or commit page on GitHub; empty for local diffs. */
    val url: String = "",
    val title: String = "",
    /** Short id after the title, e.g. `#123`. */
    val label: String = "",
    val author: Author? = null,
    val baseRef: String = "",
    val headRef: String = "",
    /** `open`, `closed`, `merged` or `draft`; empty when the source is not a PR. */
    val state: String = "",
    val description: String = "",
    val commits: List<CommitInfo> = emptyList(),
    /** `github-pr`, `github-compare`, `github-commit`, `local` or `paste`. */
    val refKind: String = "",
    /** The server's stored copy, sent because GitHub could not be asked whether it is current. */
    val stale: Boolean = false,
)

data class Finding(
    val severity: String,
    val path: String,
    val line: Int,
    val title: String,
    val body: String,
    val suggestion: String = "",
)

data class SavedReview(
    val findings: List<Finding>,
    val threads: List<String>,
    val chat: List<String>,
)

data class TaskSnap(
    val id: String,
    val status: String,
    val rev: Int,
    val error: String?,
    val result: String?,
    val activities: List<String>,
)

fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
    pairs.forEach { (key, value) -> put(key, value) }
}

fun JsonElement.asPulls(): List<PullRow> {
    val array = this as? JsonArray ?: (this as? JsonObject)?.get("pulls") as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonObject)?.asPull() }
}

fun JsonObject.asPull(): PullRow {
    val obj = this
    fun int(key: String) = obj[key]?.jsonPrimitive?.intOrNull
    fun bool(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.let { it == "true" }
    return PullRow(
        owner = obj.str("owner"),
        repo = obj.str("repo"),
        number = int("number") ?: 0,
        title = obj.str("title"),
        author = obj.str("author"),
        updatedAt = obj.str("updatedAt"),
        isDraft = bool("isDraft") == true,
        role = obj.str("role"),
        checks = obj.str("checks"),
        reviewDecision = obj.str("reviewDecision"),
        mergeable = obj.str("mergeable"),
        requestedFromMe = bool("requestedFromMe"),
        labels = (obj["labels"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.str("name") },
        updatedAtMs = runCatching { java.time.Instant.parse(obj.str("updatedAt")).toEpochMilli() }.getOrNull(),
        additions = int("additions"),
        deletions = int("deletions"),
        changedFiles = int("changedFiles"),
        commentsCount = int("commentsCount") ?: 0,
        url = obj.str("url"),
        state = obj.str("state").ifBlank { "open" },
        merged = bool("merged") == true,
        headSha = obj.str("headSha"),
        reviewRequests = (obj["reviewRequests"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull },
        baseRef = obj.str("baseRef"),
        headRef = obj.str("headRef"),
        stack = (obj["stack"] as? JsonObject)?.asPullStack(),
        jev = (obj["jev"] as? JsonObject)?.asPullJev(),
    )
}

/** A `MyPullJev`; null without a kind or a risk, which every classification has. */
fun JsonObject.asPullJev(): PullJev? {
    val kind = str("kind").ifBlank { return null }
    val risk = this["risk"]?.jsonPrimitive?.doubleOrNull ?: return null
    return PullJev(kind, this["kindConfidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0, risk)
}

/**
 * The `jev-files` answer for an opened diff: whether Jev is on, each classified file's priority by path (`p0` needs
 * a human, `p1` is worth a look, `p2` is low attention), and how many files it is still sorting.
 */
data class JevFiles(val enabled: Boolean, val files: Map<String, String>, val pending: Int)

fun JsonObject.asJevFiles(): JevFiles = JevFiles(
    enabled = this["enabled"]?.jsonPrimitive?.contentOrNull == "true",
    files = (this["files"] as? JsonObject).orEmpty().mapNotNull { (path, value) -> (value as? JsonObject)?.str("priority")?.ifBlank { null }?.let { path to it } }.toMap(),
    pending = this["pending"]?.jsonPrimitive?.intOrNull ?: 0,
)

/** A `MyPullStack`; null without an id or a position, which no real stack lacks. */
fun JsonObject.asPullStack(): PullStack? {
    val position = this["position"]?.jsonPrimitive?.intOrNull ?: return null
    val id = str("id").ifBlank { return null }
    return PullStack(
        id = id,
        position = position,
        size = this["size"]?.jsonPrimitive?.intOrNull ?: 0,
        native = this["native"]?.jsonPrimitive?.contentOrNull == "true",
        below = this["below"]?.jsonPrimitive?.intOrNull,
    )
}

fun JsonElement.asFiles(): List<ReviewFile> {
    val files = (this as? JsonObject)?.get("files") as? JsonArray ?: return emptyList()
    return files.map { it.jsonObject.asFile() }
}

fun JsonElement.asOpened(): OpenedReview {
    val obj = jsonObject
    val diff = obj.getValue("diff").jsonObject
    val files = diff["files"]?.jsonArray.orEmpty().map { it.jsonObject.asFile() }
    val byPath = files.associateBy { it.path }
    val groups = obj["groups"]?.jsonArray.orEmpty().map { it.jsonObject.asGroup() }
    val ordered = if (groups.isEmpty()) files else groups.flatMap { group -> group.allPaths.mapNotNull(byPath::get) }
    val authorObj = diff["author"] as? JsonObject
    return OpenedReview(
        key = obj.str("key"),
        headSha = diff["head"]?.jsonObject?.str("sha").orEmpty(),
        files = ordered.ifEmpty { files },
        groups = groups,
        baseSha = diff["base"]?.jsonObject?.str("sha").orEmpty(),
        url = diff.str("url"),
        title = diff.str("title"),
        label = diff.str("label"),
        author = authorObj?.let { Author(it.str("name"), it.str("avatarUrl")) },
        baseRef = diff["base"]?.jsonObject?.str("ref").orEmpty(),
        headRef = diff["head"]?.jsonObject?.str("ref").orEmpty(),
        state = (diff["pullRequest"] as? JsonObject)?.str("state").orEmpty(),
        description = diff.str("description"),
        commits = diff["commits"]?.jsonArray.orEmpty().map { CommitInfo(it.jsonObject.str("sha"), it.jsonObject.str("message")) },
        refKind = (diff["ref"] as? JsonObject)?.str("kind").orEmpty(),
        stale = obj["stale"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

/** `diff-patches`: each path's hunks, in the diff's own JSON shape. */
fun JsonElement.asPatches(): Map<String, JsonArray> =
    (this as? JsonObject)?.get("files")?.jsonObject.orEmpty().mapNotNull { (path, file) -> (file as? JsonObject)?.get("hunks")?.jsonArray?.let { path to it } }.toMap()

/** This file with the patch [hunks] the server sent later, parsed as [asFile] parses a whole diff. */
fun ReviewFile.withHunks(hunks: JsonArray): ReviewFile {
    val (lines, parsed) = parseHunks(hunks)
    return copy(patchLines = lines, hunks = parsed, patchOmitted = false)
}

/** A `{ key, label, summary, category, filePaths, children }` group from the server. */
fun JsonObject.asGroup(): FileGroup = FileGroup(
    label = str("label"),
    summary = str("summary"),
    paths = this["filePaths"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
    key = str("key").ifBlank { str("label") },
    category = str("category").ifBlank { "other" },
    children = this["children"]?.jsonArray.orEmpty().map { it.jsonObject.asGroup() },
)

private fun parseHunks(json: List<JsonElement>): Pair<List<DiffLine>, List<Hunk>> {
    val lines = mutableListOf<DiffLine>()
    val hunks = mutableListOf<Hunk>()
    // Unmodified lines between hunks, counted on the old side (the new side for an added file).
    var nextOld = 1
    var nextNew = 1
    json.forEachIndexed { index, element ->
        val hunk = element.jsonObject
        val oldStart = hunk["oldStart"]?.jsonPrimitive?.intOrNull ?: 1
        val newStart = hunk["newStart"]?.jsonPrimitive?.intOrNull ?: 1
        hunks += Hunk(hunk.str("header"), oldStart, hunk["oldLines"]?.jsonPrimitive?.intOrNull ?: 0, newStart, hunk["newLines"]?.jsonPrimitive?.intOrNull ?: 0)
        var gap = if (oldStart > 0) oldStart - nextOld else newStart - nextNew
        var old = oldStart
        var new = newStart
        for (line in hunk.str("patch").lineSequence()) {
            val parsed = when {
                line.startsWith("@@") || line.startsWith("\\") -> null
                line.startsWith("+++") || line.startsWith("---") -> null
                line.startsWith("+") -> DiffLine('+', line.drop(1), newLine = new, oldLine = null).also { new += 1 }
                line.startsWith("-") -> DiffLine('-', line.drop(1), newLine = null, oldLine = old).also { old += 1 }
                line.startsWith(" ") -> DiffLine(' ', line.drop(1), newLine = new, oldLine = old).also { old += 1; new += 1 }
                else -> null
            } ?: continue
            lines += parsed.copy(gapBefore = gap.coerceAtLeast(0), hunk = index)
            gap = 0
        }
        nextOld = old
        nextNew = new
    }
    return lines to hunks
}

private fun JsonObject.asFile(): ReviewFile {
    val (lines, hunks) = parseHunks(this["hunks"]?.jsonArray.orEmpty())
    return ReviewFile(
        path = str("path"),
        status = str("status"),
        sha = str("sha"),
        additions = this["additions"]?.jsonPrimitive?.intOrNull ?: 0,
        deletions = this["deletions"]?.jsonPrimitive?.intOrNull ?: 0,
        patchLines = lines,
        previousPath = str("previousPath").ifBlank { null },
        isBinary = this["isBinary"]?.jsonPrimitive?.contentOrNull == "true",
        truncated = this["truncated"]?.jsonPrimitive?.contentOrNull == "true",
        hunks = hunks,
        patchOmitted = this["patchOmitted"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

fun JsonElement.asTask(): TaskSnap {
    val obj = jsonObject
    return TaskSnap(
        id = obj.str("id"),
        status = obj.str("status"),
        rev = obj["rev"]?.jsonPrimitive?.intOrNull ?: 0,
        error = obj["error"]?.jsonPrimitive?.contentOrNull,
        result = obj["result"]?.jsonPrimitive?.contentOrNull,
        activities = obj["activities"]?.jsonArray?.map { it.jsonObject.str("title") }.orEmpty(),
    )
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()


fun JsonElement.asSaved(): SavedReview {
    val obj = jsonObject
    val findings = obj["critique"]?.jsonObject?.get("findings")?.jsonArray.orEmpty().map { finding ->
        val item = finding.jsonObject
        Finding(
            severity = item.str("severity"),
            path = item.str("path"),
            line = item["line"]?.jsonPrimitive?.intOrNull ?: 0,
            title = item.str("title"),
            body = item.str("body"),
            suggestion = item.str("suggestion"),
        )
    }
    val threads = obj["threads"]?.jsonArray.orEmpty().flatMap { thread ->
        val item = thread.jsonObject
        item["turns"]?.jsonArray.orEmpty().map { turn ->
            val row = turn.jsonObject
            "${item.str("label")}: ${row.str("question")} — ${row.str("answer").ifBlank { row.str("error") }}"
        }
    }
    val chat = obj["chat"]?.jsonArray.orEmpty().map { message ->
        val row = message.jsonObject
        "${row.str("role")}: ${row.str("text")}"
    }
    return SavedReview(findings, threads, chat)
}
