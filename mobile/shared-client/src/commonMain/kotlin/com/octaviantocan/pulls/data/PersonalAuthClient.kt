package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** App-owned public auth origin. This is independent of legacy ServerStore and Access configuration. */
class PersonalAuthOrigin private constructor(val base: String) {
    companion object {
        fun parse(raw: String): PersonalAuthOrigin {
            val endpoint = ServerEndpoint.parse(raw)
            require(endpoint.scheme == "https" && '/' !in endpoint.base.substringAfter("://")) {
                "GitHub sign-in requires a trusted HTTPS service."
            }
            return PersonalAuthOrigin(endpoint.base)
        }
    }
}

enum class PersonalAuthProblem {
    NotConfigured, InvalidRequest, Unauthorized, Expired, RateLimited, Unavailable,
    Reconnect, Denied, InvalidResponse, WebPage, Redirect, HttpRejected, Timeout, Unreachable,
}

/** Contains fixed recovery only; no response body, provider message, address, proof or credential. */
class PersonalAuthError(val problem: PersonalAuthProblem, val httpStatus: Int? = null) : IllegalStateException(when (problem) {
    PersonalAuthProblem.NotConfigured -> "GitHub sign-in is not available yet. You can keep browsing public repositories."
    PersonalAuthProblem.InvalidRequest -> "Pully could not start GitHub sign-in. Try again."
    PersonalAuthProblem.Unauthorized -> "Your Pully session is no longer valid. Sign in again."
    PersonalAuthProblem.Expired -> "This sign-in has expired. Start again."
    PersonalAuthProblem.RateLimited -> "Too many sign-in requests. Wait a moment, then try again."
    PersonalAuthProblem.Unavailable -> "GitHub sign-in is temporarily unavailable. Try again."
    PersonalAuthProblem.Reconnect -> "Reconnect your GitHub account to continue."
    PersonalAuthProblem.Denied -> "GitHub sign-in was not approved. You can try again or keep browsing."
    PersonalAuthProblem.InvalidResponse -> "The sign-in service could not return account data. Try again."
    PersonalAuthProblem.WebPage -> "The sign-in service returned a web page. Try again later."
    PersonalAuthProblem.Redirect -> "The sign-in service redirected the request. Try again later."
    PersonalAuthProblem.HttpRejected -> "The sign-in service could not accept this request. Try again later."
    PersonalAuthProblem.Timeout -> "GitHub sign-in took too long to respond. Try again."
    PersonalAuthProblem.Unreachable -> "Pully could not reach sign-in. Check your connection and try again."
})

enum class PersonalAuthority { PublicRead, PrivateRepositories }
data class PersonalAccountStatus(
    val accountId: String,
    val githubId: Long,
    val login: String,
    val authority: PersonalAuthority,
    val sessionExpiresAt: Long,
    val accessExpiresAt: Long?,
    val refreshAvailable: Boolean,
)

/** Only the opaque Pully bearer reaches the native secure store; GitHub credentials stay on the server. */
class PersonalSession internal constructor(val token: String, val status: PersonalAccountStatus, val origin: String) {
    override fun toString(): String = "PersonalSession([redacted])"
}

class PersonalAuthAttempt internal constructor(
    val authorizeUrl: String,
    val expiresAt: Long,
    val pollAfterMs: Long,
    internal val id: String,
    internal val proof: PersonalAuthProof,
    internal val source: RpcSource,
    internal val authority: PersonalAuthority = PersonalAuthority.PublicRead,
    internal val accountId: String? = null,
    internal val githubId: Long? = null,
    /** Set for GitHub CLI device sign-in: the person types it at [authorizeUrl], which is then github.com/login/device. */
    val userCode: String? = null,
) {
    override fun toString(): String = "PersonalAuthAttempt([redacted])"
}

enum class PersonalAttemptPhase { Pending, Ready, Failed, Cancelled, Expired }
data class PersonalAttemptStatus(val phase: PersonalAttemptPhase, val failure: PersonalAuthProblem?)

