package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.listedPull
import com.octaviantocan.pulls.core.mentionedPulls
import com.octaviantocan.pulls.data.AskTurn
import com.octaviantocan.pulls.ui.ai.AiActivityLogFull
import com.octaviantocan.pulls.ui.ai.AiLiveMark
import com.octaviantocan.pulls.ui.ai.Answer
import com.octaviantocan.pulls.ui.ai.AnswerExtras
import com.octaviantocan.pulls.ui.ai.AnswerSource
import com.octaviantocan.pulls.ui.ai.AskField
import com.octaviantocan.pulls.ui.ai.UserBubble
import com.octaviantocan.pulls.ui.ai.answerSoFar
import com.octaviantocan.pulls.ui.ai.pullReads
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullMentions
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PullMentions
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.t

/** What a first question about the inbox can be, as chips while the conversation is empty. */
private val INBOX_STARTERS = listOf("What is waiting on me?", "Which pull requests have failing checks?", "What did I merge this week?")

/**
 * The Ask sheet of Home: the same conversation as Ask on a pull request (rule 99), about the whole inbox. Your questions
 * in the accent bubble, the answers as plain text, the work log while one is written, and under an answer the pull
 * requests it names as chips, copy, redo and the questions to ask next. A pull request an answer names is a link that
 * opens it when [listed] has it or the server saw it in the list the answer was written from; any other name, one the
 * model made up included, stays plain text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InboxAskSheet(ask: InboxAsk, listed: List<ListedPull>, onOpen: (ListedPull) -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val mount = rememberInboxAskMount(ask)
    val actions = rememberInboxAskActions(ask, mount)
    if (!mount.canRead) return
    val platformUri = LocalUriHandler.current
    val guardedUri = remember(actions, platformUri) { object : UriHandler {
        override fun openUri(uri: String) { actions.read { platformUri.openUri(uri) } }
    } }
    val turns = ask.chat?.turns.orEmpty()
    val known = remember(listed, turns) { (listed + turns.flatMap { it.pulls }).distinct() }
    val task = ask.task
    val streaming = task?.running == true
    val busy = ask.busy
    val pending = ask.pending
    // The turn this screen follows sits apart, so its log and orb stay at one place in the code while it runs and settles.
    val followed = if (pending == null && task != null) turns.indexOfLast { it.taskId == task.id } else -1
    val split = if (followed >= 0) followed else turns.size
    val current = turns.getOrNull(split)
    val lastIndex = if (pending != null) -1 else turns.lastIndex
    // The answer as it is written (rule 99), until the saved one takes its place. Not while a question waits to start:
    // the task is still the last one then.
    val written = task?.takeIf { pending == null && (it.running || it.status == "done") }?.let { answerSoFar(it.activities) }?.takeIf { current?.answer == null }
    val scroll = rememberScrollState()
    LaunchedEffect(turns.size, pending, task?.activities?.size, streaming, written?.length) { scroll.animateScrollTo(scroll.maxValue) }
    val mentions = remember(known, onOpen, actions) {
        PullMentions(resolve = { owner, repo, number -> listedPull(owner, repo, number, known) != null }) { owner, repo, number -> actions.read { listedPull(owner, repo, number, known)?.let(onOpen) } }
    }

    @Composable
    fun TurnAnswer(turn: AskTurn, index: Int) {
        val isLast = index == lastIndex
        val answer = turn.answer
        val failure = turn.error ?: task?.takeIf { turn.taskId == it.id && it.status == "failed" }?.error
        // The steps of the turn: saved with it, or still on the task that wrote it.
        val steps = turn.activities.ifEmpty { task?.takeIf { turn.taskId == it.id }?.activities.orEmpty() }
        when {
            answer != null -> {
                Answer(answer)
                // The pull requests it names, then the ones it read and does not name (rule 99), each once.
                val named = remember(answer, known, steps) { (mentionedPulls(answer, known) + mentionedPulls(pullReads(steps), known)).distinct() }
                AnswerExtras(
                    answer,
                    named.map { pull -> AnswerSource("${pull.repo}#${pull.number}", "Open ${pull.owner}/${pull.repo}#${pull.number}") { actions.read { onOpen(pull) } } },
                    onRedo = if (isLast && !busy && actions.canAsk) ({ actions.retry(turn) }) else null,
                    next = if (isLast && !busy && actions.canAsk) ask.chat?.suggestions.orEmpty() else emptyList(),
                    onAsk = actions::ask, copyAnswer = actions::copy,
                )
            }
            turn.outcome == "stopped" -> Text(turn.error ?: "Stopped.", style = PullsType.sm, color = c.muted)
            failure != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(failure, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.error)
                if (isLast && !busy && actions.canAsk) FloatIcon(Lu.redo, "Ask again", { actions.retry(turn) }, flat = true, iconSize = 22.dp, tint = c.muted)
            }
            // Written, and on its way to being saved: the words stay where they are until the saved answer takes their place.
            written != null && turn.taskId == task?.id -> Answer(written)
        }
    }

    @Composable
    fun SavedTurn(turn: AskTurn, index: Int) {
        UserBubble(turn.question)
        if (turn.activities.isNotEmpty()) AiActivityLogFull(turn.activities, false, startedAt = turn.startedAt, outcome = turn.outcome ?: "done", compact = true)
        TurnAnswer(turn, index)
    }

    CompositionLocalProvider(LocalPullMentions provides mentions, LocalUriHandler provides guardedUri) {
        PullsSheet(
            onClose = { actions.read(onClose) },
            title = "Ask about your pull requests",
            full = true,
            scroll = scroll,
            trailing = if (turns.isNotEmpty() && !busy && actions.canAsk) ({ FloatIcon(Lu.newChat, "New chat", actions::clear) }) else null,
            footer = if (mount.canAsk) ({
                AskField(
                    ask.input, actions::input,
                    placeholder = if (turns.isEmpty()) "Ask about your pull requests…" else "Ask a follow-up…",
                    onSend = actions::send,
                    sendEnabled = actions.canAsk && ask.accepting && ask.input.isNotBlank(),
                    onStop = if (streaming && actions.canAsk) ({ actions.stop(task) }) else null,
                )
            }) else null,
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                turns.take(split).forEachIndexed { index, turn -> SavedTurn(turn, index) }
                val asked = pending ?: current?.question ?: task?.takeIf { busy }?.text
                if (asked != null) UserBubble(asked)
                // From the moment the question is sent: "Thinking" and the time, then each step with its icon (rule 72). When the
                // answer is in, the same log folds to "Worked for…", so what is under it glides up instead of jumping (rule 69).
                val sentAt = ask.pendingStartedAt ?: task?.startedAt ?: ask.nowMillis()
                val steps = if (busy) task?.takeIf { streaming }?.activities.orEmpty() else current?.activities?.ifEmpty { task?.activities.orEmpty() }.orEmpty()
                if (busy || steps.isNotEmpty()) key(if (pending != null) "pending" else task?.id) {
                    AiActivityLogFull(
                        steps, busy,
                        startedAt = if (busy) (task?.takeIf { streaming }?.startedAt?.takeIf { it > 0 } ?: current?.startedAt ?: sentAt) else (current?.startedAt ?: task?.startedAt),
                        endedAt = if (busy) null else task?.endedAt,
                        outcome = current?.outcome ?: if (task?.status == "failed") "failed" else "done",
                        compact = true, mark = false,
                    )
                }
                // The orb alone, no grey bars under it: the answer arrives where it goes (rule 71, L33).
                AiLiveMark(busy)
                // The answer as it is written, plain text in the place the saved one takes (rule 99).
                if (busy && written != null) Answer(written)
                if (current != null && !busy) TurnAnswer(current, split)
                turns.drop(split + 1).forEachIndexed { offset, turn -> SavedTurn(turn, split + 1 + offset) }
                ask.error?.takeUnless { it == ask.observationError }?.let { Text(it, style = PullsType.sm, color = c.error) }
                ask.observationError?.let { error ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error.ifBlank { t("ai.reconnecting") }, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.error)
                        FloatIcon(Lu.redo, t("common.retry"), actions::reconnect, flat = true, iconSize = 22.dp, tint = c.muted)
                    }
                }
            }
            if (ask.chat != null && turns.isEmpty() && pending == null && actions.canAsk) {
                FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    INBOX_STARTERS.forEach { starter -> SoftChip(starter, selected = false, onClick = { actions.ask(starter) }) }
                }
            }
        }
    }
}
