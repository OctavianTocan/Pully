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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.mentionedPulls
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.AskTurn
import com.octaviantocan.pulls.data.InboxChat
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.data.asTaskState
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.ui.ai.AiActivityLogFull
import com.octaviantocan.pulls.ui.ai.AiLiveMark
import com.octaviantocan.pulls.ui.ai.Answer
import com.octaviantocan.pulls.ui.ai.AnswerExtras
import com.octaviantocan.pulls.ui.ai.AnswerSource
import com.octaviantocan.pulls.ui.ai.AskField
import com.octaviantocan.pulls.ui.ai.UserBubble
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullMentions
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PullMentions
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SoftChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** What a first question about the inbox can be, as chips while the conversation is empty. */
private val INBOX_STARTERS = listOf("What is waiting on me?", "Which pull requests have failing checks?", "What did I merge this week?")

/**
 * Ask from Home: one conversation about the reader's pull requests as a whole, kept on the server. The server answers
 * from the list the inbox holds and reads any one of those pull requests further when the question needs it.
 * [settings] gives the language and the repositories switched off in Profile, which the question leaves out.
 */
@Stable
internal class InboxAsk(private val repo: Repo, private val scope: CoroutineScope, private val settings: AppSettings) {
    /** The saved conversation; `null` until it has loaded once. */
    var chat by mutableStateOf<InboxChat?>(null)
        internal set

    /** The task answering the last question this screen sent or found running; it stays once it settles, for its log. */
    var task by mutableStateOf<TaskState?>(null)
        internal set

    /** A question sent that the saved conversation does not have yet. */
    var pending by mutableStateOf<String?>(null)
        internal set

    /** Why the last request failed before the server took it. */
    var error by mutableStateOf<String?>(null)
        private set

    var input by mutableStateOf("")

    private var follower: Job? = null

    /** A question is on its way: sent, or answered as we speak. */
    val busy: Boolean get() = pending != null || task?.running == true

    /** Loads the conversation and follows an answer already on its way (asked before the app left Home, or elsewhere). */
    fun load() {
        scope.launch {
            refresh()
            if (busy) return@launch
            val live = runCatching { repo.allTasks() }.getOrNull()?.firstOrNull { it.kind == "inbox" && it.running } ?: return@launch
            task = live
            follow(live.id)
        }
    }

    private suspend fun refresh() {
        try {
            chat = repo.inboxChat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A conversation already on screen stays readable; only an empty sheet says why it is empty.
            if (chat == null) error = e.message ?: "Could not load the conversation"
        }
    }

