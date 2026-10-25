package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

enum class PersonalPullRelation(internal val wire: String) {
    ReviewRequested("reviewRequested"), Authored("authored"), Involved("involved")
}

data class PersonalRepositoryPage(val repositories: List<ExploreRepository>, val nextPage: Int?, val incomplete: Boolean)
data class PersonalPull(
    val repository: ExploreRepository,
    val number: Long,
    val title: String,
    val author: String?,
    val draft: Boolean,
    val createdAt: String,
    val updatedAt: String,
) {
    val url: String get() = "${repository.url}/pull/$number"
}
data class PersonalRepositoryPullPage(val repository: ExploreRepository, val pulls: List<PersonalPull>, val nextPage: Int?, val incomplete: Boolean)
data class PersonalInboxPage(val relation: PersonalPullRelation, val pulls: List<PersonalPull>, val nextPage: Int?, val incomplete: Boolean)
data class PersonalRepositoryCapabilities(val consentAvailable: Boolean, val readEnabled: Boolean)

/** An account repository is distinct from anonymous/public repository selections. */
data class PersonalRepository(val metadata: ExploreRepository, val private: Boolean) {
    val id: Long get() = metadata.id
    val owner: String get() = metadata.owner
    val name: String get() = metadata.name
    val fullName: String get() = metadata.fullName
    val url: String get() = metadata.url
}
data class PersonalAccountPull(val repository: PersonalRepository, val number: Long, val title: String,
    val author: String?, val draft: Boolean, val createdAt: String, val updatedAt: String) {
    val url: String get() = "${repository.url}/pull/$number"
}
data class PersonalAccountRepositoryPage(val repositories: List<PersonalRepository>, val nextPage: Int?, val incomplete: Boolean)
data class PersonalAccountRepositoryPullPage(val repository: PersonalRepository, val pulls: List<PersonalAccountPull>, val nextPage: Int?, val incomplete: Boolean)
data class PersonalAccountInboxPage(val relation: PersonalPullRelation, val pulls: List<PersonalAccountPull>, val nextPage: Int?, val incomplete: Boolean)

enum class PersonalReadProblem {
    InvalidRequest, Unauthorized, Reconnect, Forbidden, RateLimited, Unavailable,
    InvalidResponse, WebPage, Redirect, HttpRejected, Timeout, Unreachable,
}

/** Only fixed recovery categories survive the account service; its body/message is never retained. */
class PersonalReadError(val problem: PersonalReadProblem, val retryAfterSeconds: Int? = null) : IllegalStateException(when (problem) {
    PersonalReadProblem.InvalidRequest -> "Choose a valid repository or page."
    PersonalReadProblem.Unauthorized -> "Sign in again to see your GitHub pull requests."
    PersonalReadProblem.Reconnect -> "Reconnect your GitHub account to continue."
    PersonalReadProblem.Forbidden -> "This repository is unavailable for this account."
    PersonalReadProblem.RateLimited -> "GitHub reads are busy. Wait a moment, then try again."
    PersonalReadProblem.Unavailable -> "Your GitHub repositories are temporarily unavailable."
    PersonalReadProblem.InvalidResponse -> "Pully could not return your GitHub repositories. Try again."
    PersonalReadProblem.WebPage -> "The account service returned a web page. Try again later."
    PersonalReadProblem.Redirect -> "The account service redirected this request. Try again later."
    PersonalReadProblem.HttpRejected -> "The account service could not accept this request. Try again later."
    PersonalReadProblem.Timeout -> "Your GitHub repositories took too long to respond. Try again."
    PersonalReadProblem.Unreachable -> "Pully could not reach your GitHub repositories. Check your connection and try again."
})

