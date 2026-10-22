package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** A first page older than this, as the server answered it, is asked for again from GitHub straight after. */
private const val FIRST_PAGE_FRESH_MS = 60_000L

/** `PullRequestListStore`: the first page, then pages appended through the `next` cursor. */
class RepoPullsStore(
    private val repo: Repo,
    val owner: String,
    val name: String,
    private val pages: FirstPages,
    private val currentSource: () -> Boolean,
    private val effects: HomeScreenEffects,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val seen = pages.get(owner, name)
    var items by mutableStateOf(seen?.items.orEmpty())
    var totalCount by mutableStateOf(seen?.totalCount)
    var next by mutableStateOf(seen?.next)
    var isLoading by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var generation = 0
    private var viewedGeneration = 0
    private var invalidated = false
    private fun active() = !invalidated && currentSource()

    fun invalidate() {
        invalidated = true
        generation++
        viewedGeneration++
        items = emptyList(); totalCount = null; next = null; viewed = emptyMap()
        isLoading = false; isLoadingMore = false; error = null; answeredOld = false
    }
    val hasMore get() = next != null

    /** Whether the last first page the server sent was an old copy, so a fresh one should follow. */
    private var answeredOld = false

    private suspend fun fetchFirstPage(fresh: Boolean = false): Boolean {
        if (!active()) return false
        val current = ++generation
        // A first page supersedes any old cursor request. Its eventual completion cannot own this flag.
        isLoadingMore = false
        error = null
        try {
            val raw = repo.repoPulls(owner, name, fresh = fresh)
            val page = raw.asRepoPullsPage()
            if (!active() || current != generation) return false
            items = page.items; totalCount = page.totalCount; next = page.next
            val cachedAt = (raw["cachedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            answeredOld = cachedAt != null && now() - cachedAt > FIRST_PAGE_FRESH_MS
            pages.put(owner, name, FirstPages.Page(page.items, page.totalCount, page.next))
            pages.write(owner, name, raw)
            return active() && current == generation
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (active() && current == generation) {
                error = e.message ?: e.toString()
                effects.error("repo.load", e.message)
            }
            return false
        } finally {
            if (active() && current == generation) isLoading = false
        }
    }

    /** `viewed`: what the server's cache holds for PRs of this repo already opened (`loadViewed`). */
    var viewed by mutableStateOf<Map<Int, Viewed>>(emptyMap())
        private set

    suspend fun loadViewed() {
        if (!active()) return
        val current = ++viewedGeneration
        try {
            val result = repo.call("repo-viewed") { put("owner", owner); put("repo", name) }
            if (!active() || current != viewedGeneration) return
            viewed = (result as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun int(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                int("number") to Viewed(int("additions"), int("deletions"), int("groups"), (o["hasAiResult"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true")
            }.toMap()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Independent cached decorations remain best effort. */ }
    }

    /**
     * The first page: the rows last seen (in this run, else kept on the phone) are on screen at once while it comes, and
     * only a list with none shows the skeleton. The server answers from its own copy; an old one is asked for again.
     */
    suspend fun load() {
        if (!active()) return
        val beforeRead = generation
        if (items.isEmpty()) pages.read(owner, name)?.let { kept ->
            if (active() && generation == beforeRead && items.isEmpty()) { items = kept.items; totalCount = kept.totalCount; next = kept.next }
        }
        if (!active() || generation != beforeRead) return
        if (items.isEmpty()) isLoading = true
        if (fetchFirstPage()) followOldAnswer()
    }

    /** The server answered from a copy older than a minute: ask again for GitHub's own, quietly. */
    private suspend fun followOldAnswer() {
        if (active() && answeredOld && error == null) {
            if (fetchFirstPage(fresh = true)) refreshes++
        }
    }

    /** How many quiet refreshes have landed: a search keys on it to fetch the later pages again after one. */
    var refreshes by mutableIntStateOf(0)
        private set

    /** The first page again, quietly, over the rows on screen. */
    suspend fun refresh() {
        if (!active()) return
        if (fetchFirstPage()) { refreshes++; followOldAnswer() }
    }

    suspend fun loadMore() {
        if (!active()) return
        val cursor = next ?: return
        if (isLoadingMore) return
        val current = generation
        isLoadingMore = true
        error = null
        try {
            val page = repo.repoPulls(owner, name, cursor).asRepoPullsPage()
            if (!active() || current != generation) return
            items = items + page.items; totalCount = page.totalCount; next = page.next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (active() && current == generation) error = e.message ?: e.toString()
        } finally {
            if (active() && current == generation) isLoadingMore = false
        }
    }

    suspend fun loadAll() {
        // Waits out a page already on its way rather than spinning on the main thread until it lands.
        while (active() && next != null && error == null) if (isLoadingMore) delay(50) else loadMore()
    }
}

/** A cached diff's size and grouping for a PR opened before. */
data class Viewed(val additions: Int, val deletions: Int, val groups: Int, val hasAiResult: Boolean)
