package com.octaviantocan.pulls.ui.ai

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
internal fun AskSheetImpl(session: ReviewSession, title: String, suggestions: List<String>, initial: String?, reference: AskReference?, onOpen: (CodePlace) -> Unit, onDismiss: () -> Unit) {
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
    // Redo answers the last question again (a turn with no text of its own): its old answer steps aside meanwhile.
    val redoing = streaming && task?.text.isNullOrEmpty() && pending == null && lastQuestion >= 0
    val shown = if (redoing) rows.take(lastQuestion + 1) else rows
    // Where the turn's own log and orb go in the rows: after all of them while it runs, after its question once saved.
    val turnAt = if (streaming || pending != null) shown.size else lastQuestion + 1
    // Under the last answer: redo and the questions a small model wrote for it (rule 99).
    val lastAnswer = shown.lastOrNull() as? ChatRow.Assistant
    val nextQuestions = if (lastAnswer != null && !busy && pending == null) session.saved?.suggestions.orEmpty() else emptyList()
    val failed = task?.status == "failed"
    val scroll = rememberScrollState()
    LaunchedEffect(rows.size, pending, task?.activities?.size, streaming) { scroll.animateScrollTo(scroll.maxValue) }
    LaunchedEffect(initial) {
        if (!initial.isNullOrBlank() && !busy) session.sendChat(initial)
    }

    // A file note (or a file) the question is about: it rides on the next question and then goes.
    var attached by remember(reference) { mutableStateOf(reference) }

    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || busy) return
        val about = attached
        session.sendChat(if (about == null) question else "About ${about.label}: \"${about.text}\"\n\n$question")
        attached = null
    }

    @Composable
    fun RowView(row: ChatRow) {
        ChatRowView(row)
        if (row is ChatRow.Assistant) AnswerExtras(
            session, row.text, onOpen,
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
            trailing = if (rows.isNotEmpty() && !busy) ({
                FloatIcon(Lu.newChat, "New chat", {
                    val key = session.key ?: return@FloatIcon
                    ui.chatError = null
                    session.scope.launch { runCatching { session.repo.clearChat(key) }.onFailure { ui.chatError = it.message }; session.reloadSaved() }
                })
            }) else null,
            footer = {
                attached?.let { about -> AttachedReference(about) { attached = null } }
                Row(
                    Modifier.fillMaxWidth().height(52.dp).floatShadow(CircleShape, PullsShadow.Search).clip(CircleShape).background(c.raised).padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BasicTextField(
                        value = ui.chatInput,
                        onValueChange = { ui.chatInput = it },
                        singleLine = true,
                        textStyle = PullsType.base.copy(color = c.text),
                        cursorBrush = SolidColor(c.accentTeal),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { val text = ui.chatInput; ui.chatInput = ""; ask(text) }),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (ui.chatInput.isEmpty()) Text(if (rows.isEmpty()) "Ask Pulls…" else "Ask a follow-up…", style = PullsType.base, color = c.muted, maxLines = 1)
                                inner()
                            }
                        },
                    )
                    if (streaming && task != null) {
                        FloatIcon(Lu.stop, t("common.stop"), { session.cancelTask(task.id) }, size = 40.dp, iconSize = 18.dp, primary = true)
                    } else {
                        SendButton({ val text = ui.chatInput; ui.chatInput = ""; ask(text) }, t("common.send"), enabled = !busy && ui.chatInput.isNotBlank())
                    }
                }
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (analyzeTask?.running == true && !streaming) Text("The analysis is running. You can ask when it finishes.", style = PullsType.sm, color = c.muted)
                // The turn sits under its own question: below every saved row while it runs, under the saved
                // question once the reply is saved, with the answer after it. The orb is drawn once, at one
                // place in the code, so the orb that ran is the one that settles, where the log was.
                shown.take(turnAt).forEach { RowView(it) }
                if (pending != null) UserBubble(pending)
                // From the moment the question is sent: "Thinking" and the time, then each step with its icon (rule 72).
                val working = streaming || ui.chatPending != null
                val sentAt = remember(ui.chatPending) { System.currentTimeMillis() }
                if (working) {
                    val live = task?.takeIf { streaming }
                    AiActivityLogFull(live?.activities.orEmpty(), true, startedAt = live?.startedAt?.takeIf { it > 0 } ?: sentAt, compact = true, mark = false)
                }
                // The last reply's work log folds in under the question that asked for it.
                if (!streaming && pending == null && lastQuestion >= 0 && task != null && task.activities.isNotEmpty()) {
                    AiActivityLogFull(task.activities, false, startedAt = task.startedAt.takeIf { it > 0 }, endedAt = task.endedAt, outcome = outcomeOf(task), compact = true)
                }
                AiLiveMark(working, hint = true)
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
            if (rows.isEmpty() && pending == null && suggestions.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { suggestion -> SoftChip(suggestion, selected = false, onClick = { ask(suggestion) }) }
                }
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
private fun Answer(text: String) {
    val c = LocalPullsColors.current
    Markdown(text, Modifier.fillMaxWidth(), style = PullsType.read.copy(color = c.text))
}

/**
 * Under an answer (rule 99): the places it mentions as chips that open them, copy and, on the last
 * answer, redo, then the questions to ask next as rows that send with a tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerExtras(session: ReviewSession, text: String, onOpen: (CodePlace) -> Unit, onRedo: (() -> Unit)?, next: List<String>, onAsk: (String) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val places = remember(text, session.files) { mentionedPlaces(text) { name -> resolveMention(session, name) } }
    var copied by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (places.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            places.forEach { place -> SourceChip(place) { onOpen(place) } }
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

/** A place an answer mentions: the file's name and the line, on the kit's outlined 36dp chip (rule 70). */
@Composable
private fun SourceChip(place: CodePlace, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    val shape = CircleShape
    Box(
        Modifier.height(36.dp).clip(shape).border(1.dp, c.border, shape)
            .cueClickable(role = Role.Button, onClickLabel = "Open ${place.path}", onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(place.path.substringAfterLast('/') + if (place.line > 0) ":${place.line}" else "", style = PullsType.sm, color = c.text, maxLines = 1)
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
