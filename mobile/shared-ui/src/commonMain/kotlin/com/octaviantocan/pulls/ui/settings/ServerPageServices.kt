package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ConnectionProblem
import com.octaviantocan.pulls.data.checkAccountConnection
import com.octaviantocan.pulls.data.connectionProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Private unsaved input only. Native storage owns the actual Access credential values. */
class ServerPageConfiguration(val url: String, val accessId: String, val accessSecret: String) {
    override fun toString() = "ServerPageConfiguration(credentials=redacted)"
}

interface ServerPageServices {
    val configuration: ServerPageConfiguration
    /** Checks the opening owner before saving, then returns a probe bound to the saved connection. */
    fun save(configuration: ServerPageConfiguration): ServerConnectionCheck
}

sealed interface ServerConnectionOutcome {
    data object Connected : ServerConnectionOutcome
    data class Failed(val problem: ConnectionProblem) : ServerConnectionOutcome
    data object Superseded : ServerConnectionOutcome
}

fun interface ServerConnectionCheck { suspend fun verify(): ServerConnectionOutcome }

/** An explicit Save can check its new connection; it cannot follow a later replacement's credentials. */
fun serverConnectionCheck(repo: Repo): ServerConnectionCheck {
    // Invalid saved URLs retain Android's actionable "Saved, but ..." probe result.
    val opening = runCatching { repo.source() }
    return ServerConnectionCheck {
        val source = opening.getOrThrow()
        if (!repo.isCurrent(source)) throw CancellationException("The server changed.")
        try {
            checkAccountConnection(repo, null)
            currentCoroutineContext().ensureActive()
            if (!repo.isCurrent(source)) throw CancellationException("The server changed.")
            ServerConnectionOutcome.Connected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!repo.isCurrent(source)) throw CancellationException("The server changed.")
            ServerConnectionOutcome.Failed(connectionProblem(failure))
        }
    }
}
