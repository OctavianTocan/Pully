package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Exact displayed tour and existing view revision, including a cached tour before the opening arrives. */
internal class TourRead internal constructor(
    internal val session: ReviewSession,
    internal val view: ReviewSession.Version?,
    internal val tour: Tour?,
)

/** The actual screen's retained commands and gestures consult the current host policy at invocation. */
internal class TourReaderActions(
    private val session: ReviewSession,
    private val owner: TourRead?,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
    private val capabilities: () -> AiCapabilities,
) {
    private val scrolls = mutableSetOf<Job>()
    private var disposed = false
    val current: Boolean get() = !disposed && readiness.ai(capabilities()).canReadResults && session.isCurrentTourRead(owner)
    fun read(action: () -> Unit) { if (current) action() }
    fun ask(action: () -> Unit) { if (current && readiness.ai(capabilities()).canAsk) action() }
    fun regenerate(action: () -> Unit) { if (current && readiness.ai(capabilities()).canTour) action() }
    fun scroll(scope: CoroutineScope, action: suspend () -> Unit) {
        if (!current) return
        val job = scope.launch { if (current) action() }
        scrolls += job
        job.invokeOnCompletion { scrolls -= job }
    }
    fun close() {
        disposed = true
        scrolls.toList().forEach { it.cancel() }
        scrolls.clear()
    }
}

/** Same in-memory fold behavior, owned by the app's current RPC source rather than a process global. */
internal class TourFolds {
    private val folded = mutableStateMapOf<Int, Boolean>()
    fun folded(index: Int): Boolean = folded[index] == true
    fun toggle(index: Int) { folded[index] = !folded(index) }
    fun unfold(index: Int) { folded.remove(index) }
    fun foldAll(count: Int) { for (i in 0 until count) folded[i] = true }
    fun unfoldAll() = folded.clear()
}

internal class TourFoldStore(private val limit: Int? = tourFoldMemoryLimit) {
    private val tours = linkedMapOf<String, TourFolds>()
    fun of(key: String): TourFolds = (tours.remove(key) ?: TourFolds()).also {
        tours[key] = it
        if (limit != null) while (tours.size > limit) tours.remove(tours.keys.first())
    }
}

private val tourFoldStoreKey = SessionStateKey<TourFoldStore>()
internal fun ReviewSession.tourFolds(generatedAt: String?): TourFolds =
    sourcePresentation(tourFoldStoreKey) { TourFoldStore() }?.of("$target|$generatedAt") ?: TourFolds()

/** Native retention follows the existing 200-tour policy; Android retains its original app-session behavior. */
internal expect val tourFoldMemoryLimit: Int?

/** Stop numbers only: no tour text, file paths or repository identity enter this telemetry. */
internal class TourReading(private val track: (String, Int, Int?) -> Unit) {
    private var furthest = 0
    private var finished = false
    fun at(stop: Int, total: Int) {
        if (furthest == 0) track("started", stop, total)
        else if (stop > furthest && !finished) track("next", stop, total)
        if (stop > furthest) furthest = stop
        if (stop == total && !finished) {
            finished = true
            track("finished", stop, total)
        }
    }
    fun leave() { if (furthest > 0 && !finished) track("skipped", furthest, null) }
}

/** Private input belongs to the displayed view; ordinary recomposition keeps the current keystroke. */
internal class TourQuestionState {
    var text by mutableStateOf("")
    var focused by mutableStateOf(false)
}

@Composable
internal fun rememberTourQuestionState(session: ReviewSession, owner: TourRead?): TourQuestionState =
    remember(session, owner) { TourQuestionState() }
