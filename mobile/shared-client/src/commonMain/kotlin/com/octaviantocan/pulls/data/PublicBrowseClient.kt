package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

data class ExploreRepository(
    val id: Long,
    val owner: String,
    val name: String,
    val description: String?,
    val stars: Long,
    val language: String?,
) {
    val fullName: String get() = "$owner/$name"
    val url: String get() = "https://github.com/$owner/$name"
}

data class ExploreRepositoryPage(val repositories: List<ExploreRepository>, val nextPage: Int?, val incomplete: Boolean)
data class ExplorePull(val number: Long, val title: String, val author: String?, val draft: Boolean, val updatedAt: String, val url: String)
data class ExplorePullPage(val repository: ExploreRepository, val pulls: List<ExplorePull>, val nextPage: Int?)

enum class PublicBrowseProblem { InvalidSearch, RateLimited, RepositoryUnavailable, Unavailable, InvalidResponse, WebPage, Redirect, Timeout, Unreachable }

/** Fixed safe diagnostics; no upstream message, body, URL or credentials are retained. */
class PublicBrowseError(val problem: PublicBrowseProblem, val retryAfterSeconds: Int? = null) : IllegalStateException(when (problem) {
    PublicBrowseProblem.InvalidSearch -> "Enter a shorter repository name or search."
    PublicBrowseProblem.RateLimited -> "GitHub's public browsing limit has been reached. Try again later."
    PublicBrowseProblem.RepositoryUnavailable -> "This repository is unavailable for public browsing."
    PublicBrowseProblem.Unavailable -> "Public repositories are temporarily unavailable. Try again."
    PublicBrowseProblem.InvalidResponse -> "GitHub could not return these public repositories. Try again."
    PublicBrowseProblem.WebPage -> "GitHub returned a web page instead of repository data. Try again."
    PublicBrowseProblem.Redirect -> "GitHub redirected this repository. Try its current name."
    PublicBrowseProblem.Timeout -> "Public repositories took too long to respond. Try again."
    PublicBrowseProblem.Unreachable -> "Pully could not reach public repositories. Check your connection and try again."
})

/**
 * Anonymous browsing is independent of a private Pully server being configured.
 * This client accepts no server address, personal credentials, Access headers or cache.
 */
class PublicBrowseClient(private val transport: RpcTransport, private val generation: () -> Long = { 0 }) {
    fun source(): RpcSource = RpcSource("https://api.github.com", generation())
    fun isCurrent(source: RpcSource): Boolean = source == this.source()

    suspend fun repositories(query: String = "", page: Int = 1, source: RpcSource = source()): ExploreRepositoryPage {
        validPage(page)
        val search = query.trim()
        if (search.length > 80 || search.any { !it.isLetterOrDigit() && it !in " -_./" })
            throw PublicBrowseError(PublicBrowseProblem.InvalidSearch)
        val term = "${search.ifEmpty { "stars:>100" }} is:public archived:false"
        val value = read(source, "/search/repositories?q=${encodeQuery(term)}&sort=stars&order=desc&per_page=20&page=$page").obj()
        val items = (value["items"] as? JsonArray)?.takeIf { it.size <= 20 } ?: invalid()
        return ExploreRepositoryPage(
            items.filter { it.obj().bool("private") != true }.map { repository(it.obj()) },
            nextPage(items.size, page), value.bool("incomplete_results"),
        )
    }

    /** Every list is preceded by a fresh, tokenless visibility check; no private caches are consulted. */
    suspend fun pulls(owner: String, name: String, page: Int = 1, source: RpcSource = source()): ExplorePullPage {
        validPage(page)
        if (!validSegment(owner, 39) || !validSegment(name, 100)) throw PublicBrowseError(PublicBrowseProblem.InvalidSearch)
        val prefix = "/repos/$owner/$name"
        val repo = repository(read(source, prefix).obj())
        if (!repo.owner.equals(owner, true) || !repo.name.equals(name, true)) invalid()
        val items = (read(source, "$prefix/pulls?state=open&sort=updated&direction=desc&per_page=20&page=$page") as? JsonArray)?.takeIf { it.size <= 20 } ?: invalid()
        return ExplorePullPage(repo, items.map { value ->
            val pull = value.obj()
            if (pull.string("state") != "open") invalid()
            val number = pull.number("number", positive = true)
            ExplorePull(number, pull.string("title").bounded(512), (pull["user"]?.takeUnless { it == JsonNull }?.obj())?.optionalString("login")?.bounded(128), pull.bool("draft"), pull.string("updated_at").bounded(64), "${repo.url}/pull/$number")
        }, nextPage(items.size, page))
    }

