package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The existing sign-in RPCs, owned by the source that opened the sign-in page. */
class SignInController(
    private val repo: Repo,
    private val source: RpcSource,
    private val isCurrent: () -> Boolean,
    private val accept: (session: String, email: String) -> Unit,
) {
    suspend fun password(email: String, password: String) {
        requireCurrent()
        finish(repo.signIn(email, password))
    }

    /** A dismissed native account sheet returns null and leaves the page signed out. */
    suspend fun google(idToken: suspend () -> String?): Boolean {
        requireCurrent()
        val token = idToken() ?: return false
        // Native account selection can outlive the route or a server/Access/account change.
        requireCurrent()
        finish(repo.signInWithGoogle(token))
        return true
    }

    private suspend fun finish(answer: Pair<String, String>) {
        requireCurrent()
        // The host commits synchronously; that successful commit may itself advance the source.
        accept(answer.first, answer.second)
    }

    private suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent() || !repo.isCurrent(source))
            throw CancellationException("The sign-in source changed.")
    }
}
