package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.core.parsePullKey
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

/**
 * The pull requests archived from the inbox, by `owner/repo#number`, each with the head commit it had when
 * it was archived. Archiving hides one from Waiting on you and Authored (it stays under All) until its head
 * moves: new commits bring it back. Oldest entries fall off past [LIMIT].
 */
class ArchiveStore(private val storage: KeyValueStore?) {
    private var invalidated = false
    private fun active(): Boolean = !invalidated && storage?.current != false
    fun invalidate() { invalidated = true; entries = emptyMap() }

    /** Key to the head commit at archive time (empty when unknown, which never brings it back), oldest first. */
    var entries: Map<String, String> by mutableStateOf(read())
        private set

    val keys: Set<String> get() = if (active()) entries.keys else emptySet()

    fun isArchived(key: String): Boolean = active() && key in entries

    fun archive(key: String, head: String = "") {
        if (!active()) return
        entries = (entries - key + (key to head)).entries.toList().takeLast(LIMIT).associate { it.key to it.value }
        write()
    }

    fun restore(key: String) {
        if (!active()) return
        entries = entries - key
        write()
    }

    /** The archived keys that still count as archived for [pulls]: those whose head commit has not moved. */
    fun active(pulls: List<PullRow>): Set<String> {
        if (!active()) return emptySet()
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

/** The tours listed under Tours, newest activity first. */
class TourIndex(private val storage: KeyValueStore?) {
    private var invalidated = false
    private var generation = 0L
    private fun active(): Boolean = !invalidated && storage?.current != false
    fun invalidate() { invalidated = true; generation++; entries = emptyList(); dismissed = emptySet() }

    var entries: List<TourEntry> by mutableStateOf(read())
        private set

    fun get(key: String): TourEntry? = if (active()) entries.firstOrNull { it.key == key } else null

    /** Adds or replaces the entry for the same pull request. */
    fun upsert(entry: TourEntry) {
        if (!active()) return
        generation++
        entries = (entries.filterNot { it.key == entry.key } + entry).sortedByDescending { it.updatedAt }.take(LIMIT)
        write()
    }

    fun remove(key: String) {
        if (!active()) return
        generation++
        entries = entries.filterNot { it.key == key }
        write()
    }

    /** Server tour tasks whose rows the reviewer removed, so a task the server still lists does not bring its row back. */
    private var dismissed: Set<String> = runCatching { storage?.get(STORAGE_DISMISSED)?.split(',')?.filter { it.isNotBlank() }?.toSet() }.getOrNull().orEmpty()

    /** Removes the row and remembers the tasks it came from. */
    fun dismiss(key: String, taskIds: Collection<String>) {
        if (!active()) return
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
        if (!active()) return
        for ((key, group) in tasks.filter { it.kind == "tour" && it.id !in dismissed }.groupBy { it.key }) {
            if (!active()) return
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
                    val revision = generation
                    val stops = stopsOf(key) ?: existing?.stops ?: 0
                    if (!active()) return
                    if (revision != generation || group.any { it.id in dismissed }) continue
                    if (stops > 0) upsert((existing ?: fresh).copy(writing = false, stops = stops, updatedAt = now))
                    else if (existing != null) remove(entryKey)
                }
                existing?.writing == true && newest.status in setOf("failed", "cancelled") -> if (existing.stops > 0) upsert(existing.copy(writing = false)) else remove(entryKey)
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

/**
 * How the Inbox is laid out, kept on this phone: the sort, the grouping, and the headings whose rows are folded away
 * (repositories by `owner/repo`, kinds by `kind:<kind>`). A folded repository stays folded in Inbox and Tours alike,
 * until a tap on its heading opens it again. A sort or grouping that needs Jev is kept while Jev is off and comes back
 * with it; meanwhile [sortWhen] and [groupByWhen] give the default in its place.
 */
class InboxLayout(private val storage: KeyValueStore?, private val effects: HomeEffects = NoHomeEffects) {
    private var invalidated = false
    private fun active(): Boolean = !invalidated && storage?.current != false
    fun invalidate() { invalidated = true; sort = InboxSort.Updated; groupBy = InboxGrouping.Repo; folded = emptySet() }

    var sort: InboxSort by mutableStateOf(InboxSort.of(runCatching { storage?.get(SORT_KEY) }.getOrNull()))
        private set

    var groupBy: InboxGrouping by mutableStateOf(InboxGrouping.of(runCatching { storage?.get(GROUP_KEY) }.getOrNull()))
        private set

    /** The key of every folded heading. */
    var folded: Set<String> by mutableStateOf(readFolded())
        private set

    fun updateSort(value: InboxSort) {
        if (!active()) return
        if (value != sort) effects.action("sort", value.id)
        sort = value
        runCatching { storage?.set(SORT_KEY, value.id) }
    }

    fun updateGroupBy(value: InboxGrouping) {
        if (!active()) return
        if (value != groupBy) effects.action("group", value.id)
        groupBy = value
        runCatching { storage?.set(GROUP_KEY, value.id) }
    }

    /** The sort in force: Riskiest first is Recently updated while Jev is off. */
    fun sortWhen(jev: Boolean): InboxSort = if (sort.needsJev && !jev) InboxSort.Updated else sort

    /** The grouping in force: Kind is Repository while Jev is off. */
    fun groupByWhen(jev: Boolean): InboxGrouping = if (groupBy.needsJev && !jev) InboxGrouping.Repo else groupBy

    fun isFolded(key: String): Boolean = key in folded

    fun toggle(key: String) {
        if (!active()) return
        // Which kind of heading, never its name: a repository's or a kind's.
        effects.action(if (key in folded) "unfold" else "fold", if (key.startsWith("kind:")) "kind" else "repo")
        folded = if (key in folded) folded - key else folded + key
        runCatching { storage?.set(FOLDED_KEY, folded.joinToString("\n")) }
    }

    private fun readFolded(): Set<String> =
        runCatching { storage?.get(FOLDED_KEY)?.split('\n')?.filter { it.isNotBlank() }?.toSet() }.getOrNull().orEmpty()

    companion object {
        const val SORT_KEY = "pulls-review:inbox-sort:v1"
        const val GROUP_KEY = "pulls-review:inbox-group:v1"
        const val FOLDED_KEY = "pulls-review:inbox-folded:v1"
    }
}
