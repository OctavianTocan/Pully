package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.ChatMessage
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.TextInput
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

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

/** Port of `ChatWidget.vue`: a floating button bottom-right, opening the chat as a full-height sheet. */
@Composable
internal fun ChatWidgetImpl(session: ReviewSession, modifier: Modifier) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    if (!session.hasAiResult || session.key == null) return
    Box(modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        if (!ui.chatOpen) {
            Box(
                Modifier.size(TOUCH + 4.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(c.base).border(1.dp, c.border, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = t("chat.title")) { ui.chatOpen = true },
                contentAlignment = Alignment.Center,
            ) { PhIcon("chat-circle-dots-duotone", tint = c.text, size = 24.dp, contentDescription = t("chat.title")) }
        }
    }
    if (ui.chatOpen) ChatSheet(session) { ui.chatOpen = false }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChatSheet(session: ReviewSession, onDismiss: () -> Unit) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val task = rememberLiveTask(session, "chat")
    val analyzeTask = session.taskFor("analyze")
    val streaming = task?.running == true
    val busy = streaming || analyzeTask?.running == true
    val analysis = session.saved?.analysis
    // A chat continues the analysis session; a loaded shared analysis has none.
    val available = analysis != null && analysis.sharedBy == null
    val saved = session.saved?.chat.orEmpty()
    val rows = remember(saved) { chatRows(saved) }
    // The question shows at once; the server saves it with the reply when the turn ends.
    val pending = ui.chatPending?.takeIf { text -> (rows.lastOrNull() as? ChatRow.User)?.text != text }
    val lastQuestion = rows.indexOfLast { it is ChatRow.User }
    val failed = task?.status == "failed"
    val listState = rememberLazyListState()
    val itemCount = rows.size + (if (pending != null) 1 else 0) + 2
    LaunchedEffect(itemCount, task?.activities?.size) { if (itemCount > 0) listState.animateScrollToItem(itemCount - 1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(0.dp),
        containerColor = c.base,
        contentColor = c.text,
        dragHandle = null,
        scrimColor = Color.Transparent,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhIcon("chat-circle-dots-duotone", tint = c.mute, size = 18.dp)
                Text(t("chat.title"), Modifier.weight(1f), style = PullsType.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                if (available) ActionButton(onClick = {
                    val key = session.key ?: return@ActionButton
                    task?.takeIf { it.running }?.let { session.cancelTask(it.id) }
                    ui.chatError = null
                    session.scope.launch { runCatching { session.repo.clearChat(key) }.onFailure { ui.chatError = it.message }; session.reloadSaved() }
                }, variant = ButtonVariant.Text, small = true) { Text(t("common.clear")) }
                IconButton("caret-down", t("chat.collapse"), onDismiss, compact = true)
            }
            HorizontalDivider(color = c.border)
            if (!available) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(t("chat.reanalyzeToEnable"), style = PullsType.sm, color = c.mute)
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEachIndexed { index, row ->
                    item(key = "m$index") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChatRowView(row)
                            // The last reply's work log folds in under the question that asked for it.
                            if (index == lastQuestion && !streaming && pending == null && task != null && task.activities.isNotEmpty()) {
                                AiActivityLogFull(task.activities, false, startedAt = task.startedAt.takeIf { it > 0 }, endedAt = task.endedAt, outcome = outcomeOf(task), compact = true)
                            }
                        }
                    }
                }
                if (pending != null) item(key = "pending") { UserBubble(pending) }
                item(key = "live") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (streaming && task.activities.isNotEmpty()) AiActivityLogFull(task.activities, true, startedAt = task.startedAt.takeIf { it > 0 }, compact = true)
                        else if (streaming || ui.chatPending != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Spinner(c.mute, 12.dp)
                            Text(t("chat.thinking"), style = PullsType.xs, color = c.mute)
                        }
                        if (!streaming && failed && ui.chatPending == null) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Text(task.error ?: t("chat.requestFailed"), style = PullsType.sm, color = c.error)
                                // A failed turn saved nothing, so the question is sent again (web `retry` re-runs an errored reply).
                                ActionButton(onClick = { val text = task.text; if (text != null) session.sendChat(text) else session.sendChat(null, retry = true) }, icon = "arrow-clockwise-duotone", small = true) { Text(t("common.retry")) }
                            }
                        } else if (!streaming && task?.status == "cancelled") {
                            Text(t("chat.stopped"), Modifier.border(1.dp, c.border, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp), style = PullsType.xs, color = c.mute)
                        }
                    }
                }
            }
            ui.chatError?.takeIf { !failed }?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = PullsType.xs, color = c.error) }
            HorizontalDivider(color = c.border)
            Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(ui.chatInput, { ui.chatInput = it }, t("chat.placeholder"), Modifier.weight(1f), singleLine = false, minLines = 2, enabled = !busy)
                if (streaming) ActionButton(onClick = { session.cancelTask(task.id) }, icon = "stop-duotone", small = true) { Text(t("common.stop")) }
                else ActionButton(
                    onClick = {
                        val text = ui.chatInput
                        if (text.isNotBlank() && !busy) { ui.chatInput = ""; session.sendChat(text) }
                    },
                    variant = ButtonVariant.Primary, icon = "paper-plane-right-duotone", enabled = !busy && ui.chatInput.isNotBlank(), small = true,
                ) { Text(t("common.send")) }
            }
        }
    }
}

/** Port of `ChatMessage.vue` for the saved (text-only) messages. */
@Composable
private fun ChatRowView(row: ChatRow) {
    val c = LocalPullsColors.current
    when (row) {
        is ChatRow.User -> UserBubble(row.text)
        is ChatRow.Assistant -> Markdown(row.text, Modifier.fillMaxWidth())
        is ChatRow.Grouping -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhIcon("tree-structure-duotone", tint = c.mute, size = 14.dp)
            Text(t("chat.updatedGrouping", "n" to row.groups), style = PullsType.xs, color = c.mute)
        }
    }
}
