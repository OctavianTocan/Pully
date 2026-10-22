package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.octaviantocan.pulls.ui.review.LocalReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.SessionStateKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.put

private class ChatOwner {
    var view: ReviewSession.Version? = null
    var turn = 0L
    var waiting by mutableStateOf(false)
    var change: Job? = null
    var changed = false
}
private val chatOwnerKey = SessionStateKey<ChatOwner>()

/** A private draft and optimistic question belong to this exact review, including its full view revision. */
internal fun chatUi(session: ReviewSession): AiUi {
    val ui = aiUi(session)
    val state = session.pageState(chatOwnerKey) { ChatOwner() }
    val view = session.captureView()
    if (state.view != view) {
        state.view = view
        state.turn++
        state.waiting = false
        state.change = null
        state.changed = false
        session.chatChange?.cancel()
        session.chatChange = null
        ui.chatInput = ""
        ui.chatPending = null
        ui.chatError = null
    }
    return ui
}

/** The real Chat RPCs and callbacks, fenced to their drawn owner rather than a replacement page. */
internal class ChatActions(
    private val session: ReviewSession,
    private val owner: ReviewSession.Version?,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
    private val capabilities: () -> AiCapabilities,
) {
    private val ui = chatUi(session)
    private val state = session.pageState(chatOwnerKey) { ChatOwner() }
    private var disposed by mutableStateOf(false)
    private val viewCurrent: Boolean get() = owner != null && session.isCurrentView(owner)
    val current: Boolean get() = !disposed && viewCurrent
    val canRead: Boolean get() = current && readiness.ai(capabilities()).canReadResults
    val canAsk: Boolean get() = current && readiness.ai(capabilities()).canAsk
    val waiting: Boolean get() = current && state.waiting
    val changing: Boolean get() = current && session.chatChange?.isActive == true
    private val busy: Boolean get() = state.waiting || session.taskFor("chat")?.running == true || session.taskFor("analyze")?.running == true

    /** The actual FieldText setter retains its drawn owner even when the per-session AiUi is reused. */
    val changeInput: (String) -> Unit = { value -> if (canAsk) ui.chatInput = value }

    fun read(action: () -> Unit): Boolean {
        if (!canRead) return false
        action()
        return true
    }

    fun send(text: String, reference: AskReference? = null): Boolean {
        val question = text.trim()
        if (!canAsk || busy || question.isEmpty()) return false
        session.trackAsk(question.length, reference != null)
        val payload = if (reference == null) question else "About ${reference.label}: \"${reference.text}\"\n\n$question"
        sendTurn(payload, retry = false)
        return true
    }

    fun retry(text: String?): Boolean {
        if (!canAsk || busy) return false
        sendTurn(text, retry = text == null)
        return true
    }

    private fun sendTurn(text: String?, retry: Boolean) {
        val turn = ++state.turn
        val locale = session.settings.locale
        ui.chatError = null
        ui.chatPending = text
        val change = session.chatChange?.takeIf { it.isActive }
        if (change != null) {
            state.waiting = true
            session.scope.launch {
                try {
                    if (!awaitChange(change)) { abandon(turn, text); return@launch }
                    currentCoroutineContext().ensureActive()
                    if (!canAsk || state.turn != turn || state.change !== change || !state.changed) {
                        abandon(turn, text)
                        return@launch
                    }
                    startTurn(text, retry, locale, turn)
                } catch (cancelled: CancellationException) {
                    abandon(turn, text)
                    throw cancelled
                }
            }
        } else startTurn(text, retry, locale, turn)
    }

    /** A queued new question is not an accepted write and must not survive a live action revocation. */
    private suspend fun awaitChange(change: Job): Boolean = coroutineScope {
        val expected = owner ?: return@coroutineScope false
        val revoked = async(start = CoroutineStart.UNDISPATCHED) {
            snapshotFlow { disposed || !session.matchesView(expected) || !readiness.ai(capabilities()).canAsk }.first { it }
        }
        try {
            select {
                change.onJoin { true }
                revoked.onAwait { false }
            }
        } finally { revoked.cancel() }
    }

    private fun abandon(turn: Long, text: String?) {
        if (!viewCurrent || state.turn != turn) return
        state.waiting = false
        ui.chatPending = null
        if (text != null && ui.chatInput.isBlank()) ui.chatInput = text
        if (ui.chatError == null) ui.chatError = "The question was not sent. Try again."
    }

    private fun startTurn(text: String?, retry: Boolean, locale: String, turn: Long) {
        if (!canAsk || state.turn != turn) { abandon(turn, text); return }
        state.waiting = false
        session.startAiTask("chat", extra = {
            put("locale", locale)
            if (text != null) put("text", text)
            if (retry) put("retry", true)
        }, onStartError = { problem ->
            if (viewCurrent && state.turn == turn) { ui.chatPending = null; ui.chatError = problem }
        }, onDone = { done ->
            if (viewCurrent && state.turn == turn) {
                ui.chatPending = null
                if (done.status == "failed") ui.chatError = done.error ?: "Request failed"
            }
        })
    }

    fun stop(id: String) {
        if (canAsk && session.tasks[id]?.let { it.kind == "chat" && it.running } == true) session.cancelTask(id)
    }

    fun loadHistory(force: Boolean = false): Boolean {
        if (!canRead || session.chatHistoryLoading) return false
        session.loadChatHistory(force)
        return true
    }

    /** The empty conversation is visible in the tap's frame; the same server keeps the old one in history. */
    fun newChat(): Boolean = change(null) {}

    /** The saved conversation is reconciled before Ask is reopened, including a recoverable failure. */
    fun restore(id: String, onRestored: () -> Unit): Boolean = change(id, onRestored)

    /** Native hosts expose snapshot-observable eligibility; a retired view or policy must also wake this wait. */
    private suspend fun awaitReadAuthority(): Boolean {
        val expected = owner ?: return false
        if (!viewCurrent) return false
        snapshotFlow {
            !session.matchesView(expected) || !readiness.installedAi(capabilities()).canReadResults || readiness.ai(capabilities()).canReadResults
        }.first { it }
        currentCoroutineContext().ensureActive()
        return viewCurrent && readiness.ai(capabilities()).canReadResults
    }

    /** Retirement also cancels the inner observation wait after a later pause interrupts this read. */
    private suspend fun reloadForChange(): Boolean? = coroutineScope {
        val expected = owner ?: return@coroutineScope null
        val retired = async(start = CoroutineStart.UNDISPATCHED) {
            snapshotFlow {
                !session.matchesView(expected) || !readiness.installedAi(capabilities()).canReadResults
            }.first { it }
        }
        val reconciliation = async {
            if (!awaitReadAuthority()) return@async null
            val reloaded = session.reloadSavedWhenObserved(expected, reloadHistory = false)
            currentCoroutineContext().ensureActive()
            if (!awaitReadAuthority()) return@async null
            reloaded
        }
        try {
            select {
                retired.onAwait { null }
                reconciliation.onAwait { it }
            }
        } finally {
            reconciliation.cancel()
            retired.cancel()
        }
    }

    private fun change(id: String?, onRestored: () -> Unit): Boolean {
        if (!canAsk || busy || changing || owner == null) return false
        state.turn++
        ui.chatPending = null
        ui.chatError = null
        if (id == null) session.saved = session.saved?.copy(chat = emptyList(), suggestions = emptyList())
        // Publish the job before it starts, so a question in this same frame waits for this exact change.
        val job = session.scope.launch(start = CoroutineStart.LAZY) {
            try {
                // History closes its sheet as soon as this accepted change is queued. That disposal must not revoke it.
                if (!viewCurrent || !readiness.installedAi(capabilities()).canAsk) return@launch
                var succeeded = true
                try {
                    if (id == null) session.repo.clearChat(owner.key) else session.repo.restoreChat(owner.key, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    succeeded = false
                    if (viewCurrent) ui.chatError = failure.message ?: "Request failed"
                }
                currentCoroutineContext().ensureActive()
                val reloaded = reloadForChange() ?: return@launch
                if (!reloaded && viewCurrent && ui.chatError == null) ui.chatError = "Couldn't load that chat"
                currentCoroutineContext().ensureActive()
                if (!viewCurrent || !readiness.ai(capabilities()).canReadResults) return@launch
                if (state.change === currentCoroutineContext()[Job]) state.changed = succeeded && reloaded
                session.loadChatHistory(force = true)
                if (id != null) onRestored()
            } finally {
                if (viewCurrent && session.chatChange === currentCoroutineContext()[Job]) session.chatChange = null
            }
        }
        session.chatChange = job
        state.change = job
        state.changed = false
        job.start()
        return true
    }

    fun close() { disposed = true }
}

@Composable
internal fun rememberChatActions(session: ReviewSession): ChatActions {
    val latest by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val owner = session.captureView()
    val actions = remember(session, owner, readiness) { ChatActions(session, owner, readiness) { latest } }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
