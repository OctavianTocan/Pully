package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.data.ReviewThread
import com.octaviantocan.pulls.data.ThreadComment
import com.octaviantocan.pulls.ui.ai.AskThreadCard
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.Badge
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TextInput
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.t
import kotlinx.coroutines.launch

/*
 * Ports of `ReviewThreadCard.vue`, `ReviewCommentCard.vue`, `CommentComposer.vue`, and the
 * inline AI content of `AiAnnotations.vue` (`FindingCard inline`, hunk notes, ask threads).
 */

/** The card never grows wider than a readable line on a tablet. */
private val CARD_MAX = 800.dp

/** `.card`: a white 16dp card with a soft shadow, sitting on the diff under its line. */
@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.widthIn(max = CARD_MAX).fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised)) { content() }
}

/** Runs a GitHub write for a thread, then reloads the reviews; errors stay on the card. */
private class ThreadActions(val session: ReviewSession) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun run(action: suspend () -> Unit, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        session.scope.launch {
            runCatching { action() }
                .onSuccess { after(); session.reloadReviews() }
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }
}

/**
 * `ReviewThreadCard`: the comments, then a footer with a Reply box and Resolve (when the
 * server token can write). A resolved thread starts folded to one line, tap to open.
 */
@Composable
fun ReviewThreadCard(session: ReviewSession, thread: ReviewThread, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val actions = remember(session, thread.rootId) { ThreadActions(session) }
    var open by rememberSaveable(thread.rootId) { mutableStateOf(!thread.resolved) }
    val viewer = session.reviewData?.viewer
    val canWrite = session.canWrite && session.pull != null
    val canResolve = canWrite && thread.threadId != null && !thread.pending && !thread.resolved
    CardBox(modifier) {
        if (!open) {
            val first = thread.comments.firstOrNull()
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).clickable(role = Role.Button) { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhIcon("check-circle-duotone", tint = c.success, size = 16.dp)
                if (first != null) GithubAvatar(first.author, avatarUrl = first.avatarUrl, size = 18.dp)
                Text(
                    (first?.author ?: "ghost") + ": " + first?.body.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    Modifier.weight(1f), style = PullsType.sm, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                PhIcon("caret-down", tint = c.mute, size = 16.dp)
            }
            return@CardBox
        }
        Column {
            thread.comments.forEachIndexed { index, comment ->
                if (index > 0) HorizontalDivider(color = c.borderMute)
                ReviewCommentCard(
                    comment,
                    isViewer = viewer != null && comment.author == viewer,
                    busy = actions.busy,
                    onEdit = { body -> session.pull?.let { pull -> actions.run({ session.repo.editComment(pull, comment.id, body) }) } },
                    onDelete = { session.pull?.let { pull -> actions.run({ session.repo.deleteComment(pull, comment.id) }) } },
                )
            }
        }
        actions.error?.let { Text(it, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp), style = PullsType.xs, color = c.error) }
        if (thread.resolved) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                IconButton("caret-up", t("file.collapse"), { open = false }, compact = true)
            }
        }
        if (canWrite) {
            var replying by rememberSaveable(thread.rootId) { mutableStateOf(false) }
            var reply by rememberSaveable(thread.rootId) { mutableStateOf("") }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderMute))
            if (replying) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(reply, { reply = it }, t("review.replyPlaceholder"), Modifier.fillMaxWidth(), singleLine = false, minLines = 2, enabled = !actions.busy)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        ActionButton({ replying = false }, variant = ButtonVariant.Text, small = true, enabled = !actions.busy) { Text(t("common.cancel")) }
                        ActionButton(
                            {
                                val pull = session.pull ?: return@ActionButton
                                val body = reply
                                if (body.isBlank()) return@ActionButton
                                actions.run({ session.repo.reply(pull, thread.rootId, body) }) { reply = ""; replying = false }
                            },
                            variant = ButtonVariant.Primary, small = true, loading = actions.busy, enabled = reply.isNotBlank(),
                        ) { Text(t("common.reply")) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!thread.pending) {
                        Row(
                            Modifier.weight(1f).height(44.dp).clickable(role = Role.Button) { replying = true },
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LuIcon(Lu.reply, tint = c.activeText, size = 18.dp)
                            Text(t("common.reply"), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
                        }
                    } else Spacer(Modifier.weight(1f))
                    if (canResolve) {
                        ActionButton(
                            { val pull = session.pull ?: return@ActionButton; actions.run({ session.repo.resolve(pull, thread.threadId) }) },
                            Modifier.padding(end = 8.dp), variant = ButtonVariant.Text, small = true, enabled = !actions.busy, icon = "check-circle-duotone",
                        ) { Text(t("review.resolve")) }
                    }
                }
            }
        }
    }
}

