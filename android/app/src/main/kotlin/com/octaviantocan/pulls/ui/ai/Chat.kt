package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.review.prIsoMillis
import com.octaviantocan.pulls.ui.review.timeAgo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SearchList
import com.octaviantocan.pulls.data.ChatHistoryEntry
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.mentionedPlaces
import com.octaviantocan.pulls.ui.review.copyText
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Activity
import com.octaviantocan.pulls.data.ChatMessage
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SendButton
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import androidx.compose.foundation.border
import com.octaviantocan.pulls.ui.design.PullsRadius
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

private val GROUPING_UPDATED = Regex("Grouping updated: (\\d+) groups?")

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

private fun ReviewSession.sendChat(text: String?, retry: Boolean = false) {
    val ui = aiUi(this)
    val locale = settings.locale
    ui.chatError = null
    ui.chatPending = text
    startAiTask("chat", extra = {
        put("locale", locale)
        if (text != null) put("text", text)
        if (retry) put("retry", true)
    }, onStartError = { ui.chatPending = null; ui.chatError = it }, onDone = { done ->
        ui.chatPending = null
        if (done.status == "failed") ui.chatError = done.error ?: "Request failed"
    })
}

/**
 * The Ask sheet: your questions on the right in the accent bubble, the answers as plain text with no box (rule 99),
 * starter chips while it is empty, a send pill, and a new-chat button at the top right once there is a conversation.
 * While an answer is on its way the work log shows each step with its icon, the orb and the grey bars under it, never
 * a bubble of dots. It continues the analysis session when there is one and starts from the pull request itself when
 * there is not, so Ask never waits on Analyze. [initial] is asked on open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskSheetImpl(session: ReviewSession, title: String, suggestions: List<String>, initial: String?, reference: AskReference?, keptScroll: ScrollState?, returning: Boolean, onOpen: (CodePlace) -> Unit, onDismiss: () -> Unit) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    val task = rememberLiveTask(session, "chat")
    val analyzeTask = session.taskFor("analyze")
    val streaming = task?.running == true
    // A chat shares the diff's saved transcript with the analysis, so the two do not run at once.
    val busy = streaming || analyzeTask?.running == true
    val saved = session.saved?.chat.orEmpty()
    val rows = remember(saved) { chatRows(saved) }
    // The question shows at once; the server saves it with the reply when the turn ends.
    val pending = ui.chatPending?.takeIf { text -> (rows.lastOrNull() as? ChatRow.User)?.text != text }
    val lastQuestion = rows.indexOfLast { it is ChatRow.User }
    // The answer as it is written (rule 99): the newest writing step's text, held once the turn settles until the saved
    // reply takes its place, so the words stay where they are when it swaps. A new question or a new saved reply lets it go.
    val held = remember(rows, ui.chatPending) { arrayOf<String?>(null) }
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
    // Each new line brings the end into view while the reader is at the end; once they scroll up the answer goes on
    // writing below without pulling them back down, until they come back to the end or ask again ("if I scroll up, you
    // scroll me down again immediately", recording 15 Oct 15:37, 16:14 to 16:23; L32). A sheet opened again where it was
    // is left there until something new comes.
    val stay = remember { booleanArrayOf(returning) }
    var following by remember { mutableStateOf(true) }
    val ours = remember { booleanArrayOf(false, false) }
    val slack = with(LocalDensity.current) { 96.dp.toPx() }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (moving && !ours[0]) { ours[1] = true; following = false }
            if (!moving && ours[1]) { ours[1] = false; following = scroll.value >= scroll.maxValue - slack }
        }
    }
    LaunchedEffect(rows.size, pending, task?.activities?.size, streaming, written?.length) {
        if (stay[0]) { stay[0] = false; return@LaunchedEffect }
        if (!following) return@LaunchedEffect
        ours[0] = true
        try { scroll.animateScrollTo(scroll.maxValue) } finally { ours[0] = false }
    }
    LaunchedEffect(initial) {
        if (!initial.isNullOrBlank() && !busy) session.sendChat(initial)
    }

    // A file note (or a file) the question is about: it rides on the next question and then goes.
    var attached by remember(reference) { mutableStateOf(reference) }

    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || busy) return
        val about = attached
        following = true
        session.sendChat(if (about == null) question else "About ${about.label}: \"${about.text}\"\n\n$question")
        attached = null
    }

    // The chats this pull request had before, on a searchable sheet over this one (L36).
    var history by remember { mutableStateOf(false) }
    fun newChat() {
        val key = session.key ?: return
        ui.chatError = null
        // The page empties at once; the server keeps the old chat in the history meanwhile (L35, L36).
        session.saved = session.saved?.copy(chat = emptyList(), suggestions = emptyList())
        session.scope.launch { runCatching { session.repo.clearChat(key) }.onFailure { ui.chatError = it.message }; session.reloadSaved() }
    }

    @Composable
    fun RowView(row: ChatRow) {
        ChatRowView(row)
        if (row is ChatRow.Assistant) AnswerExtras(
            session, row.text, onOpen,
            // The files the turn read are known for the last answer only: the steps of older turns are not kept.
            read = if (row === lastAnswer && !busy) task?.takeIf { it.status == "done" }?.activities.orEmpty() else emptyList(),
            onRedo = if (row === lastAnswer && !busy) ({ session.sendChat(null, retry = true) }) else null,
            next = if (row === lastAnswer) nextQuestions else emptyList(),
            onAsk = ::ask,
        )
    }

    // Mentions in the answers open their place and code is the diff's code view (rules 95 and 96).
    ProvideAiText(session, onOpen) {
        PullsSheet(
            onClose = onDismiss,
            title = title,
            full = true,
            scroll = scroll,
            // A fresh conversation, in place of a "Clear" button (F69).
            trailing = if (rows.isNotEmpty() && !busy) ({ FloatIcon(Lu.newChat, "New chat", ::newChat) }) else null,
            footer = {
                attached?.let { about -> AttachedReference(about) { attached = null } }
                AskField(
                    ui.chatInput, { ui.chatInput = it },
                    placeholder = if (rows.isEmpty()) "Ask Pulls…" else "Ask a follow-up…",
                    onSend = { val text = ui.chatInput; ui.chatInput = ""; ask(text) },
                    sendEnabled = !busy && ui.chatInput.isNotBlank(),
                    onStop = if (streaming && task != null) ({ session.cancelTask(task.id) }) else null,
                    // With nothing typed the button opens the chats this pull request had (Tavi, L36).
                    onHistory = if (session.key != null && !busy) ({ history = true }) else null,
                    working = streaming || ui.chatPending != null,
                )
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (analyzeTask?.running == true && !streaming) Text("The analysis is running. You can ask when it finishes.", style = PullsType.sm, color = c.muted)
                // The turn sits under its own question: below every saved row while it runs, under the saved
                // question once the reply is saved, with the answer after it. The orb is drawn once, at one
                // place in the code, so the orb that ran is the one that settles, where the log was.
                shown.take(turnAt).forEach { RowView(it) }
                if (pending != null) UserBubble(pending)
                // From the moment the question is sent: "Thinking" and the time, then each step with its icon (rule 72). When the
                // reply is in, the same log folds to "Worked for…" under the question that asked for it, so what is under it
                // glides up instead of jumping (rule 69).
                val working = streaming || ui.chatPending != null
                val sentAt = remember(ui.chatPending) { System.currentTimeMillis() }
                val turnTask = task?.takeIf { answering } ?: task?.takeIf { !working && lastQuestion >= 0 && it.activities.isNotEmpty() }
                if (working || turnTask != null) key(turnTask?.id) {
                    // The tour's progress, line for line: "Thinking" and the time over the steps with their icons, and no
                    // grey bars or orb under them; what is running shows in the send button (recording 15 Oct 15:37, 16:09
                    // to 16:37; L31, L33).
                    AiActivityLogFull(
                        turnTask?.activities.orEmpty(), working, startedAt = turnTask?.startedAt?.takeIf { it > 0 } ?: sentAt,
                        endedAt = turnTask?.endedAt, outcome = outcomeOf(turnTask), compact = true, mark = false,
                    )
                }
                // The answer as it is written, in the place the saved reply takes: plain text, never a bubble (rule 99).
                if (written != null) Answer(written)
                shown.drop(turnAt).forEach { RowView(it) }
                if (!streaming && failed && ui.chatPending == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(task?.error ?: t("chat.requestFailed"), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.error)
                        // A failed turn saved nothing, so the question is sent again (web `retry` re-runs an errored reply).
                        FloatIcon(Lu.redo, "Ask again", { val text = task?.text; if (text != null) session.sendChat(text) else session.sendChat(null, retry = true) }, flat = true, iconSize = 22.dp, tint = c.muted)
                    }
                } else if (!streaming && task?.status == "cancelled") {
                    Text(t("chat.stopped"), style = PullsType.sm, color = c.muted)
                }
                ui.chatError?.takeIf { !failed }?.let { Text(it, style = PullsType.sm, color = c.error) }
            }
            // An empty chat, the first and every new one alike: the questions to start with as the same rows that follow
            // an answer, in a card, instead of loose chips ("this is not how we do things", 16:58 to 17:12; L35).
            if (rows.isEmpty() && pending == null && !answering && suggestions.isNotEmpty()) {
                Text("Start with", Modifier.padding(start = 4.dp, top = 6.dp), style = PullsType.sm, color = c.muted)
                SheetCard(gap = 8.dp) {
                    suggestions.forEachIndexed { index, suggestion ->
                        SheetRow(suggestion, { ask(suggestion) }, first = index == 0, icon = { LuIcon(Lu.followUp, tint = c.muted, size = 18.dp) })
                    }
                }
            }
        }
    }
    if (history) ChatHistorySheet(session, onClose = { history = false }) { following = true }
}

/**
 * The chats this pull request had, newest first, on the one searchable list a sheet picks from ([SearchList]): each its
 * first question, when it was and how many questions it had. A tap brings that chat back into Ask, and the chat that was
 * open goes into the list in its place (Tavi's idea: recording 15 Oct 15:37, 17:19 to 17:48; L36).
 */
