package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.ConnectionProblem
import com.octaviantocan.pulls.data.Account

/** Replaced checks are neither a success claim nor a connection failure. */
internal sealed interface ConnectionCheckResult {
    data object Connected : ConnectionCheckResult
    data class Failed(val problem: ConnectionProblem) : ConnectionCheckResult
    data object Superseded : ConnectionCheckResult
}

internal fun connectionCheckResult(revision: Long, problem: ConnectionProblem? = null): ConnectionCheckResult =
    if (!Account.ownsConnectionCheck(revision)) ConnectionCheckResult.Superseded
    else problem?.let { ConnectionCheckResult.Failed(it) } ?: ConnectionCheckResult.Connected

internal val ConnectionCheckResult.messageKey: String?
    get() = when (this) {
        ConnectionCheckResult.Connected -> "connection.saved"
        is ConnectionCheckResult.Failed -> "connection.problem.${problem.name}"
        ConnectionCheckResult.Superseded -> null
    }
