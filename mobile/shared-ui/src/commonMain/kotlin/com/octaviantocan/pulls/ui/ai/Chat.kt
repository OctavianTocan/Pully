package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.review.prIsoMillis
import com.octaviantocan.pulls.ui.review.timeAgo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SearchList
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.Role
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.mentionedPlaces
import com.octaviantocan.pulls.ui.design.rememberTextCopy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Activity
import com.octaviantocan.pulls.data.ChatMessage
import com.octaviantocan.pulls.ui.design.FieldFrame
import com.octaviantocan.pulls.ui.design.FieldPlace
import com.octaviantocan.pulls.ui.design.FieldText
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SendButton
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.guardedStop
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import com.octaviantocan.pulls.ui.design.PullsRadius
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private val GROUPING_UPDATED = Regex("Grouping updated: (\\d+) groups?")

/**
 * The prototype's Ask sheet (the old `ChatWidget`, opened from the Ask button instead of a floating one):
 * a full-height chat about this pull request or its tour. [suggestions] are the starter chips of an empty
 * chat and [initial] is a question to send as it opens. [reference] is attached to the next question, shown over the
 * field until it is sent or removed; nothing is sent until the person writes the question. [scroll] is where the
 * conversation is scrolled, kept by the opener; [returning] opens it again where it was rather than at its end, as Back
 * from a place an answer opened does. Both hosts supply the actual source destination through [onOpen].
 */
@Composable
fun AskSheet(session: ReviewSession, title: String, suggestions: List<String>, initial: String?, reference: AskReference? = null, scroll: ScrollState? = null, returning: Boolean = false, onOpen: (CodePlace) -> Unit, onDismiss: () -> Unit) =
    AskSheetImpl(session, title, suggestions, initial, reference, scroll, returning, onOpen, onDismiss)

/**
 * What `ChatMessage.vue` shows of a saved message: the user's text, the assistant's
 * Markdown, and "Updated grouping: n groups" for a grouping tool result. Other tool results
 * (file contents the model read) are not shown, as on the web.
 */
internal sealed interface ChatRow {
    data class User(val text: String) : ChatRow
    data class Assistant(val text: String) : ChatRow
    data class Grouping(val groups: Int) : ChatRow
}

internal fun chatRows(messages: List<ChatMessage>): List<ChatRow> = messages.mapNotNull { message ->
    when (message.role) {
        "user" -> ChatRow.User(message.text)
        "assistant" -> message.text.takeIf { it.isNotBlank() }?.let { ChatRow.Assistant(it) }
        "toolResult" -> GROUPING_UPDATED.find(message.text)?.groupValues?.get(1)?.toIntOrNull()?.let { ChatRow.Grouping(it) }
        else -> null
    }
}

