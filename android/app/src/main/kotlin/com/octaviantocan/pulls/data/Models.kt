package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PullRow(
    val owner: String,
    val repo: String,
    val number: Int,
    val title: String,
    val author: String,
    val updatedAt: String,
    val isDraft: Boolean,
    val role: String = "",
    val checks: String = "",
    val reviewDecision: String = "",
    val mergeable: String = "",
    val requestedFromMe: Boolean = false,
    val labels: List<String> = emptyList(),
    val updatedAtMs: Long = 0,
)

data class ReviewFile(
    val path: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val sha: String = "",
    val patchLines: List<DiffLine>,
)

data class DiffLine(val kind: Char, val text: String, val newLine: Int? = null, val oldLine: Int? = null)

data class FileGroup(val label: String, val summary: String, val paths: List<String>)

data class OpenedReview(
    val key: String,
    val headSha: String,
    val files: List<ReviewFile>,
    val groups: List<FileGroup>,
    val baseSha: String = "",
    /** The PR, compare or commit page on GitHub; empty for local diffs. */
    val url: String = "",
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
    return array.map { row ->
        val obj = row.jsonObject
        PullRow(
            owner = obj.str("owner"),
            repo = obj.str("repo"),
            number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0,
            title = obj.str("title"),
            author = obj.str("author"),
            updatedAt = obj.str("updatedAt"),
            isDraft = obj["isDraft"]?.jsonPrimitive?.contentOrNull == "true",
            role = obj.str("role"),
            checks = obj.str("checks"),
            reviewDecision = obj.str("reviewDecision"),
            mergeable = obj.str("mergeable"),
            requestedFromMe = obj["requestedFromMe"]?.jsonPrimitive?.contentOrNull == "true",
            labels = obj["labels"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
            updatedAtMs = runCatching { java.time.Instant.parse(obj.str("updatedAt")).toEpochMilli() }.getOrDefault(0),
        )
    }
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
    val groups = obj["groups"]?.jsonArray.orEmpty().map { group ->
        val item = group.jsonObject
        FileGroup(item.str("label"), item.str("summary"), item["filePaths"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content })
    }
    val ordered = if (groups.isEmpty()) files else groups.flatMap { group -> group.paths.mapNotNull(byPath::get) }
    return OpenedReview(
        key = obj.str("key"),
        headSha = diff["head"]?.jsonObject?.str("sha").orEmpty(),
        files = ordered.ifEmpty { files },
        groups = groups,
        baseSha = diff["base"]?.jsonObject?.str("sha").orEmpty(),
        url = diff.str("url"),
    )
}

private fun JsonObject.asFile(): ReviewFile {
    val lines = mutableListOf<DiffLine>()
    for (hunk in this["hunks"]?.jsonArray.orEmpty()) {
        var old = hunk.jsonObject["oldStart"]?.jsonPrimitive?.intOrNull ?: 1
        var new = hunk.jsonObject["newStart"]?.jsonPrimitive?.intOrNull ?: 1
        for (line in hunk.jsonObject.str("patch").lineSequence()) {
            when {
                line.startsWith("@@") || line.startsWith("\\") -> Unit
                line.startsWith("+++") || line.startsWith("---") -> Unit
                line.startsWith("+") -> lines += DiffLine('+', line.drop(1), newLine = new, oldLine = null).also { new += 1 }
                line.startsWith("-") -> lines += DiffLine('-', line.drop(1), newLine = null, oldLine = old).also { old += 1 }
                line.startsWith(" ") -> lines += DiffLine(' ', line.drop(1), newLine = new, oldLine = old).also { old += 1; new += 1 }
            }
        }
    }
    return ReviewFile(
        path = str("path"),
        status = str("status"),
        sha = str("sha"),
        additions = this["additions"]?.jsonPrimitive?.intOrNull ?: 0,
        deletions = this["deletions"]?.jsonPrimitive?.intOrNull ?: 0,
        patchLines = lines,
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
