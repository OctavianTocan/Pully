package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.core.OpenTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A pull request's whole page as the server sent its parts (the diff, the overview, the reviews and the stack), kept on
 * the phone so the page opens with nothing to wait for (`pages-are-read-before-the-tap`). [version] is the Inbox row's
 * head and `updatedAt` it was read for, so a page is read again only when the pull request changed.
 */
class PullPage(val json: JsonObject) {
    val version: String? get() = (json["version"] as? JsonPrimitive)?.contentOrNull

    fun opened(): OpenedReview? = json["opened"]?.let { parsed { it.asOpened() } }

    fun overview(): JsonObject? = json["overview"] as? JsonObject

    fun reviews(): ReviewData? = (json["reviews"] as? JsonObject)?.let { parsed { it.asReviewData() } }

    /** Null when the stack was not read; otherwise its [KeptStack.stack] is null when the pull request is in none. */
    fun stack(): KeptStack? = (json["stack"] as? JsonObject)?.let { answer -> parsed { KeptStack(answer["stack"]?.asPrStack()) } }

    private inline fun <T> parsed(read: () -> T): T? = try { read() } catch (_: Exception) { null }

    companion object {
        /** The version an Inbox row's pull request is at: its head and when GitHub last changed it. */
        fun version(headSha: String?, updatedAt: String?): String? =
            if (headSha.isNullOrEmpty() || updatedAt.isNullOrEmpty()) null else "$headSha@$updatedAt"
    }
}

/** A stack that was read, which may be none. */
data class KeptStack(val stack: PrStack?)

/**
 * Reads every part of [target]'s page at once: the overview, reviews and stack the server keeps where it has them, and
 * GitHub's answer where it has not. The diff must arrive; another part that fails is left out and read when the page
 * opens.
 */
suspend fun Repo.pullPage(target: OpenTarget.Pull, version: String?): PullPage = coroutineScope {
    val pull = buildJsonObject { put("owner", target.owner); put("repo", target.repo); put("number", target.number) }
    fun with(extra: Map<String, JsonElement>) = JsonObject(pull + extra)
    val cached = mapOf("cached" to JsonPrimitive(true))
    suspend fun keptOrLive(name: String): JsonElement? = try {
        call(name, with(cached)).takeIf { it is JsonObject } ?: call(name, pull)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    val overview = async { keptOrLive("pr-overview") }
    val reviews = async { keptOrLive("review-data") }
    val stack = async { keptOrLive("pr-stack") }
    val opened = call("github-pr", with(mapOf("lite" to JsonPrimitive(true))))
    PullPage(buildJsonObject {
        put("opened", opened)
        version?.let { put("version", it) }
        overview.await()?.let { put("overview", it) }
        reviews.await()?.let { put("reviews", it) }
        stack.await()?.takeIf { it != JsonNull }?.let { put("stack", it) }
    })
}
