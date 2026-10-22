package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.serialization.json.JsonObjectBuilder
import com.octaviantocan.pulls.ui.review.SessionStateKey

/**
 * Page-local AI state the web keeps in its critique, share, ask and chat stores, shared by
 * every AI section of one review page (header buttons, the panel, the threads, the chat).
 * One per [ReviewSession], dropped with it.
 */
class AiUi {
    // --- Critique (`critique-store.ts`) ---
    /** Which result [selected] / [posted] belong to; a new result resets them. */
    var critiqueFor by mutableStateOf<String?>(null)
    var critiqueBodyFor by mutableStateOf<String?>(null)
    var severities by mutableStateOf(SEVERITIES.toSet())
    var selected by mutableStateOf(setOf<String>())
    var posted by mutableStateOf(setOf<String>())
    var postBody by mutableStateOf("")
    var posting by mutableStateOf(false)
    internal var critiquePostOwner: Any? = null
    var postError by mutableStateOf<String?>(null)
    var panelOpen by mutableStateOf(true)

    // --- Ask: a question sent but not yet in the saved thread, by thread id, with the turn count it was sent at ---
    val askPending = mutableStateMapOf<String, Pair<String, Int>>()

    // --- Chat ---
    var chatInput by mutableStateOf("")
    var chatPending by mutableStateOf<String?>(null)
    var chatError by mutableStateOf<String?>(null)

    // --- Banner ---
    var bannerDismissed by mutableStateOf(false)

    /** Tasks this page started. */
    var startedHere by mutableStateOf(setOf<String>())

    /** Tasks this page saw running (started here or resumed): their settled state stays on screen. */
    var watched by mutableStateOf(setOf<String>())
}

private val aiStateKey = SessionStateKey<AiUi>()

fun aiUi(session: ReviewSession): AiUi = session.pageState(aiStateKey) { AiUi() }

/** `outcome` of a settled task, as the activity log words it. */
fun outcomeOf(task: TaskState?): String = when (task?.status) {
    "failed" -> "failed"
    "cancelled" -> "stopped"
    else -> "done"
}

/**
 * The task of [kind] a surface shows: the newest one, while it runs or once this page has
 * seen it run. Finished tasks from before this page opened (`ai-task-list` keeps them a
 * while) are history, not status, as on the web.
 */
@Composable
fun rememberLiveTask(session: ReviewSession, kind: String, threadId: String? = null): TaskState? {
    val ui = aiUi(session)
    val task = session.taskFor(kind, threadId)
    LaunchedEffect(task?.id, task?.running) {
        if (task != null && task.running && task.id !in ui.watched) ui.watched = ui.watched + task.id
    }
    return task?.takeIf { it.running || it.id in ui.watched || it.id in ui.startedHere }
}

/**
 * Starts a task and records it as started here; [afterStart] runs once the server has the
 * task (an ask turn is saved by then), [onDone] once it settles and saved state reloaded.
 */
fun ReviewSession.startAiTask(
    kind: String,
    threadId: String? = null,
    extra: JsonObjectBuilder.() -> Unit,
    effort: String? = null,
    then: List<String> = emptyList(),
    afterStart: (() -> Unit)? = null,
    onStartError: ((String) -> Unit)? = null,
    onDone: (TaskState) -> Unit = {},
) {
    val ui = aiUi(this)
    startTask(kind, extra, effort, then, onStarted = { id ->
        ui.startedHere = ui.startedHere + id
        ui.watched = ui.watched + id
        // `ReviewSession.startTask` records a start the server refused as a settled `failed-…` task.
        if (id.startsWith("failed-")) onStartError?.invoke(tasks[id]?.error ?: "Request failed") else afterStart?.invoke()
    }, onDone = onDone)
}
