package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The address a public [Repo] calls; it never reaches a Pully server. */
const val PUBLIC_REVIEW_SERVER = "https://api.github.com"

/**
 * The review page's reading RPCs answered from GitHub's tokenless public API, so a public pull request opens in Pully's
 * own review page instead of on GitHub ("if I click on one of these, I am taken to GitHub ... make it so that those for
 * sure do not open in GitHub", recording 23 Oct 12:12, 00:50 to 01:12). It sends no credentials and no Pully server
 * address, reads only public repositories, and answers anything that writes, asks AI or needs an account with an error.
 * GitHub allows 60 tokenless reads an hour per address, so each answer is kept for a few minutes and one page open
 * costs about four reads.
 */
class PublicReviewTransport(private val transport: RpcTransport, private val nowMillis: () -> Long) : RpcTransport {
    private val reads = Mutex()
    private val kept = linkedMapOf<String, Pair<Long, JsonElement>>()
    private val opened = mutableMapOf<String, PublicPull>()

    private class PublicPull(val prefix: String, val base: String, val head: String, val renamed: Map<String, String>)

    override suspend fun execute(request: RpcRequest): RpcResponse {
        require(request.url == "$PUBLIC_REVIEW_SERVER/api/rpc" && request.headers.isEmpty()) { "Public reviews never carry credentials or leave GitHub." }
        val call = Json.parseToJsonElement(request.body) as? JsonObject ?: return failure("Pully could not read this request.")
        val name = (call["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
        val result = try {
            when (name) {
                "github-pr" -> open(args)
                "pr-overview" -> buildJsonObject { put("overview", overview(args)) }
                "pr-text" -> overview(args)
                "pr-stack" -> buildJsonObject { put("stack", JsonNull) }
                "review-state", "reviewed-get", "review-data" -> JsonObject(emptyMap())
                "ai-task-list" -> JsonArray(emptyList())
                "jev-files" -> buildJsonObject { put("enabled", false) }
                "highlight" -> JsonObject(emptyMap())
                "file-content" -> fileContent(args)
                else -> return failure(SIGN_IN)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PublicBrowseError) {
            return failure(e.message ?: "GitHub could not answer. Try again.")
        } catch (_: IllegalArgumentException) {
            return failure("This pull request cannot be read publicly.")
        }
        return RpcResponse(200, buildJsonObject { put("result", result) }.toString(), "application/json")
    }

    private fun failure(message: String) = RpcResponse(400, buildJsonObject { put("error", message) }.toString(), "application/json")

    private fun prefix(args: JsonObject): String {
        val owner = args.text("owner")
        val repo = args.text("repo")
        require(segment(owner, 39) && segment(repo, 100)) { "Invalid repository" }
        return "/repos/$owner/$repo"
    }

    private fun number(args: JsonObject): Int = (args["number"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: throw IllegalArgumentException("Invalid number")

    private suspend fun open(args: JsonObject): JsonElement {
        val prefix = prefix(args)
        val number = number(args)
        val pull = read("$prefix/pulls/$number").obj()
        val base = pull.obj("base").text("sha")
        val head = pull.obj("head").text("sha")
        require(base.isNotEmpty() && head.isNotEmpty())
        // GitHub lists at most 3000 files; three pages keep a large pull request to three reads.
        val files = buildList {
            for (page in 1..3) {
                val items = read("$prefix/pulls/$number/files?per_page=100&page=$page") as? JsonArray ?: throw IllegalArgumentException("Invalid files")
                addAll(items.map { it.obj() })
                if (items.size < 100) break
            }
        }
        val commits = (read("$prefix/pulls/$number/commits?per_page=100") as? JsonArray).orEmpty().map { it.obj() }
        val key = "public:${prefix.removePrefix("/repos/")}#$number@$head"
        opened[key] = PublicPull(prefix, base, head, files.mapNotNull { f -> f.textOrNull("previous_filename")?.let { f.text("filename") to it } }.toMap())
        val author = pull.objOrNull("user")
        return buildJsonObject {
            put("key", key)
            put("groups", JsonArray(emptyList()))
            put("diff", buildJsonObject {
                put("ref", buildJsonObject { put("kind", "github-pr") })
                put("title", pull.text("title"))
                put("label", "#$number")
                put("description", pull.textOrNull("body").orEmpty())
                put("url", pull.text("html_url"))
                put("author", buildJsonObject { put("name", author?.textOrNull("login") ?: "ghost"); put("avatarUrl", author?.textOrNull("avatar_url").orEmpty()) })
                put("base", buildJsonObject { put("sha", base); put("ref", pull.obj("base").text("ref")) })
                put("head", buildJsonObject { put("sha", head); put("ref", pull.obj("head").text("ref")) })
                put("createdAt", pull.text("created_at"))
                put("updatedAt", pull.text("updated_at"))
                put("pullRequest", buildJsonObject { put("state", state(pull)); put("baseSha", base) })
                put("commits", JsonArray(commits.map { c -> buildJsonObject { put("sha", c.text("sha")); put("message", c.obj("commit").text("message")) } }))
                put("files", JsonArray(files.map(::file)))
            })
        }
    }

    private fun file(f: JsonObject): JsonObject {
        val path = f.text("filename")
        val patch = f.textOrNull("patch")
        val additions = (f["additions"] as? JsonPrimitive)?.intOrNull ?: 0
        val deletions = (f["deletions"] as? JsonPrimitive)?.intOrNull ?: 0
        return buildJsonObject {
            put("path", path)
            put("status", when (f.text("status")) { "added" -> "added"; "removed" -> "removed"; "renamed" -> "renamed"; "copied" -> "copied"; else -> "modified" })
            put("additions", additions)
            put("deletions", deletions)
            put("sha", f.textOrNull("sha") ?: path)
            f.textOrNull("previous_filename")?.let { put("previousPath", it) }
            put("isBinary", patch == null && additions + deletions == 0)
            // GitHub leaves the patch out of a very large file; the page then offers the whole file instead.
            if (patch == null && additions + deletions > 0) put("truncated", true)
            put("hunks", JsonArray(patch?.let(::publicHunks).orEmpty()))
        }
    }

    private suspend fun overview(args: JsonObject): JsonObject {
        val prefix = prefix(args)
        val number = number(args)
        val pull = read("$prefix/pulls/$number").obj()
        val commits = (read("$prefix/pulls/$number/commits?per_page=100") as? JsonArray).orEmpty().map { it.obj() }
        val comments = (read("$prefix/issues/$number/comments?per_page=100", HTML) as? JsonArray).orEmpty().map { it.obj() }
        val author = pull.objOrNull("user")
        return buildJsonObject {
            put("id", "public:${pull.longOrZero("id")}")
            put("number", number)
            put("title", pull.text("title"))
            put("url", pull.text("html_url"))
            put("body", pull.textOrNull("body").orEmpty())
            put("bodyHTML", pull.textOrNull("body_html").orEmpty())
            put("createdAt", pull.text("created_at"))
            put("updatedAt", pull.text("updated_at"))
            put("author", actor(author))
            put("state", state(pull))
            pull.textOrNull("merged_at")?.let { put("mergedAt", it) }
            pull.textOrNull("closed_at")?.let { put("closedAt", it) }
            put("head", buildJsonObject {
                put("oid", pull.obj("head").text("sha")); put("ref", pull.obj("head").text("ref"))
                pull.obj("head").objOrNull("repo")?.objOrNull("owner")?.textOrNull("login")?.let { put("owner", it) }
            })
            put("base", buildJsonObject { put("oid", pull.obj("base").text("sha")); put("ref", pull.obj("base").text("ref")) })
            // GitHub's own merge answer; null while it is still working it out, which the page shows as checking.
            put("mergeable", when ((pull["mergeable"] as? JsonPrimitive)?.booleanOrNull) { true -> "mergeable"; false -> "conflicting"; null -> "unknown" })
            put("mergeStateStatus", pull.textOrNull("mergeable_state") ?: "unknown")
            put("viewer", buildJsonObject { put("canUpdate", false) })
            put("merge", buildJsonObject { put("methods", JsonArray(emptyList())) })
            put("commits", JsonArray(commits.map { c ->
                val commit = c.obj("commit")
                val message = commit.text("message")
                buildJsonObject {
                    put("oid", c.text("sha"))
                    put("headline", message.substringBefore('\n'))
                    put("body", message.substringAfter('\n', "").trim())
                    put("committedDate", commit.objOrNull("committer")?.textOrNull("date").orEmpty())
                    put("url", c.textOrNull("html_url").orEmpty())
                    put("author", buildJsonObject {
                        commit.objOrNull("author")?.textOrNull("name")?.let { put("name", it) }
                        c.objOrNull("author")?.textOrNull("login")?.let { put("login", it) }
                        c.objOrNull("author")?.textOrNull("avatar_url")?.let { put("avatarUrl", it) }
                    })
                }
            }))
            put("timeline", JsonArray(comments.map { c ->
                buildJsonObject {
                    put("kind", "comment")
                    put("id", "public-comment:${c.longOrZero("id")}")
                    put("createdAt", c.text("created_at"))
                    put("actor", actor(c.objOrNull("user")))
                    put("url", c.textOrNull("html_url").orEmpty())
                    put("bodyHTML", c.textOrNull("body_html").orEmpty())
                }
            }))
        }
    }

    private suspend fun fileContent(args: JsonObject): JsonElement {
        val pull = opened[args.text("key")] ?: throw IllegalArgumentException("Unknown review")
        val path = args.text("path")
        require(path.isNotEmpty() && !path.startsWith('/') && path.split('/').none { it == "." || it == ".." })
        suspend fun at(file: String, sha: String): String? = try {
            (read("${pull.prefix}/contents/${file.split('/').joinToString("/", transform = ::encode)}?ref=$sha", RAW) as? JsonPrimitive)?.contentOrNull
        } catch (e: PublicBrowseError) {
            if (e.problem == PublicBrowseProblem.RepositoryUnavailable) null else throw e
        }
        return buildJsonObject {
            put("old", at(pull.renamed[path] ?: path, pull.base)?.let(::JsonPrimitive) ?: JsonNull)
            put("new", at(path, pull.head)?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    /** One GitHub read, kept for [KEEP_MS]; a raw read answers the file's text as a string. */
    private suspend fun read(path: String, accept: String = JSON): JsonElement {
        val key = "$accept $path"
        reads.withLock { kept[key]?.takeIf { nowMillis() - it.first < KEEP_MS }?.let { return it.second } }
        val request = RpcRequest(
            "https://api.github.com$path", mapOf("Accept" to accept, "X-GitHub-Api-Version" to "2022-11-28", "User-Agent" to "Pully/public-review"), "",
            connectTimeoutMs = 10_000, readTimeoutMs = 30_000, timeoutMs = 30_000, method = RpcMethod.Get, responseLimit = 8 * 1024 * 1024,
        )
        val response = try {
            withTimeout(request.timeoutMs) { transport.execute(request) }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw PublicBrowseError(PublicBrowseProblem.Timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw PublicBrowseError(PublicBrowseProblem.Unreachable)
        }
        if (response.status in 300..399) throw PublicBrowseError(PublicBrowseProblem.Redirect)
        if (response.status == 429 || response.status == 403 && (response.rateLimitRemaining == 0L || response.retryAfterSeconds != null))
            throw PublicBrowseError(PublicBrowseProblem.RateLimited, response.retryAfterSeconds)
        if (response.status == 404 || response.status == 403) throw PublicBrowseError(PublicBrowseProblem.RepositoryUnavailable)
        if (response.status != 200) throw PublicBrowseError(PublicBrowseProblem.Unavailable)
        val value = if (accept == RAW) JsonPrimitive(response.body) else {
            val text = response.body.trimStart('﻿', ' ', '\t', '\r', '\n')
            if (text.startsWith("<")) throw PublicBrowseError(PublicBrowseProblem.WebPage)
            runCatching { Json.parseToJsonElement(text) }.getOrElse { throw PublicBrowseError(PublicBrowseProblem.InvalidResponse) }
        }
        // A private repository never answers a tokenless read, but a public one turned private keeps nothing.
        if ((value as? JsonObject)?.objOrNull("base")?.objOrNull("repo")?.let { (it["private"] as? JsonPrimitive)?.contentOrNull == "true" } == true)
            throw PublicBrowseError(PublicBrowseProblem.RepositoryUnavailable)
        reads.withLock {
            kept[key] = nowMillis() to value
            while (kept.size > 64) kept.remove(kept.keys.first())
        }
        return value
    }

    companion object {
        private const val KEEP_MS = 5 * 60_000L
        private const val JSON = "application/vnd.github+json"
        private const val HTML = "application/vnd.github.full+json"
        private const val RAW = "application/vnd.github.raw"
        const val SIGN_IN = "Sign in to Pully to do this. Public pull requests are read-only."
    }
}

private fun state(pull: JsonObject): String = when {
    pull.textOrNull("merged_at") != null -> "merged"
    pull.textOrNull("state") == "closed" -> "closed"
    (pull["draft"] as? JsonPrimitive)?.contentOrNull == "true" -> "draft"
    else -> "open"
}

private fun actor(user: JsonObject?): JsonElement = user?.let {
    buildJsonObject {
        put("login", it.textOrNull("login") ?: "ghost")
        it.textOrNull("avatar_url")?.let { url -> put("avatarUrl", url) }
        put("bot", it.textOrNull("type") == "Bot")
    }
} ?: JsonNull

/** GitHub's per-file patch as the server's hunks: a header, its ranges, and the lines under it (backend `patch::hunks`). */
fun publicHunks(text: String): List<JsonObject> {
    val header = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
    val lines = text.split('\n')
    val out = mutableListOf<JsonObject>()
    var i = 0
    while (i < lines.size) {
        val match = header.find(lines[i])
        if (match == null) { i++; continue }
        val oldStart = match.groupValues[1].toInt()
        val oldLines = match.groupValues[2].ifEmpty { "1" }.toInt()
        val newStart = match.groupValues[3].toInt()
        val newLines = match.groupValues[4].ifEmpty { "1" }.toInt()
        val head = lines[i]
        i++
        var a = oldLines
        var b = newLines
        val body = mutableListOf<String>()
        while (i < lines.size && !header.containsMatchIn(lines[i])) {
            val line = lines[i]
            if (!line.startsWith("\\") && a <= 0 && b <= 0) break
            if (line.startsWith("-") || line.startsWith(" ")) a--
            if (line.startsWith("+") || line.startsWith(" ")) b--
            body += line
            i++
        }
        out += buildJsonObject {
            put("header", head); put("oldStart", oldStart); put("oldLines", oldLines); put("newStart", newStart); put("newLines", newLines)
            put("patch", body.joinToString("\n"))
        }
    }
    return out
}

private fun segment(value: String, max: Int): Boolean = value.isNotEmpty() && value.length <= max && value !in setOf(".", "..") &&
    value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_." }

private fun encode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val code = byte.toInt() and 255
        if (code in 65..90 || code in 97..122 || code in 48..57 || code.toChar() in "-_.~") append(code.toChar())
        else { append('%'); append("0123456789ABCDEF"[code shr 4]); append("0123456789ABCDEF"[code and 15]) }
    }
}

private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw PublicBrowseError(PublicBrowseProblem.InvalidResponse)
private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: throw PublicBrowseError(PublicBrowseProblem.InvalidResponse)
private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Missing $key")
private fun JsonObject.textOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.longOrZero(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
