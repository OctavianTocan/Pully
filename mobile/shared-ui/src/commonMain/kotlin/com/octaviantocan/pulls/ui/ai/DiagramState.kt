package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.SessionStateKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DiagramKey(val dark: Boolean, val mermaid: String)
private val diagramStateKey = SessionStateKey<DiagramState>()
internal fun diagramState(session: ReviewSession): DiagramState =
    session.pageState(diagramStateKey) { DiagramState(session) }

/** SVG text is private review data. The current drawing may be larger than the retained cache. */
internal class DiagramState(private val session: ReviewSession) {
    private var owner = session.captureView()
    private val results = linkedMapOf<DiagramKey, Result<String>>()
    private class Gate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val gates = mutableMapOf<DiagramKey, Gate>()
    internal val retainedResults: Int get() = results.size
    internal val retainedRequests: Int get() = gates.size

    fun viewOwner(): ReviewSession.Version? {
        val next = session.captureView()
        if (next != owner) {
            results.clear(); gates.clear()
            owner = next
        }
        return next
    }

    fun cached(key: DiagramKey): Result<String>? {
        if (viewOwner() == null) return null
        return results.remove(key)?.also { results[key] = it }
    }

    suspend fun load(key: DiagramKey): Result<String>? {
        val view = viewOwner() ?: return null
        val gate = gates.getOrPut(key) { Gate() }
        gate.users++
        try {
            return gate.mutex.withLock {
                if (!session.isCurrentView(view)) return@withLock null
                cached(key)?.let { return@withLock it }
                val result = try { Result.success(session.repo.mermaidSvg(key.mermaid, key.dark)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
                currentCoroutineContext().ensureActive()
                if (!session.isCurrentView(view)) return@withLock null
                // A large current drawing still renders, without retaining it after its composition leaves.
                if (weight(key, result) <= DIAGRAM_CHARACTERS_KEPT) {
                    results[key] = result
                    while (results.size > DIAGRAMS_KEPT || results.entries.sumOf { weight(it.key, it.value) } > DIAGRAM_CHARACTERS_KEPT) {
                        results.remove(results.keys.first())
                    }
                }
                result
            }
        } finally {
            gate.users--
            if (gate.users == 0 && gates[key] === gate) gates.remove(key)
        }
    }
}

private fun weight(key: DiagramKey, result: Result<String>): Int =
    key.mermaid.length + (result.getOrNull()?.length ?: 0)

private const val DIAGRAMS_KEPT = 16
private const val DIAGRAM_CHARACTERS_KEPT = 2_000_000
