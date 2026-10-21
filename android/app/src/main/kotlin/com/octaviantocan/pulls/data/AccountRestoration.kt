package com.octaviantocan.pulls.data

/** Source evidence from durable preferences, without retaining any session credential. */
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
    fun matches(saved: String?): Boolean = saved != null && runCatching {
        ServerEndpoint.parse(saved).base == ServerEndpoint.parse(configured).base
    }.getOrDefault(false)
    val email = storedEmail?.takeIf { hasSession && matches(sessionServer) }
    // Older versions did not record which server supplied these capabilities. Rediscover
    // instead of assigning that unmarked data to whichever server is configured now.
    val status = hasAccount.takeIf { matches(statusServer) }
    return RestoredAccountConnection(
        email,
        status,
        googleClientId.takeIf { matches(statusServer) },
        matches(verifiedServer) || status != null || email != null,
    )
}
