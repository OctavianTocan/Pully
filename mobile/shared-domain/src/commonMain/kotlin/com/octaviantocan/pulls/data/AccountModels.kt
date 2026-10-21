package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** An API key of the account, as Settings, Agent keys lists it: never the key itself, which is shown once. */
data class AgentKey(val id: String, val name: String, val hint: String, val createdAt: String, val lastUsedAt: String?)

/** A diff an agent posted to the account (plan 13), as the Inbox lists it. */
data class PostedRow(
    val id: String,
    val title: String,
    /** Who posted it: the name the agent gave, or its key's. */
    val author: String,
    val version: Int,
    val updatedAt: String,
    val files: Int,
    val additions: Int,
    val deletions: Int,
)

/** What the server says about its account: whether it has one, and the client ID for Google sign-in, null when off. */
data class AccountStatus(val hasAccount: Boolean, val googleClientId: String? = null)

/** One setting as the account keeps it: its value and when it was changed, so the newer of two phones wins. */
data class SyncedSetting(val value: JsonElement, val at: Long)

fun JsonObject.asAgentKey() = AgentKey(s("id"), s("name"), s("hint"), s("createdAt"), sOrNull("lastUsedAt"))

fun JsonObject.asPostedRow() = PostedRow(s("id"), s("title"), s("author"), i("version") ?: 1, s("updatedAt"), i("files") ?: 0, i("additions") ?: 0, i("deletions") ?: 0)

fun JsonObject.asSyncedSettings(): Map<String, SyncedSetting> =
    (obj("values") ?: JsonObject(emptyMap())).mapNotNull { (name, raw) ->
        val o = raw as? JsonObject ?: return@mapNotNull null
        val value = o["value"] ?: return@mapNotNull null
        val at = (o["at"]?.jsonPrimitive?.longOrNull) ?: return@mapNotNull null
        name to SyncedSetting(value, at)
    }.toMap()
