package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.core.sha256Utf8
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stable private cache identity. Raw authorization material is never returned, persisted or logged. */
fun sourceStorageNamespace(
    server: String,
    email: String?,
    eligibleSession: Boolean,
    accessHeaders: Map<String, String> = emptyMap(),
): String? {
    val endpoint = runCatching { ServerEndpoint.parse(server).base }.getOrNull() ?: return null
    val owner = email?.takeIf { eligibleSession } ?: "signed-out"
    val access = accessHeaders.entries.filterNot { it.key.equals("X-Pully-Session", ignoreCase = true) }
        .sortedWith(compareBy<Map.Entry<String, String>> { it.key.lowercase() }.thenBy { it.value })
    val identity = buildJsonObject {
        put("server", endpoint)
        put("owner", owner)
        put("access", JsonArray(access.map { (name, value) ->
            JsonArray(listOf(JsonPrimitive(name.lowercase()), JsonPrimitive(value)))
        }))
    }
    return sha256Utf8(identity.toString())
}