/**
 * The Ask sheet: your questions on the right in the accent bubble, the answers as plain text with no box (rule 99),
 * starter chips while it is empty, a send pill, and a new-chat button at the top right once there is a conversation.
 * While an answer is on its way the work log shows each step like the tour's progress and the send button is the loader,
 * never grey bars or a bubble of dots. It continues the analysis session when there is one and starts from the pull request itself when
 * there is not, so Ask never waits on Analyze. [initial] is asked on open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskSheetImpl(session: ReviewSession, title: String, suggestions: List<String>, initial: String?, reference: AskReference?, keptScroll: ScrollState?, returning: Boolean, onOpen: (CodePlace) -> Unit, onDismiss: () -> Unit) {
    val capabilities = LocalAiCapabilities.current
    if (!capabilities.canReadResults) return
    val c = LocalPullsColors.current
    val actions = rememberChatActions(session)
    val owner = session.captureView()
    val ui = chatUi(session)
    val task = rememberLiveTask(session, "chat")
    val analyzeTask = session.taskFor("analyze")
    val streaming = task?.running == true
    // A chat shares the diff's saved transcript with the analysis, so the two do not run at once.
    val busy = streaming || analyzeTask?.running == true || actions.waiting
    val saved = session.saved?.chat.orEmpty()
    val rows = remember(saved) { chatRows(saved) }
    // The question shows at once; the server saves it with the reply when the turn ends.
    val pending = ui.chatPending?.takeIf { text -> (rows.lastOrNull() as? ChatRow.User)?.text != text }
    val lastQuestion = rows.indexOfLast { it is ChatRow.User }
    // The answer as it is written (rule 99): the newest writing step's text, held once the turn settles until the saved
    // reply takes its place, so the words stay where they are when it swaps. A new question or a new saved reply lets it go.
    val held = remember(session, owner, rows, ui.chatPending) { arrayOf<String?>(null) }
    val writing = task?.takeIf { streaming }?.let { answerSoFar(it.activities) }
    if (writing != null) held[0] = writing
    val written = writing ?: held[0]?.takeIf { task?.status == "done" }
    // A reply on its way, or written and not yet saved.
    val answering = streaming || written != null
    // Redo answers the last question again (a turn with no text of its own): its old answer steps aside meanwhile.
    val redoing = answering && task?.text.isNullOrEmpty() && pending == null && lastQuestion >= 0
    val shown = if (redoing) rows.take(lastQuestion + 1) else rows
    // Where the turn's own log and orb go in the rows: after all of them while it runs, after its question once saved.
    val turnAt = if (answering || pending != null) shown.size else lastQuestion + 1
    // Under the last answer: redo and the questions a small model wrote for it (rule 99).
    val lastAnswer = shown.lastOrNull() as? ChatRow.Assistant
    val nextQuestions = if (lastAnswer != null && !busy && pending == null) session.saved?.suggestions.orEmpty() else emptyList()
    val failed = task?.status == "failed"
    val scroll = keptScroll ?: rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // The conversation sticks to its end only while the reader is at the end, as shadcn's Conversation does (its
    // use-stick-to-bottom): a finger that scrolls up lets go of the end, and new words are written below without pulling
    // the reader down; letting go at the end, or the round button, takes hold of it again ("streaming scrolls you down",
    // recording of 16 Oct 12:10, 11:48 to 12:47, N26; L32). Only the reader's own drags count: the sheet's scrolls never
    // read as the reader's, so a chunk that arrives mid-scroll cannot hand the end back. A sheet opened again where it
    // was is left there.
    val slack = with(density) { 48.dp.toPx() }
    val atEnd by remember(scroll) { derivedStateOf { scroll.value >= scroll.maxValue - slack } }
    var following by remember(session, owner) { mutableStateOf(!returning) }
    LaunchedEffect(session, owner, scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    snapshotFlow { scroll.isScrollInProgress }.first { !it }
                    following = scroll.value >= scroll.maxValue - slack
                }
            }
        }
    }
    LaunchedEffect(session, owner, rows.size, pending, task?.activities?.size, streaming, written?.length) {
        if (following) scroll.animateScrollTo(scroll.maxValue)
    }
    // A chat opened afresh starts at its end, already there rather than scrolling to it.
    LaunchedEffect(session, owner) {
        if (returning) return@LaunchedEffect
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }
    // A question sent goes to the top of the sheet with its answer to come under it, as in a chat app, and the sheet does
    // not follow the answer down: the reader reads from the question (N26). [turnTop] and [contentTop] are where the
    // latest turn and the top of the conversation are on screen; the turn is at least the sheet's height once a question
    // is sent here, so the question can always reach the top.
    var pinTo by remember(session, owner) { mutableIntStateOf(0) }
    val marks = remember(session, owner) { floatArrayOf(0f, 0f) }
    LaunchedEffect(session, owner, pinTo) {
        if (pinTo == 0) return@LaunchedEffect
        // The new question is laid out on the next frame.
        withFrameNanos { }
        withFrameNanos { }
        val gap = with(density) { 8.dp.toPx() }
        scroll.animateScrollTo((marks[0] - marks[1] - gap).roundToInt().coerceIn(0, scroll.maxValue))
    }
    var initialSent by remember(session, initial) { mutableStateOf(false) }
    val initialOwner = remember(session, initial) { owner }
    LaunchedEffect(session, owner, initial, actions.canAsk) {
        if (!initialSent && !initial.isNullOrBlank() && (initialOwner == null || initialOwner == owner) && actions.send(initial)) {
            initialSent = true
            following = false
            pinTo++
        }
    }

    // A file note (or a file) the question is about: it rides on the next question and then goes.
    val referenceOwner = remember(session, reference) { owner }
    var attached by remember(session, owner, reference) { mutableStateOf(reference.takeIf { owner == referenceOwner }) }

    fun ask(text: String): Boolean {
        val question = text.trim()
        if (!actions.send(question, attached)) return false
        following = false
        pinTo++
        attached = null
        return true
    }

    // The chats this pull request had before, on a searchable sheet over this one (L36).
    var history by remember(session, owner) { mutableStateOf(false) }
    LaunchedEffect(session, owner, capabilities.canReadResults) { actions.loadHistory() }
    fun newChat() {
        if (!actions.newChat()) return
        // An empty chat starts from the top, its starting questions in view.
        pinTo = 0
    }

    @Composable
    fun RowView(row: ChatRow) {
        ChatRowView(row)
        if (row is ChatRow.Assistant) AnswerExtras(
            session, row.text, onOpen,
            // The files the turn read are known for the last answer only: the steps of older turns are not kept.
            read = if (row === lastAnswer && !busy) task?.takeIf { it.status == "done" }?.activities.orEmpty() else emptyList(),
            onRedo = if (row === lastAnswer && !busy && actions.canAsk) ({ actions.retry(null); Unit }) else null,
            next = if (row === lastAnswer) nextQuestions else emptyList(),
            onAsk = { ask(it) },
        )
    }

    // Mentions in the answers open their place and code is the diff's code view (rules 95 and 96).
    key(session, owner) {
        ProvideAiText(session, { place -> actions.read { onOpen(place) } }) {
            PullsSheet(
                onClose = onDismiss,
                title = title,
                full = true,
                scroll = scroll,
                // A fresh conversation, in place of a "Clear" button (F69).
                trailing = if (rows.isNotEmpty() && !busy && actions.canAsk && !actions.changing) ({ FloatIcon(Lu.newChat, "New chat", ::newChat) }) else null,
                // Ask opens at its tall height at once, with no keyboard, and may still be dragged down to half (recording of
                // 16 Oct 12:10, 11:37 to 11:47, N25).
                tall = true,
                // Scrolled up from the end, a round button takes the reader back down and the sheet follows the end again (N26).
                aboveFooter = {
                    val motion = rememberMotionEnabled()
                    AnimatedVisibility(
                        !atEnd && scroll.maxValue > 0,
                        enter = if (motion) fadeIn(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + scaleIn(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut), initialScale = 0.8f) else EnterTransition.None,
                        exit = if (motion) fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + scaleOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut), targetScale = 0.8f) else ExitTransition.None,
                    ) {
                        FloatIcon(Lu.chevd, "Scroll to the end", {
                            following = true
                            scope.launch { scroll.animateScrollTo(scroll.maxValue) }
                        }, size = 40.dp, iconSize = 20.dp)
                    }
                },
                footer = if (capabilities.canAsk) ({
                    attached?.let { about -> AttachedReference(about) { attached = null } }
                    AskField(
                        ui.chatInput, actions.changeInput,
                        placeholder = if (rows.isEmpty()) "Ask Pully…" else "Ask a follow-up…",
                        onSend = { if (ask(ui.chatInput)) actions.changeInput("") },
                        sendEnabled = actions.canAsk && !busy && ui.chatInput.isNotBlank(),
                        onStop = guardedStop(session, listOf("chat"), actions.canAsk && streaming && task != null) { task?.let { actions.stop(it.id) } },
                        // With nothing typed the button opens the chats this pull request had (Tavi, L36), and only when it had
                        // some (P4).
                        onHistory = if (actions.canAsk && !busy && !session.chatHistory.isNullOrEmpty()) ({ if (actions.canAsk) history = true }) else null,
                        working = streaming || ui.chatPending != null || actions.waiting,
                    )
                }) else null,
            ) {
                Spacer(Modifier.onGloballyPositioned { marks[1] = it.positionInRoot().y })
                Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (analyzeTask?.running == true && !streaming) Text("The analysis is running. You can ask when it finishes.", style = PullsType.sm, color = c.muted)
                    // The turn sits under its own question: below every saved row while it runs, under the saved
                    // question once the reply is saved, with the answer after it. The orb is drawn once, at one
                    // place in the code, so the orb that ran is the one that settles, where the log was.
                    // The latest turn, from its question on, is one block: the one a question just sent scrolls to the top.
                    val split = if (pending == null && lastQuestion >= 0) lastQuestion else turnAt
                    shown.take(split).forEach { RowView(it) }
                    val room = with(density) { scroll.viewportSize.toDp() } - 14.dp
                    Column(
                        Modifier.fillMaxWidth().then(if (pinTo > 0 && room > 0.dp) Modifier.heightIn(min = room) else Modifier)
                            .onGloballyPositioned { marks[0] = it.positionInRoot().y },
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        shown.subList(split, turnAt).forEach { RowView(it) }
                        if (pending != null) UserBubble(pending)
                        // From the moment the question is sent: "Thinking" and the time, then each step with its icon (rule 72). When the
                        // reply is in, the same log folds to "Worked for…" under the question that asked for it, so what is under it
                        // glides up instead of jumping (rule 69).
                        val working = streaming || ui.chatPending != null || actions.waiting
                        val sentAt = remember(session, owner, ui.chatPending) { session.wallClockMillis() }
                        val turnTask = task?.takeIf { answering } ?: task?.takeIf { !working && lastQuestion >= 0 && it.activities.isNotEmpty() }
                        if (working || turnTask != null) key(turnTask?.id) {
                            // The tour's progress, line for line: "Thinking" and the time over the steps with their icons, and no
                            // grey bars or orb under them; what is running shows in the send button (recording 15 Oct 15:37, 16:09
                            // to 16:37; L31, L33).
                            AiActivityLogFull(
                                turnTask?.activities.orEmpty(), working, startedAt = turnTask?.startedAt?.takeIf { it > 0 } ?: sentAt,
                                endedAt = turnTask?.endedAt, outcome = outcomeOf(turnTask), compact = true, mark = false,
                                reconnecting = turnTask?.id in session.taskObservationErrors,
                            )
                        }
                        // The answer as it is written, in the place the saved reply takes: plain text, never a bubble (rule 99).
                        if (written != null) Answer(written)
                        shown.drop(turnAt).forEach { RowView(it) }
                        if (!streaming && failed && ui.chatPending == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(task?.error ?: t("chat.requestFailed"), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.error)
                                // A failed turn saved nothing, so the question is sent again (web `retry` re-runs an errored reply).
                                if (actions.canAsk) FloatIcon(Lu.redo, "Ask again", { actions.retry(task?.text) }, flat = true, iconSize = 22.dp, tint = c.muted)
                            }
                        } else if (!streaming && task?.status == "cancelled") {
                            Text(t("chat.stopped"), style = PullsType.sm, color = c.muted)
                        }
                        ui.chatError?.takeIf { !failed }?.let { Text(it, style = PullsType.sm, color = c.error) }
                    }
                }
                // An empty chat, the first and every new one alike: the questions to start with as the same rows that follow
                // an answer, in a card, instead of loose chips ("this is not how we do things", 16:58 to 17:12; L35).
                if (actions.canAsk && !busy && rows.isEmpty() && pending == null && !answering && suggestions.isNotEmpty()) {
                    Text("Start with", Modifier.padding(start = 4.dp, top = 6.dp), style = PullsType.sm, color = c.muted)
                    SheetCard(gap = 8.dp) {
                        suggestions.forEachIndexed { index, suggestion ->
                            SheetRow(suggestion, { ask(suggestion) }, first = index == 0, icon = { LuIcon(Lu.followUp, tint = c.muted, size = 18.dp) })
                        }
                    }
                }
            }
        }
    }
    if (history) ChatHistorySheet(session, onClose = { history = false }) { pinTo = 0; following = true; scope.launch { withFrameNanos { }; scroll.scrollTo(scroll.maxValue) } }
}

/**
 * The chats this pull request had, newest first, on the one searchable list a sheet picks from ([SearchList]): each its
 * first question, when it was and how many questions it had. A tap brings that chat back into Ask, and the chat that was
 * open goes into the list in its place (Tavi's idea: recording 15 Oct 15:37, 17:19 to 17:48; L36). From an Ask bar it
 * opens alone, and [onRestored] opens Ask on the chat picked ("it's just the earlier chats that need to open", recording
 * of 17 Oct 13:26, 01:45 to 01:57, P5).
 */