/** A verified account's public-read capability. It cannot select an origin, bearer or account. */
class PersonalReadClient internal constructor(
    private val origin: PersonalAuthOrigin,
    private val session: StoredPersonalSession,
    private val transport: RpcTransport,
    private val isCurrent: () -> Boolean,
    private val requests: Semaphore,
    private val effectiveAuthority: PersonalAuthority = PersonalAuthority.PublicRead,
    private val onAccountRejected: (PersonalReadProblem) -> Unit = {},
) {
    val current: Boolean get() = isCurrent()
    val accountId: String get() = session.accountId
    val githubId: Long get() = session.githubId

    suspend fun capabilities(): PersonalRepositoryCapabilities {
        val value = read("/account/capabilities", effectiveAuthority)
        val capability = value["privateRepositories"].obj()
        val enabled = capability.bool("readEnabled")
        if (enabled != (effectiveAuthority == PersonalAuthority.PrivateRepositories)) invalidRead()
        return PersonalRepositoryCapabilities(capability.bool("consentAvailable"), enabled)
    }

    suspend fun repositories(page: Int = 1): PersonalRepositoryPage {
        requireCurrent()
        validPage(page)
        val value = read("/repositories?page=$page")
        val repositories = value.items("repositories").map { repository(it.obj()) }
        if (repositories.map { it.id }.distinct().size != repositories.size) invalidRead()
        return PersonalRepositoryPage(repositories, value.nextPage(page), value.bool("incomplete"))
    }

    /** The selected numeric repository identity remains required across name reuse and pagination. */
    suspend fun pulls(selected: ExploreRepository, page: Int = 1): PersonalRepositoryPullPage {
        requireCurrent()
        validPage(page)
        if (selected.id <= 0 || !validSegment(selected.owner, 39) || !validSegment(selected.name, 100))
            throw PersonalReadError(PersonalReadProblem.InvalidRequest)
        val value = read("/repositories/${selected.owner}/${selected.name}/pulls?page=$page")
        val repository = repository(value["repository"].obj())
        if (repository.id != selected.id || !repository.owner.equals(selected.owner, true) || !repository.name.equals(selected.name, true)) invalidRead()
        val pulls = value.items("pulls").map { pull(it.obj(), repository) }
        if (pulls.map { it.number }.distinct().size != pulls.size) invalidRead()
        return PersonalRepositoryPullPage(repository, pulls, value.nextPage(page), value.bool("incomplete"))
    }

    suspend fun pulls(relation: PersonalPullRelation = PersonalPullRelation.ReviewRequested, page: Int = 1): PersonalInboxPage {
        requireCurrent()
        validPage(page)
        val value = read("/pulls?relation=${relation.wire}&page=$page")
        if (value.string("relation") != relation.wire) invalidRead()
        val pulls = value.items("pulls").map { entry -> entry.obj().let { pull(it, repository(it["repository"].obj())) } }
        if (pulls.map { it.repository.id to it.number }.distinct().size != pulls.size) invalidRead()
        return PersonalInboxPage(relation, pulls, value.nextPage(page), value.bool("incomplete"))
    }

    internal suspend fun read(path: String, authority: PersonalAuthority = PersonalAuthority.PublicRead): JsonObject {
        requireCurrent()
        val request = RpcRequest("${origin.base}/api/personal/v1/github$path",
            mapOf("Accept" to "application/json", "Cache-Control" to "no-store", "Authorization" to "Bearer ${session.token}"), "",
            connectTimeoutMs = 10_000, readTimeoutMs = 60_000, timeoutMs = 60_000,
            method = RpcMethod.Get, responseLimit = MAX_BYTES)
        if (!requests.tryAcquire()) throw PersonalReadError(PersonalReadProblem.Unavailable)
        val response = try {
            withTimeout(request.timeoutMs) { transport.execute(request) }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive(); requireCurrent()
            throw PersonalReadError(PersonalReadProblem.Timeout)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { requireCurrent(); throw PersonalReadError(PersonalReadProblem.Unreachable) }
        finally { requests.release() }
        requireCurrent()
        if (response.status in 300..399) throw PersonalReadError(PersonalReadProblem.Redirect)
        val body = response.body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val mime = response.contentType.substringBefore(';').trim().lowercase()
        if (mime in setOf("text/html", "application/xhtml+xml") || body.startsWith("<!doctype html", true) || body.startsWith("<html", true))
            throw PersonalReadError(PersonalReadProblem.WebPage)
        if (response.body.length > MAX_BYTES || response.body.encodeToByteArray().size > MAX_BYTES) invalidRead()
        if (mime != "application/json" && !mime.endsWith("+json")) invalidRead()
        val value = try { Json.parseToJsonElement(body).obj() } catch (_: Exception) { invalidRead() }
        if (response.status != 200) {
            val error = responseError(response.status, value)
            if (error.problem == PersonalReadProblem.Unauthorized || error.problem == PersonalReadProblem.Reconnect)
                onAccountRejected(error.problem)
            throw error
        }
        if (value.string("accountId") != session.accountId || value.number("githubId", positive = true) != session.githubId ||
            value.string("authority") != (if (authority == PersonalAuthority.PublicRead) "publicRead" else "privateRepositories")) invalidRead()
        requireCurrent()
        return value
    }

    private suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("The personal read owner changed.")
    }

    override fun toString(): String = "PersonalReadClient([bound])"

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/** Minted only by a verified runtime with separately confirmed repository consent. */
class PersonalAccountReadClient internal constructor(origin: PersonalAuthOrigin, session: StoredPersonalSession,
    transport: RpcTransport, isCurrent: () -> Boolean, requests: Semaphore, onAccountRejected: (PersonalReadProblem) -> Unit = {}) {
    private val access = PersonalReadClient(origin, session, transport, isCurrent, requests, PersonalAuthority.PrivateRepositories, onAccountRejected)
    val current: Boolean get() = access.current
    val accountId: String get() = access.accountId
    val githubId: Long get() = access.githubId

    suspend fun repositories(page: Int = 1): PersonalAccountRepositoryPage {
        requireCurrent(); validPage(page)
        val value = read("/repositories?page=$page")
        val repositories = value.items("repositories").map { accountRepository(it.obj()) }
        if (repositories.map { it.id }.distinct().size != repositories.size) invalidRead()
        return PersonalAccountRepositoryPage(repositories, value.nextPage(page), value.bool("incomplete"))
    }

    suspend fun pulls(selected: PersonalRepository, page: Int = 1): PersonalAccountRepositoryPullPage {
        requireCurrent(); validPage(page)
        if (selected.id <= 0 || !validSegment(selected.owner, 39) || !validSegment(selected.name, 100))
            throw PersonalReadError(PersonalReadProblem.InvalidRequest)
        val value = read("/repositories/${selected.owner}/${selected.name}/pulls?page=$page")
        val repository = accountRepository(value["repository"].obj())
        if (repository.id != selected.id || repository.private != selected.private ||
            !repository.owner.equals(selected.owner, true) || !repository.name.equals(selected.name, true)) invalidRead()
        val pulls = value.items("pulls").map { accountPull(it.obj(), repository) }
        if (pulls.map { it.number }.distinct().size != pulls.size) invalidRead()
        return PersonalAccountRepositoryPullPage(repository, pulls, value.nextPage(page), value.bool("incomplete"))
    }

    suspend fun pulls(relation: PersonalPullRelation = PersonalPullRelation.ReviewRequested, page: Int = 1): PersonalAccountInboxPage {
        requireCurrent(); validPage(page)
        val value = read("/pulls?relation=${relation.wire}&page=$page")
        if (value.string("relation") != relation.wire) invalidRead()
        val pulls = value.items("pulls").map { item -> item.obj().let { accountPull(it, accountRepository(it["repository"].obj())) } }
        if (pulls.map { it.repository.id to it.number }.distinct().size != pulls.size) invalidRead()
        return PersonalAccountInboxPage(relation, pulls, value.nextPage(page), value.bool("incomplete"))
    }

    private suspend fun read(path: String) = access.read("/account$path", PersonalAuthority.PrivateRepositories)
    private suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (!current) throw CancellationException("The personal account read owner changed.")
    }
    override fun toString(): String = "PersonalAccountReadClient([bound])"
}

