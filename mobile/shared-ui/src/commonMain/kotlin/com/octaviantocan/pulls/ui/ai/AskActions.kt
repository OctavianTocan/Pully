package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.review.ReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.SessionStateKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class AskOwner(var view: ReviewSession.Version? = null) {
    val closing = mutableSetOf<String>()
}
private val askOwnerKey = SessionStateKey<AskOwner>()

/** Pending questions use the same page-local AI state, cleared when that page shows a different full view. */
internal fun askUi(session: ReviewSession): AiUi {
    val ui = aiUi(session)
    val state = session.pageState(askOwnerKey) { AskOwner() }
    val view = session.captureView()
    if (state.view != view) {
        state.view = view
        state.closing.clear()
        ui.askPending.clear()
    }
    return ui
}

/** The same selection/question RPC as Android, with an explicit host policy and captured drawn owner. */
internal fun ReviewSession.ask(
    threadId: String,
    selection: AskSelection,
    question: String,
    turnsBefore: Int,
    owner: ReviewSession.Version?,
    capabilities: AiCapabilities,
    readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
    afterStart: () -> Unit = {},
    onStartError: (String) -> Unit = {},
) {
    val text = question.trim()
    if (!readiness.ai(capabilities).canAsk || text.isEmpty() || owner == null || !isCurrentView(owner)) return
    val ui = askUi(this)
    if (threadId in ui.askPending) return
    trackAsk(text.length)
    ui.askPending[threadId] = text to turnsBefore
    startAiTask(
        "ask", threadId,
        extra = {
            put("threadId", threadId)
            put("selection", buildJsonObject { put("path", selection.path); put("side", selection.side); put("startLine", selection.startLine); put("endLine", selection.endLine) })
            put("question", text)
        },
        // The server saved the question with the task: reload to show the thread (with its task id).
        afterStart = { reloadSaved(); afterStart() },
        onStartError = { error -> ui.askPending.remove(threadId); onStartError(error) },
    )
}

/** Close/cancel controls are absent on hosts which cannot yet support their complete Ask interaction. */
internal fun ReviewSession.closeAskThread(threadId: String, taskId: String?, owner: ReviewSession.Version?, capabilities: AiCapabilities, readiness: ReviewActionReadiness = ReviewActionReadiness.Default) {
    if (!readiness.ai(capabilities).canAsk || owner == null || !isCurrentView(owner)) return
    askUi(this)
    val state = pageState(askOwnerKey) { AskOwner() }
    if (!state.closing.add(threadId)) return
    if (taskId != null && tasks[taskId]?.running == true) cancelTask(taskId)
    scope.launch {
        try {
            if (!isCurrentView(owner) || !readiness.ai(capabilities).canAsk) return@launch
            val result = runCatching { repo.closeAsk(owner.key, threadId) }
            currentCoroutineContext().ensureActive()
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (isCurrentView(owner) && readiness.ai(capabilities).canReadResults) reloadSaved()
        } finally {
            if (isCurrentView(owner)) state.closing.remove(threadId)
        }
    }
}