@Composable
fun ChatHistorySheet(session: ReviewSession, onClose: () -> Unit, onRestored: () -> Unit) {
    val actions = rememberChatActions(session)
    if (!actions.canRead) return
    val c = LocalPullsColors.current
    if (session.key == null) return
    val owner = session.captureView()
    LaunchedEffect(session, owner) { actions.loadHistory(force = true) }
    PullsSheet(onClose, "Chats") {
        val list = session.chatHistory
        session.chatHistoryError?.let { problem ->
            ErrorBox("Earlier chats could not load", problem, onRetry = { actions.loadHistory(force = true) })
        }
        when {
            list == null && session.chatHistoryError == null -> RowsSkeleton(Modifier.padding(top = 12.dp), rows = 3)
            list == null -> Unit
            list.isEmpty() -> Text("No earlier chats on this pull request.", Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
            else -> SearchList(list, text = { it.title }, placeholder = "Find a chat", empty = "No chat matches") { chat, first ->
                val ago = prIsoMillis(chat.at)?.let { timeAgo(it) }
                val asked = if (chat.turns == 1) "1 question" else "${chat.turns} questions"
                SheetRow(chat.title.ifBlank { "Chat" }, {
                    if (actions.restore(chat.id, onRestored)) onClose()
                }, first = first, iconless = true, enabled = actions.canAsk && !actions.changing, detail = listOfNotNull(ago, asked).joinToString(" · "))
            }
        }
    }
}

/** What the next question is about, over the field: its name, its text cut to three lines, and ✕ to leave it out. */
@Composable
private fun AttachedReference(about: AskReference, onRemove: () -> Unit) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(PullsRadius.xl)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(c.raised).border(1.dp, c.borderMute, shape).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(about.label, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            Text(about.text, style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.4f), color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        FloatIcon(Lu.close, "Leave it out", onRemove, flat = true, size = 40.dp, iconSize = 18.dp, tint = c.muted)
    }
}

