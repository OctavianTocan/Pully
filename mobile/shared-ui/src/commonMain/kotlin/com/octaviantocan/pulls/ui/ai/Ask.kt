package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.AskThread
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PillInput
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SendButton
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.LocalReviewActionReadiness
import com.octaviantocan.pulls.ui.review.guardedStop

/** The thread's input: a pill with the question and Send inside. */
@Composable
private fun AskComposer(session: ReviewSession, owner: ReviewSession.Version?, threadId: String, placeholder: String, autofocus: Boolean, onSend: (String) -> Unit) {
    val c = LocalPullsColors.current
    val state = rememberAskComposerState(session, owner, threadId)
    val readiness = LocalReviewActionReadiness.current
    val capabilities by rememberUpdatedState(LocalAiCapabilities.current)
    fun current(): Boolean = owner != null && session.isCurrentView(owner) && readiness.ai(capabilities).canAsk
    val question = state.question
    val focus = remember(session, owner, threadId) { FocusRequester() }
    LaunchedEffect(session, owner, threadId, autofocus) { if (autofocus && owner != null && session.isCurrentView(owner)) runCatching { focus.requestFocus() } }
    fun send() {
        if (!current()) return
        val text = state.question.trim()
        if (text.isNotEmpty()) { state.question = ""; onSend(text) }
    }
    PillInput(question, { if (current()) state.question = it }, placeholder, Modifier.padding(top = 4.dp).focusRequester(focus), onSend = ::send) {
        SendButton({ send() }, "Send", enabled = question.isNotBlank())
    }
}

/**
 * Port of `AskThread.vue`: which lines it is about, the turns (question, live log, answer or error), the follow-up field
 * and Close. It reads like the Ask sheet (rule 99): your question is the accent bubble on the right and the reply is
 * plain text under it, with no card, fill or shadow around either. The thread sits under its lines, so it names only
 * their numbers, never the path again.
 */
@Composable
fun AskThreadCard(session: ReviewSession, thread: AskThread, modifier: Modifier = Modifier) {
    val capabilities by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    if (!capabilities.canReadResults) return
    val owner = session.captureView()
    val c = LocalPullsColors.current
    val ui = askUi(session)
    val last = thread.turns.lastOrNull()
    val lastTask = last?.taskId?.let { session.tasks[it] }
    // The saved turn has no outcome while its task runs; the followed task has the live log.
    val pending = ui.askPending[thread.id]?.takeIf { (_, before) -> thread.turns.size <= before }
    LaunchedEffect(session, owner, thread.id, thread.turns.size) { if (owner != null && session.isCurrentView(owner)) ui.askPending[thread.id]?.let { (_, before) -> if (thread.turns.size > before) ui.askPending.remove(thread.id) } }
    var followError by remember(session, owner, thread.id) { mutableStateOf<String?>(null) }
    val asking = pending != null || (last != null && last.outcome == null && (lastTask == null || lastTask.running))
    Column(modifier.widthIn(max = 800.dp).fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(linesPhrase(thread.startLine, thread.endLine, label = true), Modifier.weight(1f), style = PullsType.sm, color = c.muted)
            if (capabilities.canAsk) FloatIcon(Lu.close, t("ask.close"), {
                session.closeAskThread(thread.id, lastTask?.id, owner, capabilities, readiness)
            }, flat = true, size = 40.dp, tint = c.muted)
        }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            thread.turns.forEachIndexed { index, turn ->
                val isLast = index == thread.turns.lastIndex
                val task = turn.taskId?.let { session.tasks[it] }
                val running = turn.outcome == null && (task == null || task.running) && isLast
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserBubble(turn.question)
                    if (turn.startedAt != null) {
                        // The log's orb is the Stop button; there is no Stop pill beside it (rule 87).
                        val activities = if (running && task != null) task.activities else turn.activities.ifEmpty { task?.activities.orEmpty() }
                        val outcome = turn.outcome ?: if (task != null && !task.running) outcomeOf(task) else "done"
                        val stop = guardedStop(session, listOf("ask"), capabilities.canAsk && running && task != null) { if (capabilities.canAsk && owner != null && session.isCurrentView(owner)) task?.let { session.cancelTask(it.id) } }
                        AiActivityLogFull(activities, running, Modifier.fillMaxWidth(), startedAt = turn.startedAt, endedAt = task?.endedAt, outcome = outcome, compact = true, onStop = stop, reconnecting = task?.id in session.taskObservationErrors)
                    }
                    val answer = turn.answer ?: task?.result?.takeIf { task.status == "done" }
                    val error = turn.error ?: task?.takeIf { it.status == "failed" || it.status == "cancelled" }?.let { if (it.status == "cancelled") t("ask.stopped") else it.error }
                    when {
                        // An answer in the reading size with no box (rule 99), its mentions links (rule 96).
                        answer != null -> AiMarkdown(session, answer, Modifier.fillMaxWidth(), style = PullsType.read.copy(color = c.text))
                        error != null -> Text(error, style = PullsType.sm, color = c.error)
                    }
                }
            }
            if (pending != null) {
                val sentAt = remember(session, owner, pending.first) { session.wallClockMillis() }
                UserBubble(pending.first)
                AiActivityLogFull(emptyList(), running = true, startedAt = sentAt, compact = true)
            }
            if (!asking && capabilities.canAsk) {
                followError?.let { Text(it, style = PullsType.sm, color = c.error) }
                AskComposer(session, owner, thread.id, placeholder = if (thread.turns.isNotEmpty()) t("ask.followUpPlaceholder") else t("ask.placeholder"), autofocus = thread.turns.isEmpty()) { question ->
                    followError = null
                    session.ask(thread.id, AskSelection(thread.path, thread.side, thread.startLine, thread.endLine), question, turnsBefore = thread.turns.size, owner = owner, capabilities = capabilities, readiness = readiness, onStartError = { followError = it })
                }
            }
        }
    }
}
