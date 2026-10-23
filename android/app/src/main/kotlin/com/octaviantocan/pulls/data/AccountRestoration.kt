package com.octaviantocan.pulls.data

internal data class RestoredAccountConnection(
    val email: String?,
    val hasAccount: Boolean?,
    val googleClientId: String?,
    val verified: Boolean,
)

internal fun restoreAccountConnection(
    configured: String,
    hasSession: Boolean,
    sessionServer: String?,
    storedEmail: String?,
    statusServer: String?,
    hasAccount: Boolean?,
    googleClientId: String?,
    verifiedServer: String?,
): RestoredAccountConnection {
    val email = storedEmail?.takeIf { hasSession && sameAccountServer(sessionServer, configured) }
    // Older versions did not record which server supplied these capabilities. Rediscover
    // instead of assigning that unmarked data to whichever server is configured now.
    val statusMatches = sameAccountServer(statusServer, configured)
    val status = hasAccount.takeIf { statusMatches }
    return RestoredAccountConnection(
        email,
        status,
        googleClientId.takeIf { statusMatches },
        sameAccountServer(verifiedServer, configured) || status != null || email != null,
    )
}

internal fun sameAccountServer(saved: String?, configured: String): Boolean = saved != null && runCatching {
    ServerEndpoint.parse(saved).base == ServerEndpoint.parse(configured).base
}.getOrDefault(false)