/** An answer: plain text in the reading size, with no box, fill or shadow (rule 99). */
@Composable
fun Answer(text: String) {
    val c = LocalPullsColors.current
    Markdown(text, Modifier.fillMaxWidth(), style = PullsType.read.copy(color = c.text))
}

/**
 * Under a PR answer: the places in the diff it mentions, then the files it [read] that it does not mention (rule 99), each
 * once, then what every answer has under it.
 */
@Composable
private fun AnswerExtras(session: ReviewSession, text: String, onOpen: (CodePlace) -> Unit, read: List<Activity>, onRedo: (() -> Unit)?, next: List<String>, onAsk: (String) -> Unit) {
    val actions = rememberChatActions(session)
    val copy = rememberTextCopy()
    val places = remember(text, read, session.files) {
        val named = mentionedPlaces(text, { name -> resolveMention(session, name) }, reviewRepo(session)) { name -> codeNameOf(session, name) }
        val opened = readFiles(read).map { (path, line) -> CodePlace(resolveMention(session, path) ?: path, line) }
        named + opened.filter { place -> named.none { it.path == place.path } }.distinctBy { it.path }
    }
    val sources = places.map { place ->
        AnswerSource(place.path.substringAfterLast('/') + if (place.line > 0) ":${place.line}" else "", "Open ${place.path}") { actions.read { onOpen(place) } }
    }
    AnswerExtras(text, sources, onRedo, if (actions.canAsk) next else emptyList(), onAsk, copyAnswer = { value -> actions.read { copy(value) } })
}

