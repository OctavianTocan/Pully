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
            // A Cloudflare sign-in's token changes with every sign-in; the person it names is what keeps the copies apart.
            val stable = if (name.equals(ACCESS_TOKEN_HEADER, ignoreCase = true)) "identity:${accessTokenIdentity(value) ?: value}" else value
            JsonArray(listOf(JsonPrimitive(name.lowercase()), JsonPrimitive(stable)))
        }))
    }
    return sha256Utf8(identity.toString())
}

/** The header that carries a Cloudflare Access sign-in's token (`CF_Authorization`), as `cloudflared access curl` sends it. */
const val ACCESS_TOKEN_HEADER = "cf-access-token"

/** Who a Cloudflare Access token names (its `email`, else `sub`), read without checking its signature; null when unreadable. */
fun accessTokenIdentity(token: String): String? = runCatching {
    val payload = token.split('.').getOrNull(1) ?: return null
    val json = kotlinx.serialization.json.Json.parseToJsonElement(
        kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString(),
    ) as? kotlinx.serialization.json.JsonObject ?: return null
    val email = (json["email"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val subject = (json["sub"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    (email ?: subject)?.takeIf { it.isNotBlank() }
}.getOrNull()

