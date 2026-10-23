package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import com.octaviantocan.pulls.core.PSEUDO_PROBE
import com.octaviantocan.pulls.core.PSEUDO_PROBE_AT
import com.octaviantocan.pulls.core.PseudoKind
import com.octaviantocan.pulls.ui.review.HIGHLIGHT_BATCH_SIZE
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.SessionStateKey
import com.octaviantocan.pulls.ui.review.Tok
import com.octaviantocan.pulls.ui.review.readHighlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SnippetKey(val dark: Boolean, val extension: String, val code: String)
private val codeStateKey = SessionStateKey<AiCodeState>()
internal fun codeState(session: ReviewSession): AiCodeState = session.pageState(codeStateKey) { AiCodeState(session) }

/** Private highlighted text lives only with its review route and current source/head revision. */
internal class AiCodeState(private val session: ReviewSession) {
    private var owner = session.captureView()
    private val snippets = mutableStateMapOf<SnippetKey, Map<String, List<Tok>>>()
    private class Gate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val locks = mutableMapOf<SnippetKey, Gate>()
    /** Coordination retains code only while a request or waiter still owns it. */
    internal val pendingHighlights: Int get() = locks.values.sumOf { it.users }
    internal val retainedHighlightKeys: Int get() = locks.size
    private val pseudo = mutableStateMapOf<Boolean, Map<PseudoKind, Color?>>()
    private val pseudoAsked = mutableSetOf<Boolean>()

    private fun useOwner(): ReviewSession.Version? {
        val next = session.captureView()
        if (next != owner) {
            snippets.clear(); locks.clear(); pseudo.clear(); pseudoAsked.clear()
            owner = next
        }
        return next
    }

    internal fun viewOwner(): ReviewSession.Version? = useOwner()

    fun tokens(key: SnippetKey): List<List<Tok>?>? {
        if (useOwner() == null) return null
        val rows = snippets[key] ?: return null
        return key.code.lines().map { rows[it] }
    }

    suspend fun load(key: SnippetKey) {
        val view = useOwner() ?: return
        val gate = locks.getOrPut(key) { Gate() }
        gate.users++
        try {
            gate.mutex.withLock {
                if (!session.isCurrentView(view)) return@withLock
                val have = snippets[key].orEmpty()
                val next = have.toMutableMap()
                val missing = key.code.lines().distinct().filter { it !in have }
                for (chunk in missing.chunked(HIGHLIGHT_BATCH_SIZE)) {
                    val result = try { session.repo.highlight("snippet.${key.extension}", chunk, key.dark) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { break }
                    currentCoroutineContext().ensureActive()
                    if (!session.isCurrentView(view)) return@withLock
                    val spans = readHighlight(result, chunk).second ?: break
                    next.putAll(spans)
                }
                if (session.isCurrentView(view) && next.size > have.size) {
                    if (key !in snippets && snippets.size >= CODE_TOKENS_KEPT) snippets.clear()
                    snippets[key] = next
                }
            }
        } finally {
            gate.users--
            if (gate.users == 0 && locks[key] === gate) locks.remove(key)
        }
    }

    fun pseudoColors(dark: Boolean): Map<PseudoKind, Color?>? {
        if (useOwner() == null) return null
        return pseudo[dark]
    }

    suspend fun loadPseudo(dark: Boolean) {
        val view = useOwner() ?: return
        if (dark in pseudo || !pseudoAsked.add(dark)) return
        try {
            val result = session.repo.highlight("probe.py", listOf(PSEUDO_PROBE), dark)
            currentCoroutineContext().ensureActive()
            if (!session.isCurrentView(view)) return
            val spans = readHighlight(result, listOf(PSEUDO_PROBE)).second?.get(PSEUDO_PROBE).orEmpty()
            if (spans.isEmpty()) return
            var at = 0
            val ranges = spans.map { token -> (at until at + token.text.length).also { at += token.text.length } to token.color }
            pseudo[dark] = PSEUDO_PROBE_AT.mapValues { (_, offset) -> ranges.firstOrNull { offset in it.first }?.second }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The existing text-color rendering remains readable and a later composition can retry.
        } finally {
            if (session.isCurrentView(view)) pseudoAsked.remove(dark)
        }
    }
}

private const val CODE_TOKENS_KEPT = 64
