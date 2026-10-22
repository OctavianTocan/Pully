package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.SessionStateKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

data class SharedCandidate(val login: String, val own: Boolean, val stale: Boolean, val locale: String?)
private val sharedStateKey = SessionStateKey<SharedAnalysisState>()
internal fun sharedAnalysisState(session: ReviewSession): SharedAnalysisState =
    session.pageState(sharedStateKey) { SharedAnalysisState(session) }

/** Shared-result adoption belongs to the same view as its chips, including after suspension. */
internal class SharedAnalysisState(private val session: ReviewSession) {
    private var owner = session.captureView()
    private var sequence = 0L
    var candidates by mutableStateOf<List<SharedCandidate>>(emptyList())
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    fun viewOwner(): ReviewSession.Version? {
        val next = session.captureView()
        if (next != owner) {
            owner = next; sequence++
            candidates = emptyList(); notice = null; loading = false
        }
        return next
    }

    private fun current(view: ReviewSession.Version, request: Long): Boolean =
        session.isCurrentView(view) && owner == view && sequence == request

    suspend fun discover(view: ReviewSession.Version?) {
        if (view == null || viewOwner() != view || loading) return
        val request = ++sequence
        try {
            val result = session.repo.call("shared-list") { put("key", view.key) } as? JsonObject ?: return
            currentCoroutineContext().ensureActive()
            if (!current(view, request)) return
            if (result["loaded"] != null) {
                val loaded = session.reloadSavedNow()
                currentCoroutineContext().ensureActive()
                if (!current(view, request)) return
                if (loaded) session.analyzeMode = "ai"
            }
            candidates = (result["candidates"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val item = entry as? JsonObject ?: return@mapNotNull null
                fun string(key: String) = (item[key] as? JsonPrimitive)?.content
                SharedCandidate(string("login").orEmpty(), string("own") == "true", string("stale") == "true", string("locale"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Discovery is optional. The existing local result and review remain usable.
        }
    }

    suspend fun load(view: ReviewSession.Version?, candidate: SharedCandidate) {
        if (view == null || viewOwner() != view || loading || candidate !in candidates) return
        val request = ++sequence
        loading = true
        try {
            session.repo.call("shared-load") { put("key", view.key); put("login", candidate.login) }
            currentCoroutineContext().ensureActive()
            if (!current(view, request)) return
            val loaded = session.reloadSavedNow()
            currentCoroutineContext().ensureActive()
            if (!current(view, request)) return
            if (loaded) {
                candidates = emptyList(); notice = null
                session.analyzeMode = "ai"
            } else notice = "The shared analysis could not be loaded. Try again."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (current(view, request)) notice = failure.message?.takeIf { it.isNotBlank() }
                ?: "The shared analysis could not be loaded. Try again."
        } finally {
            if (current(view, request)) loading = false
        }
    }
}
