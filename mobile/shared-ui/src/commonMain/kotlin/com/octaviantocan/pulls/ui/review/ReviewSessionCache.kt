package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.OpenedReview
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RpcSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Application-owned, main-dispatcher cache. A native identity change calls [clear];
 * every read/write also verifies the current opaque RPC source before exposing data.
 */
class ReviewSessionCache(private val scope: CoroutineScope) {
    /** Hosts remount the entire private route after invalidation, including sheets and saved child state. */
    var routeGeneration by mutableLongStateOf(0L)
        private set
    private var source: RpcSource? = null
    private val recent = linkedMapOf<String, RememberedReview>()
    private val warming = linkedMapOf<String, Deferred<OpenedReview>>()
    private val requests = linkedMapOf<String, Job>()
    private val sessions = mutableSetOf<ReviewSession>()

    internal fun capture(repo: Repo): RpcSource? {
        // A saved, invalid URL must open an actionable error page, never an old-source cache.
        val next = runCatching { repo.source() }.getOrNull()
        if (source != next) {
            val replacing = source != null || sessions.isNotEmpty()
            clearState()
            source = next
            if (replacing) routeGeneration++
        }
        return next
    }

    internal fun current(repo: Repo, expected: RpcSource?): Boolean = capture(repo) == expected

    internal fun attach(session: ReviewSession) { sessions += session }
    internal fun release(session: ReviewSession) { sessions -= session }

    /** Cancel observations and erase old-account state; never cancel a server-owned task. */
    fun clear() {
        clearState()
        routeGeneration++
    }

    private fun clearState() {
        source = null
        warming.values.toList().forEach { it.cancel() }
        requests.values.toList().forEach { it.cancel() }
        warming.clear()
        requests.clear()
        recent.clear()
        val previous = sessions.toList()
        sessions.clear()
        previous.forEach { it.invalidate() }
    }

    internal fun remembered(repo: Repo, expected: RpcSource?, name: String): RememberedReview? {
        if (expected == null || !current(repo, expected)) return null
        return recent.remove(name)?.also { recent[name] = it }
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
                if (!current(repo, expected)) return@launch
                if (!recent.containsKey(name)) remember(repo, expected, name, RememberedReview(opened, null))
                val fetched = overview.await()
                if (!current(repo, expected)) return@launch
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

    companion object {
        const val LIMIT = 12
        const val WARM_TOP = 4
    }
}

internal data class RememberedReview(val opened: OpenedReview, val overview: JsonObject?)