    /** Asks [question]; with [retry] it answers the last question again in place of its old answer. */
    fun ask(question: String, retry: Boolean = false) {
        val text = question.trim()
        if (text.isEmpty() || busy) return
        error = null
        pending = text
        // The old answer steps aside at once, with the questions offered under it.
        if (retry) chat = chat?.let { it.copy(turns = it.turns.dropLast(1), suggestions = emptyList()) }
        else chat = chat?.copy(suggestions = emptyList())
        val sentAt = System.currentTimeMillis()
        scope.launch {
            val id = try {
                repo.startTask("inbox", "inbox") {
                    put("locale", settings.locale)
                    put("question", text)
                    if (retry) put("retry", true)
                    put("skip", JsonArray(settings.hiddenRepos.sorted().map(::JsonPrimitive)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending = null
                error = e.message ?: "Request failed"
                Sounds.play(Cue.Error, Emphasis.Subtle)
                refresh()
                return@launch
            }
            task = TaskState(id = id, kind = "inbox", key = "inbox", startedAt = sentAt, text = text)
            // The server saved the question with the task, so the conversation now has it. When an answer was already on its
            // way the server hands that one back instead, and the question goes back in the field to send after it.
            refresh()
            if (chat?.turns?.lastOrNull()?.question != text && input.isBlank()) input = text
            pending = null
            follow(id)
        }
    }

    private fun follow(id: String) {
        follower?.cancel()
        follower = scope.launch {
            while (true) {
                val current = task?.takeIf { it.id == id } ?: return@launch
                val next = try {
                    repo.waitTask(id, current.rev)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    task = current.copy(status = "failed", error = e.message)
                    break
                }
                // The first snapshot fills in what a task started here does not know yet (its label, its model).
                task = if (current.label.isEmpty()) next.asTaskState().copy(activities = current.merge(next).activities) else current.merge(next)
                if (task?.running != true) break
            }
            refresh()
            when (task?.status) {
                "failed" -> Sounds.play(Cue.Error, Emphasis.Subtle)
                "done" -> Sounds.play(Cue.Ready, Emphasis.Subtle)
            }
        }
    }

    fun stop() {
        val id = task?.takeIf { it.running }?.id ?: return
        scope.launch { repo.cancelTask(id) }
    }

    /** Starts afresh; an answer still on its way stops. */
    fun clear() {
        follower?.cancel()
        task = null
        pending = null
        error = null
        chat = InboxChat(emptyList(), emptyList())
        scope.launch {
            try {
                repo.clearInboxChat()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not start a new chat"
            }
            refresh()
        }
    }
}

/**
 * The Ask sheet of Home: the same conversation as Ask on a pull request (rule 99), about the whole inbox. Your questions
 * in the accent bubble, the answers as plain text, the work log while one is written, and under an answer the pull
 * requests it names as chips, copy, redo and the questions to ask next. A pull request an answer names is a link that
 * opens it, when [resolve] knows it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InboxAskSheet(ask: InboxAsk, resolve: (owner: String, repo: String, number: Int) -> ListedPull?, onOpen: (ListedPull) -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    LaunchedEffect(ask) { ask.load() }
    val turns = ask.chat?.turns.orEmpty()
    val task = ask.task
    val streaming = task?.running == true
    val busy = ask.busy
    val pending = ask.pending
    // The turn this screen follows sits apart, so its log and orb stay at one place in the code while it runs and settles.
    val followed = if (pending == null && task != null) turns.indexOfLast { it.taskId == task.id } else -1
    val split = if (followed >= 0) followed else turns.size
    val current = turns.getOrNull(split)
    val lastIndex = if (pending != null) -1 else turns.lastIndex
    val scroll = rememberScrollState()
    LaunchedEffect(turns.size, pending, task?.activities?.size, streaming) { scroll.animateScrollTo(scroll.maxValue) }
    val mentions = remember(resolve, onOpen) {
        PullMentions(resolve = { owner, repo, number -> resolve(owner, repo, number) != null }) { owner, repo, number -> resolve(owner, repo, number)?.let(onOpen) }
    }

    @Composable
    fun TurnAnswer(turn: AskTurn, index: Int) {
        val isLast = index == lastIndex
        val answer = turn.answer
        val failure = turn.error ?: task?.takeIf { turn.taskId == it.id && it.status == "failed" }?.error
        when {
            answer != null -> {
                Answer(answer)
                val named = remember(answer, resolve) { mentionedPulls(answer, resolve) }
                AnswerExtras(
                    answer,
                    named.map { pull -> AnswerSource("${pull.repo}#${pull.number}", "Open ${pull.owner}/${pull.repo}#${pull.number}") { onOpen(pull) } },
                    onRedo = if (isLast && !busy) ({ ask.ask(turn.question, retry = true) }) else null,
                    next = if (isLast && !busy) ask.chat?.suggestions.orEmpty() else emptyList(),
                    onAsk = { ask.ask(it) },
                )
            }
            turn.outcome == "stopped" -> Text(turn.error ?: "Stopped.", style = PullsType.sm, color = c.muted)
            failure != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(failure, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.error)
                if (isLast && !busy) FloatIcon(Lu.redo, "Ask again", { ask.ask(turn.question, retry = true) }, flat = true, iconSize = 22.dp, tint = c.muted)
            }
        }
    }

    @Composable
    fun SavedTurn(turn: AskTurn, index: Int) {
        UserBubble(turn.question)
        if (turn.activities.isNotEmpty()) AiActivityLogFull(turn.activities, false, startedAt = turn.startedAt, outcome = turn.outcome ?: "done", compact = true)
        TurnAnswer(turn, index)
    }

    CompositionLocalProvider(LocalPullMentions provides mentions) {
        PullsSheet(
            onClose = onClose,
            title = "Ask about your pull requests",
            full = true,
            scroll = scroll,
            trailing = if (turns.isNotEmpty() && !busy) ({ FloatIcon(Lu.newChat, "New chat", { ask.clear() }) }) else null,
            footer = {
                AskField(
                    ask.input, { ask.input = it },
                    placeholder = if (turns.isEmpty()) "Ask about your pull requests…" else "Ask a follow-up…",
                    onSend = { val text = ask.input; if (text.isNotBlank() && !busy) { ask.input = ""; ask.ask(text) } },
                    sendEnabled = !busy && ask.input.isNotBlank(),
                    onStop = if (streaming) ({ ask.stop() }) else null,
                )
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                turns.take(split).forEachIndexed { index, turn -> SavedTurn(turn, index) }
                val asked = pending ?: current?.question ?: task?.takeIf { busy }?.text
                if (asked != null) UserBubble(asked)
                // From the moment the question is sent: "Thinking" and the time, then each step with its icon (rule 72).
                val sentAt = remember(pending) { System.currentTimeMillis() }
                if (busy) {
                    val live = task?.takeIf { streaming }
                    AiActivityLogFull(live?.activities.orEmpty(), true, startedAt = live?.startedAt?.takeIf { it > 0 } ?: current?.startedAt ?: sentAt, compact = true, mark = false)
                } else if (current != null) {
                    val activities = current.activities.ifEmpty { task?.activities.orEmpty() }
                    val outcome = current.outcome ?: if (task?.status == "failed") "failed" else "done"
                    if (activities.isNotEmpty()) AiActivityLogFull(activities, false, startedAt = current.startedAt ?: task?.startedAt, endedAt = task?.endedAt, outcome = outcome, compact = true)
                }
                AiLiveMark(busy, hint = true)
                if (current != null && !busy) TurnAnswer(current, split)
                turns.drop(split + 1).forEachIndexed { offset, turn -> SavedTurn(turn, split + 1 + offset) }
                ask.error?.let { Text(it, style = PullsType.sm, color = c.error) }
            }
            if (ask.chat != null && turns.isEmpty() && pending == null) {
                FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    INBOX_STARTERS.forEach { starter -> SoftChip(starter, selected = false, onClick = { ask.ask(starter) }) }
                }
            }
        }
    }
}
