package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.core.nextUnreviewed
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.design.ReviewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The displayed file's real navigation, Ask and viewer-progress actions share its exact issued owner. */
internal class DiffPageActions(
    private val session: ReviewSession,
    private val ui: DiffUi,
    private val file: ReviewFile,
    private val scope: CoroutineScope,
    private val capabilities: () -> ReviewCapabilities,
    private val aiCapabilities: () -> AiCapabilities,
    private val isShown: () -> Boolean,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
) {
    private val owner = ui.captureFileRead(file)
    private var closed = false
    fun close() { closed = true }
    val current: Boolean get() = !closed && readiness.review(capabilities()).canReadDiff && isShown() && ui.isCurrentFileRead(owner, file)
    fun read(action: () -> Unit) { if (current) action() }
    fun ask(action: () -> Unit) { if (current && readiness.ai(aiCapabilities()).canAsk) action() }

    /** Viewer progress retains its original blob-SHA semantics and requires no GitHub write permission. */
    fun toggleReviewed(files: List<ReviewFile>, feedback: (Cue) -> Unit, onPath: (String) -> Unit) {
        if (!current || !readiness.review(capabilities()).canMarkReviewed) return
        val reviewed = session.reviewState(file) == ReviewState.Reviewed
        session.setReviewed(listOf(file.sha), !reviewed)
        if (reviewed) return
        // The final file gets its success sound, with no toast or extra counter (rule 42).
        if (files.all { it.sha in session.reviewed || it.path == file.path }) feedback(Cue.Success)
        val shaOf = files.associate { it.path to it.sha }
        nextUnreviewed(files.map { it.path }, file.path) { path -> shaOf[path] in session.reviewed }?.let { next ->
            scope.launch {
                delay(220)
                if (current && readiness.review(capabilities()).canMarkReviewed) onPath(next)
            }
        }
    }
}

/** Exact production lifetime: another file visit closes the old actions, even when that visit later returns. */
@Composable
internal fun rememberDiffPageActions(
    session: ReviewSession,
    ui: DiffUi,
    file: ReviewFile,
    scope: CoroutineScope,
    isShown: () -> Boolean,
): DiffPageActions {
    val owner = ui.captureFileRead(file)
    val capabilities by rememberUpdatedState(LocalReviewCapabilities.current)
    val aiCapabilities by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val shown by rememberUpdatedState(isShown)
    val actions = remember(session, ui, owner, scope, readiness) {
        DiffPageActions(session, ui, file, scope, { capabilities }, { aiCapabilities }, { shown() }, readiness)
    }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