/** Something an answer mentions, as a chip under it: its [label], what a tap does for a screen reader, and [open]. */
class AnswerSource(val label: String, val clickLabel: String, val open: () -> Unit)

/**
 * Under an answer (rule 99): what it mentions as chips that open them, copy and, on the last
 * answer, redo, then the questions to ask next as rows that send with a tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerExtras(text: String, sources: List<AnswerSource>, onRedo: (() -> Unit)?, next: List<String>, onAsk: (String) -> Unit, copyAnswer: ((String) -> Boolean)? = null) {
    val c = LocalPullsColors.current
    val copy = rememberTextCopy()
    var copied by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (sources.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEach { source -> SourceChip(source) }
        }
        Row(Modifier.offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
            FloatIcon(if (copied) Lu.check else Lu.copy, if (copied) "Copied" else "Copy the answer", {
                val plain = plainAnswer(text)
                if (copyAnswer != null) copied = copyAnswer(plain) else { copy(plain); copied = true }
            }, flat = true, size = 40.dp, iconSize = 18.dp, tint = c.muted)
            if (onRedo != null) FloatIcon(Lu.redo, "Answer again", onRedo, flat = true, size = 40.dp, iconSize = 18.dp, tint = c.muted)
        }
        if (next.isNotEmpty()) Column(Modifier.fillMaxWidth()) {
            next.forEachIndexed { index, question ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderMute))
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).cueClickable(role = Role.Button, onClickLabel = "Ask") { onAsk(question) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LuIcon(Lu.followUp, Modifier.padding(top = 2.dp), tint = c.muted, size = 16.dp)
                    Text(question, Modifier.weight(1f), style = PullsType.sm, color = c.text)
                }
            }
        }
    }
}

/** Something an answer mentions, on the kit's outlined 36dp chip (rule 70): a file and its line, or a pull request. */
@Composable
private fun SourceChip(source: AnswerSource) {
    val c = LocalPullsColors.current
    val shape = CircleShape
    Box(
        Modifier.height(36.dp).clip(shape).border(1.dp, c.border, shape)
            .cueClickable(role = Role.Button, onClickLabel = source.clickLabel, onClick = source.open).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(source.label, style = PullsType.sm, color = c.text, maxLines = 1)
    }
}