/** `ReviewCommentCard`: avatar, login, time ago, Pending badge, edit/delete on the viewer's own. */
@Composable
fun ReviewCommentCard(comment: ThreadComment, isViewer: Boolean, busy: Boolean, onEdit: (String) -> Unit, onDelete: () -> Unit) {
    val c = LocalPullsColors.current
    var editing by rememberSaveable(comment.id) { mutableStateOf(false) }
    var draft by rememberSaveable(comment.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GithubAvatar(comment.author, avatarUrl = comment.avatarUrl, size = 28.dp)
            Text(comment.author, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            parseIsoMillis(comment.createdAt)?.let { Text(timeAgo(it), style = PullsType.xs, color = c.fade, maxLines = 1) }
            if (comment.pending) Badge(t("review.pending"), color = c.amber)
            Spacer(Modifier.weight(1f))
            if (isViewer && !editing) {
                IconButton("pencil-simple-duotone", t("review.editComment"), { draft = comment.body; editing = true }, compact = true, enabled = !busy)
                IconButton("trash-duotone", t("review.deleteComment"), onDelete, compact = true, enabled = !busy)
            }
        }
        if (editing) {
            TextInput(draft, { draft = it }, "", Modifier.fillMaxWidth(), singleLine = false, minLines = 3, enabled = !busy)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ActionButton({ editing = false }, variant = ButtonVariant.Text, small = true, enabled = !busy) { Text(t("common.cancel")) }
                ActionButton({ if (draft.isNotBlank()) { onEdit(draft); editing = false } }, variant = ButtonVariant.Primary, small = true, enabled = !busy && draft.isNotBlank()) { Text(t("common.save")) }
            }
        } else {
            Markdown(comment.body, style = PullsType.sm.copy(color = c.text))
        }
    }
}

/** The actions under the selected lines (ask AI, copy the location and link). Commenting is the bottom bar's job. */
@Composable
fun DraftBox(session: ReviewSession, draft: DraftTarget, binary: Boolean, onDone: () -> Unit) {
    if (binary) return
    CardBox {
        com.octaviantocan.pulls.ui.ai.SelectionActions(session, draft.path, draft.side, draft.startLine ?: draft.line, draft.line, onDone)
    }
}

/** `SeverityBadge`: `badge-color-red|amber|gray`. */
@Composable
fun SeverityBadge(severity: String) {
    val c = LocalPullsColors.current
    val color = when (severity) {
        "bug" -> c.error
        "risk" -> c.amber
        else -> c.gray
    }
    Text(
        t("critique.severity.$severity"),
        Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = PullsType.xs.copy(fontWeight = FontWeight.Medium),
        color = color,
        maxLines = 1,
    )
}

/** `FindingCard inline`: severity, title, body, suggestion; tap jumps to the finding. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InlineFinding(session: ReviewSession, finding: CritiqueFinding, highlighted: Boolean) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.padding(vertical = 4.dp).widthIn(max = CARD_MAX).fillMaxWidth().clip(shape)
            .border(1.dp, if (highlighted) c.amber else c.border, shape).background(c.base)
            .clickable(role = Role.Button) { session.focusFinding(finding) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SeverityBadge(finding.severity)
            Text(finding.title, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        if (finding.body.isNotBlank()) Markdown(finding.body, style = PullsType.sm.copy(color = c.text))
        finding.suggestion?.let { suggestion ->
            Text(t("critique.suggestion"), style = PullsType.xs, color = c.mute)
            Text(
                suggestion,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(c.raised).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.text, softWrap = false,
            )
        }
    }
}

/** `AiAnnotations`: hunk notes (sparkle, `text-xs op-fade`), findings, ask threads. */
@Composable
fun AiAnnotationBlock(session: ReviewSession, anchor: AiAnchor, highlightedFinding: String?) {
    val c = LocalPullsColors.current
    anchor.notes.forEach { note ->
        Row(Modifier.padding(vertical = 4.dp).widthIn(max = CARD_MAX), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhIcon("sparkle-duotone", Modifier.padding(top = 2.dp), tint = c.fade, size = 14.dp)
            Text(note, style = PullsType.xs, color = c.fade)
        }
    }
    anchor.findings.forEach { InlineFinding(session, it, highlighted = it.id == highlightedFinding) }
    anchor.asks.forEach { AskThreadCard(session, it, Modifier.padding(vertical = 4.dp).widthIn(max = CARD_MAX)) }
}