    private suspend fun read(source: RpcSource, path: String): JsonElement {
        requireCurrent(source)
        val request = RpcRequest(
            "https://api.github.com$path", mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28", "User-Agent" to "Pully/public-explore"), "",
            connectTimeoutMs = 10_000, readTimeoutMs = 30_000, timeoutMs = 30_000, method = RpcMethod.Get, responseLimit = 2 * 1024 * 1024,
        )
        val response = try {
            withTimeout(request.timeoutMs) { transport.execute(request) }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            requireCurrent(source)
            throw PublicBrowseError(PublicBrowseProblem.Timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            requireCurrent(source)
            throw PublicBrowseError(PublicBrowseProblem.Unreachable)
        }
        requireCurrent(source)
        if (response.status in 300..399) throw PublicBrowseError(PublicBrowseProblem.Redirect)
        if (response.status == 429 || response.status == 403 && (response.rateLimitRemaining == 0L || response.retryAfterSeconds != null))
            throw PublicBrowseError(PublicBrowseProblem.RateLimited, response.retryAfterSeconds)
        if (response.status == 404) throw PublicBrowseError(PublicBrowseProblem.RepositoryUnavailable)
        if (response.status != 200) throw PublicBrowseError(PublicBrowseProblem.Unavailable)
        val text = response.body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val mime = response.contentType.substringBefore(';').trim().lowercase()
        if (mime in setOf("text/html", "application/xhtml+xml") || text.startsWith("<!doctype html", true) || text.startsWith("<html", true)) throw PublicBrowseError(PublicBrowseProblem.WebPage)
        return runCatching { Json.parseToJsonElement(text) }.getOrElse { invalid() }
    }

    private suspend fun requireCurrent(source: RpcSource) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(source)) throw CancellationException("Public browsing source changed.")
    }
}

private fun repository(value: JsonObject): ExploreRepository {
    if (value.bool("private")) throw PublicBrowseError(PublicBrowseProblem.RepositoryUnavailable)
    val owner = value["owner"]?.obj()?.string("login") ?: invalid()
    val name = value.string("name")
    if (!validSegment(owner, 39) || !validSegment(name, 100) || value.string("full_name") != "$owner/$name") invalid()
    return ExploreRepository(value.number("id", positive = true), owner, name, value.optionalString("description")?.bounded(512), value.number("stargazers_count"), value.optionalString("language")?.bounded(80))
}
private fun validSegment(value: String, max: Int): Boolean = value.isNotEmpty() && value.length <= max && value !in setOf(".", "..") && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_." }
private fun validPage(page: Int) { if (page !in 1..10) throw PublicBrowseError(PublicBrowseProblem.InvalidSearch) }
private fun nextPage(count: Int, page: Int): Int? = (page + 1).takeIf { count == 20 && page < 10 }
private fun invalid(): Nothing = throw PublicBrowseError(PublicBrowseProblem.InvalidResponse)
private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: invalid()
private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
private fun JsonObject.optionalString(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid() }
private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
private fun JsonObject.number(key: String, positive: Boolean = false): Long = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= if (positive) 1 else 0 } ?: invalid()
private fun String.bounded(max: Int): String { if (any { it.isISOControl() && it !in "\n\r\t" }) invalid(); return take(max).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it } }
private fun encodeQuery(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val code = byte.toInt() and 255
        if (code in 65..90 || code in 97..122 || code in 48..57 || code.toChar() in "-_.~") append(code.toChar())
        else { append('%'); append("0123456789ABCDEF"[code shr 4]); append("0123456789ABCDEF"[code and 15]) }
    }
}
