package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.KeptStack
import com.octaviantocan.pulls.data.OpenedReview
import com.octaviantocan.pulls.data.Outbox
import com.octaviantocan.pulls.data.PullPage
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ReviewData
import com.octaviantocan.pulls.data.RpcSource
import com.octaviantocan.pulls.data.pullPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Application-owned, main-dispatcher cache. A native identity change calls [clear];
 * every read/write also verifies the current opaque RPC source before exposing data.
 * [pages] hands the current account its store of pages kept on the phone, or null when the host keeps none.
 */
class ReviewSessionCache(private val scope: CoroutineScope, private val pages: () -> PageCopies? = { null }) {
    /** Hosts remount the entire private route after invalidation, including sheets and saved child state. */
    var routeGeneration by mutableLongStateOf(0L)
        private set
    private var source: RpcSource? = null
    private val recent = linkedMapOf<String, RememberedReview>()
    private val warming = linkedMapOf<String, Deferred<OpenedReview>>()
    private val requests = linkedMapOf<String, Job>()
    private var copies: PageCopies? = null
    private var prefetching: Job? = null
    private val sessions = mutableSetOf<ReviewSession>()
    private val presentation = mutableMapOf<SessionStateKey<*>, Any>()
    private var observing = true
    private var observationGeneration = 0L
    /** The account the queued writes go to; every queued write belongs to the current source. */
    private var writer: Repo? = null
    private val outbox = Outbox(
        scope,
        send = { write -> (writer ?: throw CancellationException("The account changed.")).call(write.name, write.args) },
        rejected = { write, _ -> sessions.toList().forEach { it.writeRejected(write) } },
    )

    /**
     * Queues a write the page has already shown, for the server to take in order and retry until it does
     * (`writes-show-first`). False when [expected] is no longer the current source, so nothing is sent.
     */
    internal fun write(repo: Repo, expected: RpcSource?, name: String, args: JsonObject): Boolean {
        if (expected == null || !current(repo, expected)) return false
        writer = repo
        outbox.add(name, args)
        return true
    }

    /** The writes of the current source the server has not confirmed yet, oldest first. */
    internal fun pendingWrites(repo: Repo, expected: RpcSource?): List<Outbox.Mutation> =
        if (expected != null && current(repo, expected)) outbox.pending else emptyList()

    /** Source-owned reading state survives route disposal, but never an identity change. */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> presentation(repo: Repo, expected: RpcSource?, key: SessionStateKey<T>, create: () -> T): T? {
        if (expected == null || !current(repo, expected)) return null
        return presentation.getOrPut(key, create) as T
    }

    internal fun capture(repo: Repo): RpcSource? {
        // A saved, invalid URL must open an actionable error page, never an old-source cache.
        val next = runCatching { repo.source() }.getOrNull()
        if (source != next) {
            val replacing = source != null || sessions.isNotEmpty()
            clearState()
            source = next
            copies = if (next != null) runCatching { pages() }.getOrNull() else null
            if (replacing) routeGeneration++
        }
        return next
    }

    internal fun current(repo: Repo, expected: RpcSource?): Boolean = capture(repo) == expected

    internal fun attach(session: ReviewSession) {
        sessions += session
        if (!observing) session.pauseObservations()
    }
    internal fun release(session: ReviewSession) { sessions -= session }

    /** Scene suspension stops local reads, retaining the same source, drafts, routes and server-owned work. */
    fun pauseObservations() {
        if (!observing) return
        observing = false
        observationGeneration++
        warming.values.toList().forEach { it.cancel() }
        requests.values.toList().forEach { it.cancel() }
        prefetching?.cancel()
        warming.clear()
        requests.clear()
        sessions.toList().forEach { it.pauseObservations() }
    }

    /** Each attached review reconciles through its real opening/saved/task APIs before polling again. */
    fun resumeObservations() {
        if (observing) return
        observing = true
        observationGeneration++
        sessions.toList().forEach { it.resumeObservations() }
    }

    /** Cancel observations and erase old-account state; never cancel a server-owned task. */
    fun clear() {
        clearState()
        routeGeneration++
    }

    private fun clearState() {
        source = null
        copies = null
        prefetching?.cancel()
        prefetching = null
        outbox.clear()
        writer = null
        warming.values.toList().forEach { it.cancel() }
        requests.values.toList().forEach { it.cancel() }
        warming.clear()
        requests.clear()
        recent.clear()
        presentation.clear()
        val previous = sessions.toList()
        sessions.clear()
        previous.forEach { it.invalidate() }
    }