/**
 * Ask's field, at the foot of its sheet: a 52dp pill with the question and Send; the turning orb, which stops it, while
 * an answer is on its way; and with nothing typed, [onHistory]'s earlier chats. The keyboard's action sends too.
 */
@Composable
fun AskField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    onStop: (() -> Unit)?,
    onHistory: (() -> Unit)? = null,
    working: Boolean = false,
) {
    val c = LocalPullsColors.current
    FieldFrame(FieldPlace.Page, button = true) {
        FieldText(
            value = value,
            onChange = onValueChange,
            placeholder = placeholder,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (sendEnabled) onSend() }),
            modifier = Modifier.weight(1f),
        )
        when {
            // The loader is the button: while an answer is on its way the orb turns where Send was, and a tap stops it (L33).
            onStop != null -> StopOrb(onStop, Modifier.size(40.dp))
            working -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Orb(size = 22.dp) }
            value.isBlank() && onHistory != null -> FloatIcon(Lu.history, "Earlier chats", onHistory, size = 40.dp, iconSize = 20.dp, flat = true, tint = c.muted)
            else -> SendButton(onSend, t("common.send"), enabled = sendEnabled)
        }
    }
}

/** Port of `ChatMessage.vue` for the saved (text-only) messages. */
@Composable
private fun ChatRowView(row: ChatRow) {
    val c = LocalPullsColors.current
    when (row) {
        is ChatRow.User -> UserBubble(row.text)
        is ChatRow.Assistant -> Answer(row.text)
        is ChatRow.Grouping -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LuIcon(Lu.list, tint = c.mute, size = 14.dp)
            Text(t("chat.updatedGrouping", "n" to row.groups), style = PullsType.sm, color = c.muted)
        }
    }
}
