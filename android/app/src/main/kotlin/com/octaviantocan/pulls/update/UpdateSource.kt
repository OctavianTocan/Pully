package com.octaviantocan.pulls.update

import com.octaviantocan.pulls.data.ServerConfig

/** Resolve transport without a mounted workspace, personal bearer or writes to compatibility preferences. */
internal fun resolveUpdateConfig(
    existingServer: Boolean,
    saved: () -> ServerConfig,
    builtInServer: String,
    updateChannel: String,
    bakedHeaders: (String, String) -> Map<String, String>,
): ServerConfig? {
    if (existingServer) return saved()
    if (updateChannel != "staging/" || bakedHeaders(builtInServer, "/download/staging/latest.json").isEmpty()) return null
    // Baked credentials are added only to approved request URLs, never stored as compatibility credentials.
    return ServerConfig(url = builtInServer)
}
