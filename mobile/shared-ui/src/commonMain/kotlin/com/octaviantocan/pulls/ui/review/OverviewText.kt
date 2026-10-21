package com.octaviantocan.pulls.ui.review

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A `pr-overview` answer with the title and description of a `pr-text` answer put in, and the merge commit text the
 * server prefilled from the old ones rewritten to match; null when either answer is missing its part. Which of two
 * answers is newer is the caller's to know, by when each was asked for: GitHub's `updatedAt` counts whole seconds, so
 * an edit can share it with the copy it replaced.
 */
fun withText(answer: JsonObject?, text: JsonObject?): JsonObject? {
    val overview = answer?.get("overview") as? JsonObject ?: return null
    if (text == null || listOf("title", "body", "bodyHTML").none { text[it] is JsonPrimitive }) return null
    val oldTitle = overview.textOrNull("title").orEmpty()
    val oldBody = overview.textOrNull("body").orEmpty()
    val newTitle = text.textOrNull("title") ?: oldTitle
    val newBody = (text["body"] as? JsonPrimitive)?.contentOrNull ?: oldBody
    val patched = buildMap {
        putAll(overview)
        for (field in listOf("title", "body", "bodyHTML", "updatedAt")) (text[field] as? JsonPrimitive)?.let { put(field, it) }
    }
    // `defaultMergeCommit` on the server: a title is the PR's with ` (#n)`, a message the PR's body or its title.
    val number = overview["number"]?.let { (it as? JsonPrimitive)?.intOrNull }
    val defaults = (answer["defaultCommits"] as? JsonObject)?.mapValues { (_, value) ->
        val commit = value as? JsonObject ?: return@mapValues value
        val title = commit.textOrNull("title").orEmpty()
        val body = (commit["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        JsonObject(commit + mapOf(
            "title" to JsonPrimitive(if (oldTitle.isNotEmpty() && title == "$oldTitle (#$number)") "$newTitle (#$number)" else title),
            "body" to JsonPrimitive(when { body.isNotEmpty() && body == oldBody -> newBody; oldTitle.isNotEmpty() && body == oldTitle -> newTitle; else -> body }),
        ))
    }
    return JsonObject(answer + ("overview" to JsonObject(patched)) + (defaults?.let { mapOf("defaultCommits" to JsonObject(it)) } ?: emptyMap()))
}

private fun JsonObject.textOrNull(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