    internal fun remembered(repo: Repo, expected: RpcSource?, name: String): RememberedReview? {
        if (expected == null || !current(repo, expected)) return null
        recent.remove(name)?.let { kept -> recent[name] = kept; return kept }
        // A page read before the tap, on this run or an earlier one.
        val kept = runCatching { copies?.read(name) }.getOrNull()?.remembered() ?: return null
        remember(repo, expected, name, kept)
        return kept
    }

    internal fun remember(repo: Repo, expected: RpcSource?, name: String, value: RememberedReview) {
        if (expected == null || !current(repo, expected)) return
        recent.remove(name)
        recent[name] = value
        while (recent.size > LIMIT) recent.remove(recent.keys.first())
    }

    internal fun warming(repo: Repo, expected: RpcSource?, name: String): Deferred<OpenedReview>? =
        if (expected != null && current(repo, expected)) warming[name] else null

    /** Warm the same production opening used after a tap; a page joins its in-flight request. */
    fun warm(repo: Repo, pull: OpenTarget.Pull) {
        val expected = capture(repo) ?: return
        if (!observing) return
        val generation = observationGeneration
        val name = pull.label
        if (recent.containsKey(name) || requests.containsKey(name)) return
        while (requests.size >= LIMIT) {
            val oldest = requests.keys.first()
            warming.remove(oldest)?.cancel()
            requests.remove(oldest)?.cancel()
        }
        val diff = scope.async { repo.open(pull) }
        warming[name] = diff
        val request = scope.launch {
            val overview = async { runCatching { repo.overview(pull) }.getOrNull() }
            try {
                val opened = diff.await()
                if (!observing || generation != observationGeneration || !current(repo, expected)) return@launch
                if (!recent.containsKey(name)) remember(repo, expected, name, RememberedReview(opened, null))
                val fetched = overview.await()
                if (!observing || generation != observationGeneration || !current(repo, expected)) return@launch
                if (fetched != null) remembered(repo, expected, name)?.let { kept ->
                    if (kept.overview == null) remember(repo, expected, name, kept.copy(overview = fetched))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed warm is retried by the real page, without disturbing other rows.
            } finally {
                if (warming[name] === diff) warming.remove(name)
            }
        }
        requests[name] = request
        request.invokeOnCompletion { if (requests[name] === request) requests.remove(name) }
    }

    /**
     * Reads the pages of [pulls] in order, two at a time, and keeps each on the phone, so any of them opens at once
     * (`pages-are-read-before-the-tap`). A page kept at the row's version is not read again; a newer list replaces the
     * one being read. A host that keeps no pages warms the first [WARM_TOP] in memory instead.
     */
    fun prefetch(repo: Repo, pulls: List<PageRequest>) {
        val expected = capture(repo) ?: return
        if (!observing) return
        val store = copies ?: return pulls.take(WARM_TOP).forEach { warm(repo, it.pull) }
        val generation = observationGeneration
        val wanted = ArrayDeque(pulls.take(PAGE_LIMIT).filter { request ->
            request.version == null || runCatching { store.version(request.pull.label) }.getOrNull() != request.version
        })
        prefetching?.cancel()
        if (wanted.isEmpty()) return
        fun still() = observing && generation == observationGeneration && current(repo, expected) && copies === store
        prefetching = scope.launch {
            coroutineScope {
                repeat(PREFETCH_AT_ONCE) {
                    launch {
                        while (still()) {
                            val request = wanted.removeFirstOrNull() ?: break
                            val page = try {
                                repo.pullPage(request.pull, request.version)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // The page reads it when tapped; the next list tries again.
                                continue
                            }
                            if (!still()) break
                            runCatching { store.write(request.pull.label, page) }
                            // A copy kept in memory is older than this one.
                            recent.remove(request.pull.label)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val LIMIT = 12
        const val WARM_TOP = 4
        /** How many Inbox rows have their pages read before they are tapped: the 150 the server keeps fresh. */
        const val PAGE_LIMIT = 150
        const val PREFETCH_AT_ONCE = 2
    }
}

/** A pull request the Inbox shows, at the version its row has ([PullPage.version]). */
data class PageRequest(val pull: OpenTarget.Pull, val version: String?)

internal data class RememberedReview(
    val opened: OpenedReview,
    val overview: JsonObject?,
    val reviews: ReviewData? = null,
    val stack: KeptStack? = null,
)

private fun PullPage.remembered(): RememberedReview? =
    opened()?.let { opened -> RememberedReview(opened, overview(), reviews(), stack()) }
