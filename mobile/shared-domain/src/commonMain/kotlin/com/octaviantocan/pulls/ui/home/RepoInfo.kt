package com.octaviantocan.pulls.ui.home

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** `RepoInfo` from `local/pages.ts`. */
data class RepoInfo(
    val currentBranch: String?,
    val defaultBranch: Pair<String, String>?,
    val branches: List<String>,
    val tags: List<String>,
    val commits: List<Pair<String, String>>,
)

/** Reads the server's `repo-info` answer. The Server page calls it to find out whether the server answers. */
fun JsonObject.asRepoInfo(): RepoInfo {
    fun s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun list(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val def = this["defaultBranch"] as? JsonObject
    return RepoInfo(
        currentBranch = s("currentBranch"),
        defaultBranch = def?.let { ((it["name"] as? JsonPrimitive)?.contentOrNull ?: "") to ((it["ref"] as? JsonPrimitive)?.contentOrNull ?: "") },
        branches = list("branches"),
        tags = list("tags"),
        commits = (this["commits"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let { ((it["sha"] as? JsonPrimitive)?.contentOrNull ?: "") to ((it["subject"] as? JsonPrimitive)?.contentOrNull ?: "") }
        },
    )
}
