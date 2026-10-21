package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException

/** Actual account discovery; no account capability is inferred from a failed request. */
data class AccountConnection(val status: AccountStatus?, val sessionExpired: Boolean = false)

/**
 * Older servers can answer repo-info without implementing accounts. An independent successful
 * Pully response proves connectivity, but does not fabricate hasAccount=false or a Google client.
 */
suspend fun checkAccountConnection(repo: Repo, storedEmail: String?): AccountConnection {
    val source = repo.source()
    fun requireCurrent() { if (!repo.isCurrent(source)) throw CancellationException("The server or account changed.") }
    val status = try {
        repo.accountStatus()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        requireCurrent()
        try {
            repo.repoInfo()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw failure
        }
        requireCurrent()
        return AccountConnection(null)
    }
    requireCurrent()
    if (!status.hasAccount || storedEmail == null) return AccountConnection(status)
    val email = try {
        repo.accountEmail()
    } catch (failure: ServerError) {
        if (failure.kind != ServerErrorKind.Unauthorized) throw failure
        null
    }
    requireCurrent()
    return AccountConnection(status, email == null || !email.equals(storedEmail, ignoreCase = true))
}

/** A small recovery category for presentation; it cannot retain a response, credential or address. */
enum class ConnectionProblem {
    Configuration, Access, WebPage, SignIn, Forbidden, Redirect, Unavailable, InvalidResponse, Timeout, Unreachable,
}

fun connectionProblem(failure: Throwable): ConnectionProblem = when (failure) {
    is RpcTimeoutException -> ConnectionProblem.Timeout
    is ServerError -> when {
        failure.status in 500..599 || failure.status == 429 -> ConnectionProblem.Unavailable
        failure.kind == ServerErrorKind.AccessRequired -> ConnectionProblem.Access
        failure.kind == ServerErrorKind.HtmlResponse -> ConnectionProblem.WebPage
        failure.kind == ServerErrorKind.Unauthorized -> ConnectionProblem.SignIn
        failure.kind == ServerErrorKind.Forbidden -> ConnectionProblem.Forbidden
        failure.kind == ServerErrorKind.Redirect -> ConnectionProblem.Redirect
        failure.kind == ServerErrorKind.Http && failure.status in setOf(401, 403) -> ConnectionProblem.Access
        else -> ConnectionProblem.InvalidResponse
    }
    else -> ConnectionProblem.Unreachable
}