private fun responseError(status: Int, value: JsonObject): PersonalReadError {
    val error = value["error"] as? JsonObject ?: return PersonalReadError(PersonalReadProblem.HttpRejected)
    if ((error["message"] as? JsonPrimitive)?.isString != true) return PersonalReadError(PersonalReadProblem.HttpRejected)
    val code = (error["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val problem = when (status to code) {
        400 to "invalidRequest" -> PersonalReadProblem.InvalidRequest
        401 to "unauthorized" -> PersonalReadProblem.Unauthorized
        401 to "reconnect" -> PersonalReadProblem.Reconnect
        403 to "forbidden" -> PersonalReadProblem.Forbidden
        429 to "rateLimited" -> PersonalReadProblem.RateLimited
        503 to "unavailable" -> PersonalReadProblem.Unavailable
        502 to "invalidResponse" -> PersonalReadProblem.InvalidResponse
        else -> return PersonalReadError(PersonalReadProblem.HttpRejected)
    }
    val retry = error["retryAfterSeconds"]?.takeUnless { it == JsonNull }?.let {
        (it as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { seconds -> seconds in 1L..3600L }?.toInt() ?: invalidRead()
    }
    return PersonalReadError(problem, retry)
}

private fun repository(value: JsonObject): ExploreRepository {
    if (value["private"]?.takeUnless { it == JsonNull }?.let { (it as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != false } == true) invalidRead()
    return repositoryMetadata(value)
}

private fun accountRepository(value: JsonObject): PersonalRepository = PersonalRepository(repositoryMetadata(value), value.bool("private"))
private fun repositoryMetadata(value: JsonObject): ExploreRepository {
    val owner = value.string("owner")
    val name = value.string("name")
    if (!validSegment(owner, 39) || !validSegment(name, 100) || value.string("fullName") != "$owner/$name" ||
        value.string("url") != "https://github.com/$owner/$name") invalidRead()
    return ExploreRepository(value.number("id", positive = true), owner, name,
        value.optionalString("description")?.bounded(512), value.number("stars"), value.optionalString("language")?.bounded(80))
}

private fun accountPull(value: JsonObject, repository: PersonalRepository): PersonalAccountPull {
    val public = pull(value, repository.metadata)
    return PersonalAccountPull(repository, public.number, public.title, public.author, public.draft, public.createdAt, public.updatedAt)
}

private fun pull(value: JsonObject, repository: ExploreRepository): PersonalPull {
    val number = value.number("number", positive = true).takeIf { it <= Int.MAX_VALUE } ?: invalidRead()
    if (value.string("url") != "${repository.url}/pull/$number") invalidRead()
    return PersonalPull(repository, number, value.string("title").bounded(512), value.optionalString("author")?.bounded(128),
        value.bool("draft"), value.string("createdAt").bounded(64), value.string("updatedAt").bounded(64))
}
private fun validSegment(value: String, max: Int): Boolean = value.isNotEmpty() && value.length <= max && value !in setOf(".", "..") &&
    value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_." }
private fun validPage(page: Int) { if (page !in 1..10) throw PersonalReadError(PersonalReadProblem.InvalidRequest) }
private fun invalidRead(): Nothing = throw PersonalReadError(PersonalReadProblem.InvalidResponse)
private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: invalidRead()
private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidRead()
private fun JsonObject.optionalString(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidRead() }
private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalidRead()
private fun JsonObject.number(key: String, positive: Boolean = false): Long = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= if (positive) 1 else 0 } ?: invalidRead()
private fun JsonObject.items(key: String): JsonArray = (this[key] as? JsonArray)?.takeIf { it.size <= 20 } ?: invalidRead()
private fun JsonObject.nextPage(page: Int): Int? {
    if ("nextPage" !in this) invalidRead()
    return this["nextPage"]?.takeUnless { it == JsonNull }?.let {
        (it as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { next -> next == page + 1L && next in 2L..10L }?.toInt() ?: invalidRead()
    }
}
private fun String.bounded(max: Int): String {
    if (any { it.isISOControl() && it !in "\n\r\t" }) invalidRead()
    return take(max).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
}