/** Typed personal-auth routes only. No legacy RPC, shared token, provider key or arbitrary path is accepted. */
class PersonalAuthClient(
    val origin: PersonalAuthOrigin,
    private val transport: RpcTransport = personalAuthTransport(),
    private val generation: () -> Long = { 0 },
) {
    fun source(): RpcSource = RpcSource(origin.base, generation())
    fun isCurrent(source: RpcSource): Boolean = source == source()

    suspend fun enabled(source: RpcSource = source()): Boolean =
        request(PersonalPaths.Capabilities, source = source).obj().let { value ->
            requireCurrent(source)
            value["github"].obj().bool("enabled")
        }

    suspend fun start(proof: PersonalAuthProof, source: RpcSource = source()): PersonalAuthAttempt {
        val value = request(PersonalPaths.Start, buildJsonObject { put("nativeChallenge", proof.challenge) }, source).obj()
        return attempt(value, proof, source)
    }

    /** Only the runtime's captured, verified account can request the separate repository consent. */
    internal suspend fun startRepositoryConsent(proof: PersonalAuthProof, session: StoredPersonalSession, source: RpcSource): PersonalAuthAttempt {
        if (session.origin != origin.base) throw PersonalAuthError(PersonalAuthProblem.InvalidRequest)
        val value = request(PersonalPaths.RepositoryConsent, buildJsonObject { put("nativeChallenge", proof.challenge) }, source, session.token).obj()
        return attempt(value, proof, source, session)
    }

    private fun attempt(value: JsonObject, proof: PersonalAuthProof, source: RpcSource, session: StoredPersonalSession? = null): PersonalAuthAttempt {
        val authority = if (session == null) PersonalAuthority.PublicRead else PersonalAuthority.PrivateRepositories
        val id = value.string("attemptId").takeIf(::canonicalPersonalSecret) ?: invalidPersonal()
        val url = value.string("authorizeUrl")
        val userCode = value["userCode"]?.string()
        if (url == GITHUB_DEVICE_URL) { if (userCode == null || !validUserCode(userCode)) invalidPersonal() }
        else { validateAuthorizeUrl(url, origin, authority); if (userCode != null) invalidPersonal() }
        return PersonalAuthAttempt(url, value.positive("expiresAt"), value.positive("pollAfterMs").takeIf { it in 1_000L..30_000L } ?: invalidPersonal(), id, proof, source,
            authority, session?.accountId, session?.githubId, userCode)
    }

    suspend fun status(attempt: PersonalAuthAttempt): PersonalAttemptStatus {
        val value = request(PersonalPaths.Status, attempt.body(), attempt.source).obj()
        val phase = when (value.string("phase")) {
            "pending" -> PersonalAttemptPhase.Pending
            "ready" -> PersonalAttemptPhase.Ready
            "failed" -> PersonalAttemptPhase.Failed
            "cancelled" -> PersonalAttemptPhase.Cancelled
            "expired" -> PersonalAttemptPhase.Expired
            else -> invalidPersonal()
        }
        val failure = value["failure"]?.takeUnless { it == JsonNull }?.let {
            when (it.string()) {
                "unavailable" -> PersonalAuthProblem.Unavailable
                "reconnect" -> PersonalAuthProblem.Reconnect
                "denied" -> PersonalAuthProblem.Denied
                else -> invalidPersonal()
            }
        }
        if ((phase == PersonalAttemptPhase.Failed) != (failure != null)) invalidPersonal()
        return PersonalAttemptStatus(phase, failure)
    }

    suspend fun claim(attempt: PersonalAuthAttempt): PersonalSession {
        val value = request(PersonalPaths.Claim, attempt.body(), attempt.source).obj()
        val session = value.string("session").also(::validatePersonalSession)
        val status = parseAccount(value["status"].obj())
        if (status.authority != attempt.authority) {
            if (attempt.authority == PersonalAuthority.PublicRead) invalidPersonal()
            throw PersonalAuthError(PersonalAuthProblem.Denied)
        }
        if (attempt.accountId != null && (status.accountId != attempt.accountId || status.githubId != attempt.githubId))
            throw PersonalAuthError(PersonalAuthProblem.Reconnect)
        if (status.sessionExpiresAt != attempt.expiresAt) invalidPersonal()
        return PersonalSession(session, status, origin.base)
    }

    suspend fun cancel(attempt: PersonalAuthAttempt): Boolean =
        request(PersonalPaths.Cancel, attempt.body(), attempt.source).obj().bool("cancelled")

    /** Retirement uses only the captured origin and attempt, even after the host advances its source. */
    internal suspend fun cancelRetired(attempt: PersonalAuthAttempt) {
        if (attempt.source != RpcSource(origin.base, attempt.source.generation))
            throw CancellationException("The personal sign-in belongs to another origin.")
        request(PersonalPaths.Cancel, attempt.body(), null)
    }

    suspend fun account(session: String, source: RpcSource = source()): PersonalAccountStatus =
        parseAccount(request(PersonalPaths.Session, source = source, session = session).obj())

    suspend fun signOut(session: String, source: RpcSource = source()) {
        if (!request(PersonalPaths.SignOut, JsonObject(emptyMap()), source, session).bool()) invalidPersonal()
    }

    suspend fun disconnect(session: String, source: RpcSource = source()) {
        if (!request(PersonalPaths.Disconnect, JsonObject(emptyMap()), source, session).bool()) invalidPersonal()
    }

    internal suspend fun signOutRetired(session: String) {
        if (!request(PersonalPaths.SignOut, JsonObject(emptyMap()), null, session).bool()) invalidPersonal()
    }

    private suspend fun request(path: PersonalPaths, body: JsonObject? = null, source: RpcSource?, session: String? = null): JsonElement {
        source?.let { requireCurrent(it) }
        session?.let(::validatePersonalSession)
        val request = RpcRequest(
            origin.base + path.path,
            buildMap { put("Accept", "application/json"); put("Cache-Control", "no-store"); session?.let { put("Authorization", "Bearer $it") } },
            body?.toString().orEmpty(), connectTimeoutMs = 10_000, readTimeoutMs = 15_000,
            timeoutMs = if (source == null) 8_000 else 20_000,
            method = if (body == null) RpcMethod.Get else RpcMethod.Post, responseLimit = 32 * 1024,
        )
        val response = try { withTimeout(request.timeoutMs) { transport.execute(request) } }
        catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            source?.let { requireCurrent(it) }
            throw PersonalAuthError(PersonalAuthProblem.Timeout)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            source?.let { requireCurrent(it) }
            throw PersonalAuthError(PersonalAuthProblem.Unreachable)
        }
        currentCoroutineContext().ensureActive()
        source?.let { requireCurrent(it) }
        if (response.status in 300..399) throw PersonalAuthError(PersonalAuthProblem.Redirect, response.status)
        if (response.body.length > request.responseLimit) invalidPersonal()
        val text = response.body.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val mime = response.contentType.substringBefore(';').trim().lowercase()
        if (mime in setOf("text/html", "application/xhtml+xml") || text.startsWith("<!doctype html", true) || text.startsWith("<html", true))
            throw PersonalAuthError(PersonalAuthProblem.WebPage, response.status)
        if (mime != "application/json" && !mime.endsWith("+json")) invalidPersonal()
        val value = runCatching { Json.parseToJsonElement(text) }.getOrElse { invalidPersonal() }
        if (response.status == 200) return value
        if (value !is JsonObject) invalidPersonal()
        val problem = value["error"] as? JsonObject
        val kind = (problem?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val category = when (response.status to kind) {
            400 to "invalidRequest" -> PersonalAuthProblem.InvalidRequest
            401 to "unauthorized" -> PersonalAuthProblem.Unauthorized
            401 to "reconnect" -> PersonalAuthProblem.Reconnect
            403 to "denied" -> PersonalAuthProblem.Denied
            410 to "expired" -> PersonalAuthProblem.Expired
            429 to "rateLimited" -> PersonalAuthProblem.RateLimited
            503 to "unavailable" -> PersonalAuthProblem.Unavailable
            else -> PersonalAuthProblem.HttpRejected
        }
        throw PersonalAuthError(category, response.status)
    }

    private suspend fun requireCurrent(source: RpcSource) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(source)) throw CancellationException("The personal account source changed.")
    }
}

