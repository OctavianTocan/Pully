package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.core.parsePullKey
import com.octaviantocan.pulls.data.Triage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * What the phone remembers about your inbox, on the device only: which pull requests you swiped
 * away, and which tours you have opened or started. Both sit behind the same string store as the
 * list copy ([KeyValueStore]), so tests can run them against a map.
 */

/** A row's age in the fewest characters: `now`, `43m`, `6h`, `2d`, `3w`, `5mo`, `2y`. Empty when the time is unknown. */
fun compactAge(thenMs: Long?, nowMs: Long): String {
    if (thenMs == null) return ""
    val seconds = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    val day = 86_400L
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < day -> "${seconds / 3_600}h"
        seconds < 7 * day -> "${seconds / day}d"
        seconds < 30 * day -> "${seconds / (7 * day)}w"
        seconds < 365 * day -> "${seconds / (30 * day)}mo"
        else -> "${seconds / (365 * day)}y"
    }
}

/** A line count that fits the diff column: `640`, `1.2k`, `12k`. */
fun compactCount(n: Int): String = when {
    n < 1_000 -> "$n"
    n < 10_000 -> "${n / 1_000}.${n % 1_000 / 100}k"
    else -> "${n / 1_000}k"
}

/** One repository in Profile's list: how many of your pull requests it has right now, and whether the Inbox shows it. */
data class RepoChoice(val owner: String, val repo: String, val count: Int, val shown: Boolean) {
    val key: String get() = "$owner/$repo"
}

/**
 * Every repository your pull requests are in, and every one switched off even when it has none right now
 * (so it can be switched back on), by name. [query] keeps those whose `owner/repo` contains it.
 */
fun repoChoices(pulls: List<PullRow>, hidden: Set<String>, query: String = ""): List<RepoChoice> {
    val counts = pulls.groupingBy { "${it.owner}/${it.repo}" }.eachCount()
    val keys = counts.keys + hidden
    val needle = query.trim().lowercase()
    return keys
        .filter { needle.isEmpty() || it.lowercase().contains(needle) }
        .sortedBy { it.lowercase() }
        .mapNotNull { key ->
            val owner = key.substringBefore('/', "")
            val repo = key.substringAfter('/', "")
            if (owner.isEmpty() || repo.isEmpty()) null else RepoChoice(owner, repo, counts[key] ?: 0, key !in hidden)
        }
}

/** The pull requests the Inbox shows: all of them but those in a switched-off repository. */
fun List<PullRow>.inShownRepos(hidden: Set<String>): List<PullRow> =
    if (hidden.isEmpty()) this else filterNot { "${it.owner}/${it.repo}" in hidden }

/**
 * The pull requests archived from the inbox, by `owner/repo#number`, each with the head commit it had when
 * it was archived. Archiving hides one from Waiting on you and Authored (it stays under All) until its head
 * moves: new commits bring it back. Oldest entries fall off past [LIMIT].
 */
class ArchiveStore(private val storage: KeyValueStore?) {
    /** Key to the head commit at archive time (empty when unknown, which never brings it back), oldest first. */
    var entries: Map<String, String> by mutableStateOf(read())
        private set

    val keys: Set<String> get() = entries.keys

    fun isArchived(key: String): Boolean = key in entries

    fun archive(key: String, head: String = "") {
        entries = (entries - key + (key to head)).entries.toList().takeLast(LIMIT).associate { it.key to it.value }
        write()
    }

    fun restore(key: String) {
        entries = entries - key
        write()
    }

    /** The archived keys that still count as archived for [pulls]: those whose head commit has not moved. */
    fun active(pulls: List<PullRow>): Set<String> {
        val heads = pulls.associate { Triage.key(it) to it.headSha }
        return entries.filter { (key, head) -> head.isEmpty() || heads[key].isNullOrEmpty() || heads[key] == head }.keys
    }

