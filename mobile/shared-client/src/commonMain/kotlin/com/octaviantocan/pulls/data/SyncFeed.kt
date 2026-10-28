package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the server said changed since its last answer to [followChanges]. */
sealed interface SyncChange {
    /** True when a screen showing [topic] should read it again. */
    fun touches(topic: String): Boolean

    /** The server restarted, or the phone fell too far behind to be told: anything may have changed. */
    data object Everything : SyncChange {
        override fun touches(topic: String) = true
    }

    data class Topics(val topics: Set<String>) : SyncChange {
        override fun touches(topic: String) = topic in topics
    }
}

/** The topics of `backend/src/sync.rs`: what a write on the server changes for a screen. */
object SyncTopics {
    /** The Inbox list and Jev's kinds on it. */
    const val INBOX = "inbox"

    /** Files marked reviewed, on any pull request. */
    const val MARKS = "marks"

    /** One pull request's stored diff, saved AI work and Ask threads. */
    fun review(key: String) = "review:$key"
}

/** One `sync-wait` answer: the server's feed position, what changed, and whether to read everything. */
class SyncAnswer(val epoch: String, val version: Long, val topics: Set<String>, val reset: Boolean)

/** How long one `sync-wait` may be held by the server. */
const val SYNC_WAIT_MS = 25_000L

/** Waits between failed `sync-wait`s; following stops after the last until the screen starts it again. */
val SYNC_RETRY_MS = listOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L)

/** The server's change feed, waited on for at most [waitMs]; `inbox` says the Inbox is on screen. */
suspend fun Repo.syncWait(epoch: String, version: Long, waitMs: Long, inbox: Boolean): SyncAnswer {
    val o = call("sync-wait") {
        put("epoch", epoch); put("version", version); put("waitMs", waitMs)
        if (inbox) put("inbox", true)
    } as JsonObject
    return SyncAnswer(
        o["epoch"]?.jsonPrimitive?.content.orEmpty(),
        o["version"]?.jsonPrimitive?.longOrNull ?: 0,
        o.strings("topics").toSet(),
        o["reset"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

/**
 * Follows the server's change feed for as long as the caller runs, calling [changed] when the server writes something a
 * screen shows: the phone is told instead of asking again and again, as Zero's clients are poked by its server, and then
 * reads only what changed through the methods it already calls. The first answer only places the phone in the feed.
 * A server without the feed (an older one answers 404) ends it at once; [retryMs] waits run out after repeated failures,
 * so a screen falls back to what it did before and starts following again when it next comes to the front. With
 * [inbox] the server also refreshes the Inbox from GitHub while it waits, when its copy is a minute old.
 */
suspend fun Repo.followChanges(
    inbox: Boolean = false,
    waitMs: Long = SYNC_WAIT_MS,
    retryMs: List<Long> = SYNC_RETRY_MS,
    changed: suspend (SyncChange) -> Unit,
) {
    val source = runCatching { source() }.getOrNull() ?: return
    var epoch = ""
    var version = 0L
    var failures = 0
    while (isCurrent(source)) {
        val answer = try {
            syncWait(epoch, version, if (epoch.isEmpty()) 0 else waitMs, inbox)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (failure is ServerError && failure.status == 404) return
            if (failures >= retryMs.size) return
            delay(retryMs[failures++])
            continue
        }
        failures = 0
        if (!isCurrent(source)) return
        val placed = epoch.isNotEmpty()
        epoch = answer.epoch
        version = answer.version
        when {
            answer.reset && placed -> changed(SyncChange.Everything)
            answer.topics.isNotEmpty() && placed -> changed(SyncChange.Topics(answer.topics))
        }
    }
}
