package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.PullRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A string store (SharedPreferences on the phone, a map in tests). */
interface KeyValueStore {
    /** False once the native server/account lease is invalidated. */
    val current: Boolean get() = true
    fun get(key: String): String?
    fun set(key: String, value: String)
}

class MyPullsSync(
    private val cached: suspend (state: String) -> PullsSnapshot?,
    private val fresh: suspend (state: String) -> List<PullRow>,
    private val storage: KeyValueStore? = null,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** A server copy younger than this is shown without asking GitHub again. */
    private val freshForMs: Long = 30_000,
    /**
     * The server's copy, without the server refreshing it from GitHub however old it is; for [followJev]. With `classify`
     * the server has Jev sort the rows it has not sorted yet.
     */
    private val peek: suspend (state: String, classify: Boolean) -> PullsSnapshot? = { state, _ -> cached(state) },
    private val effects: HomeEffects = NoHomeEffects,
    private val currentSource: () -> Boolean = { true },
) {
    /** The newest list known, null until any copy arrives. */
    var pulls by mutableStateOf<List<PullRow>?>(null)
        private set

    /** When that list was fetched from GitHub. */
    var fetchedAt by mutableStateOf<Long?>(null)
        private set

    var updating by mutableStateOf(false)
        private set

    /** Why the last update failed; the previous list stays in [pulls]. */
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * The server sorts pull requests with Jev, so the Inbox may group them by kind and sort them by risk. Only
     * `my-pulls-cached` says so: a snapshot without `classifier` turns it off, a plain `my-pulls` list leaves it as it was.
     */
    var jev by mutableStateOf(false)
        private set

    private var state = "open"
    private var generation = 0
    private var invalidated = false
    private fun active(): Boolean = !invalidated && currentSource()

    /** A new server/account owner clears this route's private list and rejects late answers. */
    fun invalidate() {
        invalidated = true
        generation++
        pulls = null
        fetchedAt = null
        error = null
        updating = false
        jev = false
        wantsJev = false
    }

    /** Shows the device copy for [state] at once, then loads. */
    suspend fun select(state: String) {
        if (!active()) return
        this.state = state
        val stored = readStored(state)
        pulls = stored?.pulls
        fetchedAt = stored?.fetchedAt
        stored?.let { jev = it.jev }
        load(false)
    }

    /** A fresh list even when the current one is recent. */
    suspend fun reload() = load(true)

    /**
     * The Inbox is sorted by risk or grouped by kind, which need Jev's word on every open row. Jev is asked only then:
     * otherwise a row shows a kind and a risk once a review of it had Jev read it.
     */
    var wantsJev by mutableStateOf(false)

    /** Jev is on, the Inbox needs it ([wantsJev]), and open rows on screen have no kind yet: the server is sorting them. */
    val sorting: Boolean get() = jev && wantsJev && state == "open" && pulls.orEmpty().any { it.state == "open" && it.jev == null }

    /**
     * While [sorting], the server's copy is read at once and then every [everyMs], for [forMs] at most, without the
     * server asking GitHub; each read asks Jev to sort what it has not. Only the kinds are taken from it, by row at the
     * same head and title: the list on screen can be newer than that copy, and a load replaces the list.
     */
    suspend fun followJev(everyMs: Long = 3_000, forMs: Long = 120_000) {
        val until = now() + forMs
        var first = true
        while (active() && sorting && now() + everyMs <= until) {
            if (!first) delay(everyMs)
            first = false
            if (!active() || !sorting) break
            val current = generation
            val selected = state
            val server = try { peek(selected, true) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: continue
            val shown = pulls
            if (!active() || current != generation || shown == null) continue
            jev = server.jev
            val sorted = server.pulls.filter { it.jev != null }.associateBy { Triple(it.owner, it.repo, it.number) }
            val filled = shown.map { row ->
                val match = sorted[Triple(row.owner, row.repo, row.number)]?.takeIf { row.jev == null && it.headSha == row.headSha && it.title == row.title }
                match?.let { row.copy(jev = it.jev) } ?: row
            }
            if (filled == shown) continue
            pulls = filled
            writeStored(selected, PullsSnapshot(filled, fetchedAt ?: server.fetchedAt, jev = jev))
        }
    }

    private suspend fun load(force: Boolean) {
        if (!active()) return
        val current = ++generation
        val selected = state
        fun isCurrent() = active() && current == generation
        fun show(value: PullsSnapshot) {
            if (!isCurrent() || (fetchedAt != null && value.fetchedAt < fetchedAt!!)) return
            pulls = value.pulls
            fetchedAt = value.fetchedAt
            writeStored(selected, value)
        }
        updating = true
        error = null
        try {
            val server = try { cached(selected) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            // Whether the server runs Jev is news even when its list is older than the one on screen.
            if (server != null && isCurrent()) jev = server.jev
            if (server != null) show(server)
            if (!isCurrent()) return
            if (!force && server != null && !server.refreshing && now() - server.fetchedAt < freshForMs) return
            val list = fresh(selected)
            show(PullsSnapshot(list, now(), jev = jev))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrent()) {
                error = e.message ?: e.toString()
                effects.loadError(e.message)
            }
        } finally {
            if (isCurrent()) updating = false
        }
    }

    private fun readStored(state: String): PullsSnapshot? = runCatching {
        val raw = storage?.get(STORAGE_PREFIX + state) ?: return null
        val obj = json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (obj["pulls"] !is JsonArray) return null
        obj.asSnapshot()
    }.getOrNull()

    private fun writeStored(state: String, value: PullsSnapshot) {
        runCatching {
            storage?.set(STORAGE_PREFIX + state, buildJsonObject {
                put("pulls", JsonArray(value.pulls.map { it.toJson() }))
                put("fetchedAt", value.fetchedAt)
                if (value.jev) put("classifier", "jev")
            }.toString())
        }
    }

    companion object {
        const val STORAGE_PREFIX = "pulls-review:my-pulls:v1:"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
