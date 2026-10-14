package com.octaviantocan.pulls.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.data.AskThread
import com.octaviantocan.pulls.ui.design.FloatButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PillInput
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SendButton
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Sends [question] on thread [threadId] about [selection] (a new thread when the server has none by that id). */
internal fun ReviewSession.ask(threadId: String, selection: AskSelection, question: String, turnsBefore: Int, afterStart: () -> Unit = {}, onStartError: (String) -> Unit = {}) {
    val text = question.trim()
    if (text.isEmpty()) return
    val ui = aiUi(this)
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

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("pulls.review", text))
}

/** Port of `SelectionActions.vue`, with the new `AskThread` composer it opens on the phone. */
@Composable
internal fun SelectionActionsImpl(session: ReviewSession, path: String, side: String, startLine: Int, endLine: Int, onDone: () -> Unit, modifier: Modifier) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val selection = AskSelection(path, side, minOf(startLine, endLine), maxOf(startLine, endLine))
    val label = lineRangeLabel(selection)
    val opened = session.opened
    val repo = FileLinks.repoOf(session.target)
    val file = opened?.files?.firstOrNull { it.path == path }
    val link = if (opened != null) githubBlobUrl(repo?.first, repo?.second, opened.refKind, opened.headSha, opened.baseSha, path, file?.previousPath, selection) else null
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(1500); copied = null } }
    var composing by remember(label) { mutableStateOf(false) }
    var sentQuestion by remember(label) { mutableStateOf<String?>(null) }
    var startError by remember(label) { mutableStateOf<String?>(null) }
    val available = cliEngine(context) && session.key != null

    fun askAi() {
        // Asking again about the same lines goes to the thread already open on them.
        val existing = session.saved?.asks?.firstOrNull { it.path == path && it.side == side && it.startLine == selection.startLine && it.endLine == selection.endLine }
        if (existing != null) onDone() else composing = true
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, Modifier.weight(1f), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted)
            if (available && !composing) PillButton(t("ask.action"), ::askAi)
            FloatIcon(if (copied == "label") Lu.check else Lu.copy, if (copied == "label") t("ask.copied") else t("ask.copyLocation"), { copyText(context, label); copied = "label" }, flat = true, size = 40.dp, tint = c.muted)
            if (link != null) FloatIcon(if (copied == "link") Lu.check else Lu.ext, if (copied == "link") t("ask.copied") else t("ask.copyLink"), { copyText(context, link); copied = "link" }, flat = true, size = 40.dp, tint = c.muted)
            FloatIcon(Lu.close, t("common.close"), onDone, size = 40.dp)
        }
        if (composing) {
            startError?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = PullsType.sm, color = c.error) }
            val sent = sentQuestion
            if (sent != null) {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserBubble(sent)
                    val askedAt = remember(sent) { System.currentTimeMillis() }
                    Row(Modifier.padding(horizontal = 4.dp)) { AiActivityLogFull(emptyList(), running = true, startedAt = askedAt, compact = true) }
                }
            } else AskComposer(placeholder = t("ask.placeholder"), autofocus = true) { question ->
                sentQuestion = question
                // The thread shows under its last line once the server has saved it; this composer then closes.
                startError = null
                session.ask(newAskThreadId(), selection, question, turnsBefore = 0, afterStart = onDone, onStartError = { startError = it; sentQuestion = null })
            }
        }
    }
}

/** The thread's input: a pill with the question and Send inside. */
@Composable
private fun AskComposer(placeholder: String, autofocus: Boolean, onSend: (String) -> Unit) {
    val c = LocalPullsColors.current
    var question by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autofocus) { if (autofocus) runCatching { focus.requestFocus() } }
    fun send() {
        val text = question.trim()
        if (text.isNotEmpty()) { question = ""; onSend(text) }
    }
    PillInput(question, { question = it }, placeholder, Modifier.padding(8.dp).focusRequester(focus), onSend = ::send) {
        SendButton({ send() }, "Send", enabled = question.isNotBlank())
    }
}

/** Port of `AskThread.vue`: label, turns (question, live log, answer or error, usage), follow-up, Stop, Close. */
@Composable
internal fun AskThreadCardImpl(session: ReviewSession, thread: AskThread, modifier: Modifier) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    val last = thread.turns.lastOrNull()
    val lastTask = last?.taskId?.let { session.tasks[it] }
    // The saved turn has no outcome while its task runs; the followed task has the live log.
    val pending = ui.askPending[thread.id]?.takeIf { (_, before) -> thread.turns.size <= before }
    LaunchedEffect(thread.turns.size) { ui.askPending[thread.id]?.let { (_, before) -> if (thread.turns.size > before) ui.askPending.remove(thread.id) } }
    var followError by remember(thread.id) { mutableStateOf<String?>(null) }
    val asking = pending != null || (last != null && last.outcome == null && (lastTask == null || lastTask.running))
    Column(
        modifier.widthIn(max = 800.dp).fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(c.base).border(1.dp, c.border, RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(thread.label, Modifier.weight(1f), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted)
            FloatIcon(Lu.close, t("ask.close"), {
                val key = session.key
                if (lastTask?.running == true) session.cancelTask(lastTask.id)
                if (key != null) session.scope.launch { runCatching { session.repo.closeAsk(key, thread.id) }; session.reloadSaved() }
            }, flat = true, size = 40.dp, tint = c.muted)
        }
        HorizontalDivider(color = c.border)
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            thread.turns.forEachIndexed { index, turn ->
                val isLast = index == thread.turns.lastIndex
                val task = turn.taskId?.let { session.tasks[it] }
                val running = turn.outcome == null && (task == null || task.running) && isLast
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserBubble(turn.question)
                    if (turn.startedAt != null) {
                        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val activities = if (running && task != null) task.activities else turn.activities.ifEmpty { task?.activities.orEmpty() }
                            val outcome = turn.outcome ?: if (task != null && !task.running) outcomeOf(task) else "done"
                            AiActivityLogFull(activities, running, Modifier.weight(1f), startedAt = turn.startedAt, endedAt = task?.endedAt, outcome = outcome, compact = true)
                            if (running && task != null) PillButton(t("common.stop"), { session.cancelTask(task.id) })
                        }
                    }
                    val answer = turn.answer ?: task?.result?.takeIf { task.status == "done" }
                    val error = turn.error ?: task?.takeIf { it.status == "failed" || it.status == "cancelled" }?.let { if (it.status == "cancelled") t("ask.stopped") else it.error }
                    when {
                        answer != null -> Markdown(answer, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                        error != null -> Text(error, Modifier.padding(horizontal = 4.dp), style = PullsType.xs, color = c.error)
                    }
                    (turn.usage ?: task?.usage)?.let { AiUsageLine(it, Modifier.padding(horizontal = 4.dp)) }
                }
            }
            if (pending != null) {
                val sentAt = remember(pending.first) { System.currentTimeMillis() }
                UserBubble(pending.first)
                Row(Modifier.padding(horizontal = 4.dp)) { AiActivityLogFull(emptyList(), running = true, startedAt = sentAt, compact = true) }
            }
            if (!asking) {
                followError?.let { Text(it, Modifier.padding(horizontal = 4.dp), style = PullsType.xs, color = c.error) }
                AskComposer(placeholder = if (thread.turns.isNotEmpty()) t("ask.followUpPlaceholder") else t("ask.placeholder"), autofocus = thread.turns.isEmpty()) { question ->
                    followError = null
                    session.ask(thread.id, AskSelection(thread.path, thread.side, thread.startLine, thread.endLine), question, turnsBefore = thread.turns.size, onStartError = { followError = it })
                }
            }
        }
    }
}