@Composable
private fun ChatHistorySheet(session: ReviewSession, onClose: () -> Unit, onRestored: () -> Unit) {
    val c = LocalPullsColors.current
    val key = session.key ?: return
    val ui = aiUi(session)
    val chats by produceState<List<ChatHistoryEntry>?>(null, key) {
        value = runCatching { session.repo.chatHistory(key) }.getOrElse { ui.chatError = it.message; emptyList() }
    }
    PullsSheet(onClose, "Chats") {
        val list = chats
        when {
            list == null -> RowsSkeleton(Modifier.padding(top = 12.dp), rows = 3)
            list.isEmpty() -> Text("No earlier chats on this pull request.", Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
            else -> SearchList(list, text = { it.title }, placeholder = "Find a chat", empty = "No chat matches") { chat, first ->
                val ago = prIsoMillis(chat.at)?.let { timeAgo(it) }
                val asked = if (chat.turns == 1) "1 question" else "${chat.turns} questions"
                SheetRow(chat.title.ifBlank { "Chat" }, {
                    onClose()
                    session.scope.launch {
                        runCatching { session.repo.restoreChat(key, chat.id) }.onFailure { ui.chatError = it.message }
                        session.reloadSaved()
                        onRestored()
                    }
                }, first = first, iconless = true, detail = listOfNotNull(ago, asked).joinToString(" · "))
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
internal fun Answer(text: String) {
    val c = LocalPullsColors.current
    Markdown(text, Modifier.fillMaxWidth(), style = PullsType.read.copy(color = c.text))
}

/**
 * Under a PR answer: the places in the diff it mentions, then the files it [read] that it does not mention (rule 99), each
 * once, then what every answer has under it.
 */
@Composable
private fun AnswerExtras(session: ReviewSession, text: String, onOpen: (CodePlace) -> Unit, read: List<Activity>, onRedo: (() -> Unit)?, next: List<String>, onAsk: (String) -> Unit) {
    val places = remember(text, read, session.files) {
        val named = mentionedPlaces(text, { name -> resolveMention(session, name) }, reviewRepo(session))
        val opened = readFiles(read).map { (path, line) -> CodePlace(resolveMention(session, path) ?: path, line) }
        named + opened.filter { place -> named.none { it.path == place.path } }.distinctBy { it.path }
    }
    val sources = places.map { place ->
        AnswerSource(place.path.substringAfterLast('/') + if (place.line > 0) ":${place.line}" else "", "Open ${place.path}") { onOpen(place) }
    }
    AnswerExtras(text, sources, onRedo, next, onAsk)
}

/** Something an answer mentions, as a chip under it: its [label], what a tap does for a screen reader, and [open]. */
internal class AnswerSource(val label: String, val clickLabel: String, val open: () -> Unit)

/**
 * Under an answer (rule 99): what it mentions as chips that open them, copy and, on the last
 * answer, redo, then the questions to ask next as rows that send with a tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnswerExtras(text: String, sources: List<AnswerSource>, onRedo: (() -> Unit)?, next: List<String>, onAsk: (String) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (sources.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEach { source -> SourceChip(source) }
        }
        Row(Modifier.offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
            FloatIcon(if (copied) Lu.check else Lu.copy, if (copied) "Copied" else "Copy the answer", { copyText(context, plainAnswer(text)); copied = true }, flat = true, size = 40.dp, iconSize = 18.dp, tint = c.muted)
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
internal fun AskField(
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
    Row(
        Modifier.fillMaxWidth().height(52.dp).floatShadow(CircleShape, PullsShadow.Search).clip(CircleShape).background(c.raised).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = PullsType.base.copy(color = c.text),
            cursorBrush = SolidColor(c.accentTeal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = PullsType.base, color = c.muted, maxLines = 1)
                    inner()
                }
            },
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

/** An answer as plain text for the clipboard: links keep their words, code keeps its text. */
internal fun plainAnswer(markdown: String): String = markdown
    .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
    .replace(Regex("""(?m)^```.*$\n?"""), "")
    .replace(Regex("""`([^`]+)`"""), "$1")
    .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
    .trim()

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