internal expect fun personalAuthTransport(): RpcTransport

private enum class PersonalPaths(val path: String) {
    Capabilities("/api/personal/v1/capabilities"), Start("/api/personal/v1/auth/github/start"),
    RepositoryConsent("/api/personal/v1/auth/github/repositories/start"),
    Status("/api/personal/v1/auth/github/status"), Claim("/api/personal/v1/auth/github/claim"),
    Cancel("/api/personal/v1/auth/github/cancel"), Session("/api/personal/v1/session"),
    SignOut("/api/personal/v1/session/sign-out"), Disconnect("/api/personal/v1/github/disconnect"),
}

private fun PersonalAuthAttempt.body() = buildJsonObject { put("attemptId", id); put("nativeProof", proof.secret) }
private fun validatePersonalSession(value: String) {
    if (!value.startsWith("pully_") || !canonicalPersonalSecret(value.removePrefix("pully_")))
        throw PersonalAuthError(PersonalAuthProblem.Unauthorized)
}
private fun parseAccount(value: JsonObject): PersonalAccountStatus {
    val account = value.string("accountId").takeIf { it.length in 1..128 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_" } } ?: invalidPersonal()
    val login = value.string("login").takeIf { it.length in 1..39 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' } } ?: invalidPersonal()
    val authority = when (value.string("authority")) {
        "publicRead" -> PersonalAuthority.PublicRead
        "privateRepositories" -> PersonalAuthority.PrivateRepositories
        else -> invalidPersonal()
    }
    val access = value["accessExpiresAt"]?.takeUnless { it == JsonNull }?.let { it.long().takeIf { n -> n > 0 } ?: invalidPersonal() }
    return PersonalAccountStatus(account, value.positive("githubId"), login, authority, value.positive("sessionExpiresAt"), access, value.bool("refreshAvailable"))
}

/** GitHub CLI device sign-in sends the person here with a short code instead of an authorize URL. */
internal const val GITHUB_DEVICE_URL = "https://github.com/login/device"
private fun validUserCode(code: String) = code.length in 4..32 && code.all { it in 'A'..'Z' || it in '0'..'9' || it == '-' }

private fun validateAuthorizeUrl(url: String, origin: PersonalAuthOrigin, authority: PersonalAuthority) {
    val prefix = "https://github.com/login/oauth/authorize?"
    if (url.length > 4096 || !url.startsWith(prefix) || url.any { it.isWhitespace() || it.code !in 33..126 || it in "#\\" }) invalidPersonal()
    val query = mutableMapOf<String, String>()
    for (part in url.removePrefix(prefix).split('&')) {
        val pieces = part.split('=', limit = 2)
        if (pieces.size != 2 || query.put(pieces[0], decodeAuthQuery(pieces[1])) != null) invalidPersonal()
    }
    if (query.keys != setOf("client_id", "redirect_uri", "response_type", "state", "code_challenge", "code_challenge_method", "scope", "prompt") ||
        query["client_id"].isNullOrBlank() || query["client_id"]!!.length > 256 ||
        query["redirect_uri"] != origin.base + "/api/personal/v1/auth/github/callback" ||
        query["response_type"] != "code" || query["code_challenge_method"] != "S256" ||
        query["scope"] != (if (authority == PersonalAuthority.PublicRead) "offline_access" else "repo offline_access") || query["prompt"] != "select_account" ||
        !canonicalPersonalSecret(query["state"].orEmpty()) || !canonicalPersonalSecret(query["code_challenge"].orEmpty())) invalidPersonal()
}

private fun decodeAuthQuery(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char == '%') {
            if (index + 2 > value.length) invalidPersonal()
            val code = value.substring(index, index + 2).toIntOrNull(16) ?: invalidPersonal()
            if (code !in 33..126) invalidPersonal()
            append(code.toChar()); index += 2
        } else append(if (char == '+') ' ' else char)
    }
}

private fun invalidPersonal(): Nothing = throw PersonalAuthError(PersonalAuthProblem.InvalidResponse)
private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: invalidPersonal()
private fun JsonElement?.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidPersonal()
private fun JsonObject.string(key: String): String = get(key).string()
private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalidPersonal()
private fun JsonObject.bool(key: String): Boolean = get(key).bool()
private fun JsonElement?.long(): Long = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalidPersonal()
private fun JsonObject.positive(key: String): Long = get(key).long().takeIf { it > 0 } ?: invalidPersonal()
