package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/**
 * The pull requests archived from the inbox, by `owner/repo#number`. Archiving hides one from
 * Waiting on you and Authored; it stays under All. Oldest entries fall off past [LIMIT].
 */
class ArchiveStore(private val storage: KeyValueStore?) {
    var keys: Set<String> by mutableStateOf(read())
        private set

    fun isArchived(key: String): Boolean = key in keys

    fun archive(key: String) {
        keys = (keys - key + key).toList().takeLast(LIMIT).toSet()
        write()
    }

    fun restore(key: String) {
        keys = keys - key
        write()
    }

    private fun read(): Set<String> =
        runCatching { storage?.get(STORAGE_KEY)?.split('\n')?.filter { it.isNotBlank() }?.toCollection(LinkedHashSet()) }.getOrNull() ?: emptySet()

    private fun write() {
        runCatching { storage?.set(STORAGE_KEY, keys.joinToString("\n")) }
    }

    companion object {
        const val STORAGE_KEY = "pulls-review:archived:v1"
        const val LIMIT = 500
    }
}

/**
 * One row of the Tours tab: a pull request whose tour you opened or asked for on this phone.
 * [stops] is 0 while the tour is still being written; [reached] is the furthest stop you scrolled
 * to (1-based), with that stop's title in [stopTitle].
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
) {
    val key: String get() = "$owner/$repo#$number"
    val done: Boolean get() = !writing && stops > 0 && reached >= stops

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

    private fun read(): List<TourEntry> = runCatching {
        val raw = storage?.get(STORAGE_KEY) ?: return emptyList()
        (json.parseToJsonElement(raw) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toEntry() }
    }.getOrDefault(emptyList())

    private fun write() {
        runCatching { storage?.set(STORAGE_KEY, JsonArray(entries.map { it.toJson() }).toString()) }
    }

    companion object {
        const val STORAGE_KEY = "pulls-review:tours:v1"
        const val LIMIT = 60
        private val json = Json { ignoreUnknownKeys = true }
    }
}

private fun TourEntry.toJson(): JsonObject = buildJsonObject {
    put("owner", owner); put("repo", repo); put("number", number); put("prTitle", prTitle)
    put("stops", stops); put("reached", reached); put("stopTitle", stopTitle); put("writing", writing); put("updatedAt", updatedAt)
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
    )
}
