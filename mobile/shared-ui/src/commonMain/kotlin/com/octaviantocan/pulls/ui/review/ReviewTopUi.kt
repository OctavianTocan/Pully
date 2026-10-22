package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Page-level UI state the web keeps in `PrOverviewStore`: the action in flight and its error, the
 * overview poll count, and which of the review sheets (progress, submit, merge) are open.
 */
@Stable
class ReviewTopUi(private val feedback: (Cue, Emphasis) -> Unit = { cue, emphasis -> ControlFeedback.play(cue, emphasis) }) {
    /** `merge`, `update-branch`, `close`, `reopen`, `ready` or `draft` while it runs. */
    var busy by mutableStateOf<String?>(null)
    var actionError by mutableStateOf<String?>(null)

    var reviewOpen by mutableStateOf(false)
    /** The merge page is on screen, so a failed merge is said there and not in a toast. */
    var mergeOpen by mutableStateOf(false)

    /** The verdict the submit sheet opens on: `COMMENT`, `APPROVE` or `REQUEST_CHANGES`. */
    var reviewVerdict by mutableStateOf("COMMENT")

    /**
     * The AI's summary the person switched away from to read the description in its place, as its `summaryKey`. Any
     * other summary, such as the next analysis, shows again (`showsDescription`).
     */
    var descriptionOver by mutableStateOf<String?>(null)

    /** Overview polls since the last refresh (`MAX_POLLS` 20 in `pr-overview-store.ts`). */
    var polls by mutableIntStateOf(0)

    /**
     * `PrOverviewStore.run`: one action at a time; on success the PR page reloads (diff,
     * overview, reviews) and [onOk] runs; on failure the message stays until dismissed.
     */
    fun run(session: ReviewSession, action: String, onOk: () -> Unit = {}, task: suspend () -> Unit) =
        run(session.scope, action, onOk, session::refresh, task)

    internal fun run(scope: CoroutineScope, action: String, onOk: () -> Unit = {}, refresh: () -> Unit, task: suspend () -> Unit) {
        if (busy != null) return
        busy = action
        actionError = null
        scope.launch {
            try {
                val result = runCatching { task() }
                // Detaching the route or changing account is not a completed or failed action in a new owner's UI.
                currentCoroutineContext().ensureActive()
                // A merge is the one outcome that sounds at full weight; the rest are quiet confirmations.
                result.onSuccess { polls = 0; refresh(); feedback(Cue.Success, if (action == "merge") Emphasis.Strong else Emphasis.Subtle); onOk() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        actionError = it.message ?: it.toString()
                        feedback(Cue.Error, Emphasis.Subtle)
                    }
            } finally {
                busy = null
            }
        }
    }
}