    // One `key<TAB>head` per line; a line from before heads were kept is just the key.
    private fun read(): Map<String, String> =
        runCatching {
            storage?.get(STORAGE_KEY)?.split('\n')?.filter { it.isNotBlank() }?.associate { line ->
                val parts = line.split('\t', limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
        }.getOrNull() ?: emptyMap()

    private fun write() {
        runCatching { storage?.set(STORAGE_KEY, entries.entries.joinToString("\n") { (key, head) -> if (head.isEmpty()) key else "$key\t$head" }) }
    }

    companion object {
        const val STORAGE_KEY = "pulls-review:archived:v1"
        const val LIMIT = 500
    }
}

/**
 * One row of the Tours tab: a pull request whose tour you opened or asked for on this phone.
 * [stops] is 0 while the tour is still being written; [reached] is the furthest stop you scrolled
 * to (1-based), with that stop's title in [stopTitle]; [at] is the stop you were on when you left,
 * where the tour reopens (0: never opened, use [reached]).
 */
data class TourEntry(
    val owner: String,
    val repo: String,
    val number: Int,
    val prTitle: String,
    val stops: Int,
    val reached: Int,
    val stopTitle: String,
    val writing: Boolean,
    val updatedAt: Long,
    val at: Int = 0,
) {
    val key: String get() = "$owner/$repo#$number"
    val done: Boolean get() = !writing && stops > 0 && reached >= stops

    /** The stop to reopen on: the one you left, else the furthest you got. */
    val resumeAt: Int get() = if (at in 1..stops) at else reached

    /**
     * Whether it is still being written. Only an open review of the pull request updates the entry, so one
     * that was left mid-write must not claim to be writing for ever.
     */
    fun writingAt(now: Long): Boolean = writing && now - updatedAt < WRITING_STALE_MS

    /** [done], but a rewrite that has expired no longer keeps a finished tour from counting as done. */
    fun doneAt(now: Long): Boolean = !writingAt(now) && stops > 0 && reached >= stops

    companion object {
        const val WRITING_STALE_MS = 15 * 60_000L
    }
}

/** The tours listed under Tours, newest activity first. */
class TourIndex(private val storage: KeyValueStore?) {
    var entries: List<TourEntry> by mutableStateOf(read())
        private set

    fun get(key: String): TourEntry? = entries.firstOrNull { it.key == key }

    /** Adds or replaces the entry for the same pull request. */
    fun upsert(entry: TourEntry) {
        entries = (entries.filterNot { it.key == entry.key } + entry).sortedByDescending { it.updatedAt }.take(LIMIT)
        write()
    }

    fun remove(key: String) {
        entries = entries.filterNot { it.key == key }
        write()
    }

    /** Server tour tasks whose rows the reviewer removed, so a task the server still lists does not bring its row back. */
    private var dismissed: Set<String> = runCatching { storage?.get(STORAGE_DISMISSED)?.split(',')?.filter { it.isNotBlank() }?.toSet() }.getOrNull().orEmpty()

    /** Removes the row and remembers the tasks it came from. */
    fun dismiss(key: String, taskIds: Collection<String>) {
        dismissed = (dismissed + taskIds.filter { it.isNotBlank() }).toList().takeLast(DISMISSED_LIMIT).toSet()
        runCatching { storage?.set(STORAGE_DISMISSED, dismissed.joinToString(",")) }
        remove(key)
    }

    /**
     * Brings the list in line with the server's tours. A tour started while no page was open (an analysis chains one) gets
     * its row, a row stops saying it is being written when its task ends, and a tour written in the background gets its
     * stop count from [stopsOf] (asked with the server's key). A tour that ended with nothing to show leaves no empty row.
     */
    suspend fun reconcile(tasks: List<WorkTask>, now: Long, stopsOf: suspend (key: String) -> Int?) {
        for ((key, group) in tasks.filter { it.kind == "tour" && it.id !in dismissed }.groupBy { it.key }) {
            val ref = parsePullKey(key) ?: continue
            val entryKey = "${ref.owner}/${ref.repo}#${ref.number}"
            val existing = get(entryKey)
            val newest = group.maxBy { it.startedAt }
            val fresh = TourEntry(ref.owner, ref.repo, ref.number, newest.title?.takeIf { it.isNotBlank() } ?: "#${ref.number}", stops = 0, reached = 0, stopTitle = "", writing = false, updatedAt = now)
            when {
                group.any { it.status == "running" } -> {
                    // Only an open page refreshes a row, so a long write is kept from expiring here.
                    if (existing == null) upsert(fresh.copy(writing = true))
                    else if (!existing.writing || now - existing.updatedAt > TourEntry.WRITING_STALE_MS / 3) upsert(existing.copy(writing = true, updatedAt = now))
                }
                newest.status == "done" && (existing == null || existing.writing) -> {
                    val stops = stopsOf(key) ?: existing?.stops ?: 0
                    if (stops > 0) upsert((existing ?: fresh).copy(writing = false, stops = stops, updatedAt = now))
                    else if (existing != null) remove(entryKey)
                }
                existing?.writing == true -> if (existing.stops > 0) upsert(existing.copy(writing = false)) else remove(entryKey)
            }
        }
    }

    private fun read(): List<TourEntry> = runCatching {
        val raw = storage?.get(STORAGE_KEY) ?: return emptyList()
        (json.parseToJsonElement(raw) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toEntry() }
    }.getOrDefault(emptyList())

    private fun write() {
        runCatching { storage?.set(STORAGE_KEY, JsonArray(entries.map { it.toJson() }).toString()) }
    }

    companion object {
        const val STORAGE_KEY = "pulls-review:tours:v1"
        const val STORAGE_DISMISSED = "pulls-review:tours:dismissed:v1"
        const val LIMIT = 60
        const val DISMISSED_LIMIT = 100
        private val json = Json { ignoreUnknownKeys = true }
    }
}

private fun TourEntry.toJson(): JsonObject = buildJsonObject {
    put("owner", owner); put("repo", repo); put("number", number); put("prTitle", prTitle)
    put("stops", stops); put("reached", reached); put("stopTitle", stopTitle); put("writing", writing); put("updatedAt", updatedAt); put("at", at)
}

private fun JsonObject.toEntry(): TourEntry? {
    fun text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    return TourEntry(
        owner = text("owner")?.takeIf { it.isNotBlank() } ?: return null,
        repo = text("repo")?.takeIf { it.isNotBlank() } ?: return null,
        number = int("number") ?: return null,
        prTitle = text("prTitle").orEmpty(),
        stops = int("stops") ?: 0,
        reached = int("reached") ?: 0,
        stopTitle = text("stopTitle").orEmpty(),
        writing = (this["writing"] as? JsonPrimitive)?.booleanOrNull == true,
        updatedAt = (this["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
        at = int("at") ?: 0,
    )
}
