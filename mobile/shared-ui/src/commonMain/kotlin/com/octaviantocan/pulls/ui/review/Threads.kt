package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.ReviewThread
import com.octaviantocan.pulls.data.ThreadComment
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.octaviantocan.pulls.ui.design.hairlineTop

/*
 * Ports of `ReviewThreadCard.vue`, `ReviewCommentCard.vue`, `CommentComposer.vue`, and the
 * inline AI content of `AiAnnotations.vue` (`FindingCard inline`, hunk notes, ask threads).
 */

/** The card never grows wider than a readable line on a tablet. */
val ReviewCardMaxWidth = 800.dp

/** `.card`: a white 16dp card with a soft shadow, sitting on the diff under its line. */
@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.widthIn(max = ReviewCardMaxWidth).fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised)) { content() }
}

/** Runs a GitHub write for a thread, then reloads the reviews; errors stay on the card. */
internal class ThreadActions(
    private val scope: CoroutineScope,
    private val reload: () -> Unit,
    private val feedback: (Cue, Emphasis) -> Unit = { cue, emphasis -> ControlFeedback.play(cue, emphasis) },
) {
    constructor(session: ReviewSession) : this(session.scope, session::reloadReviews)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun run(action: suspend () -> Unit, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = runCatching { action() }
                currentCoroutineContext().ensureActive()
                result.onSuccess { after(); reload(); feedback(Cue.Success, Emphasis.Subtle) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        error = it.message ?: it.toString()
                        feedback(Cue.Error, Emphasis.Subtle)
                    }
            } finally {
                busy = false
            }
        }
    }
}

/**
 * `ReviewThreadCard`: the comments, then a footer with a Reply box and Resolve (when the
 * server token can write). A resolved thread starts folded to one line, tap to open.
 */
@Composable
fun ReviewThreadCard(session: ReviewSession, thread: ReviewThread, modifier: Modifier = Modifier) {
    key(session, session.captureView(), thread.rootId) { ReviewThreadBody(session, thread, modifier) }
}

@Composable
private fun ReviewThreadBody(session: ReviewSession, thread: ReviewThread, modifier: Modifier) {
    val c = LocalPullsColors.current
    val actions = rememberReviewThreadActions(session)
    var open by rememberSaveable(thread.rootId) { mutableStateOf(!thread.resolved) }
    // The thread a link pointed at opens, resolved or not.
    val linked = session.linkedThread == thread.rootId
    LaunchedEffect(linked) { if (linked) open = true }
    val viewer = session.reviewData?.viewer
    val canWrite = actions.canWrite
    val canResolve = canWrite && thread.threadId != null && !thread.pending && !thread.resolved
    CardBox(modifier) {
        if (!open) {
            val first = thread.comments.firstOrNull()
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).cueClickable(role = Role.Button) { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LuIcon(Lu.check, tint = c.success, size = 18.dp)
                if (first != null) GithubAvatar(first.author, avatarUrl = first.avatarUrl, size = 18.dp)
                Text(
                    (first?.author ?: "ghost") + ": " + first?.body.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    Modifier.weight(1f), style = PullsType.sm, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LuIcon(Lu.chevd, tint = c.faint, size = 18.dp)
            }
            return@CardBox
        }
        Column {
            thread.comments.forEachIndexed { index, comment ->
                Box(if (index > 0) Modifier.hairlineTop(c.borderMute) else Modifier) {
                    ReviewCommentCard(
                        comment,
                        isViewer = canWrite && viewer != null && comment.author == viewer,
                        busy = actions.busy,
                        onEdit = { body -> actions.run({ pull -> session.repo.editComment(pull, comment.id, body) }) },
                        onDelete = { actions.run({ pull -> session.repo.deleteComment(pull, comment.id) }) },
                    )
                }
            }
        }
        actions.error?.let { Text(it, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp), style = PullsType.sm, color = c.error) }
        if (thread.resolved) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                FloatButton({ open = false }, t("file.collapse"), size = 40.dp, flat = true) { LuIcon(Lu.chevd, Modifier.rotate(180f), tint = c.muted, size = 18.dp) }
            }
        }
        if (actions.canKeepDraft) {
            var replying by rememberSaveable(thread.rootId) { mutableStateOf(false) }
            var reply by rememberSaveable(thread.rootId) { mutableStateOf("") }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderMute))
            if (replying) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.xl)).background(c.paper)) {
                        SheetField(null, reply, { if (actions.canWrite) reply = it }, placeholder = t("review.replyPlaceholder"), first = true, enabled = canWrite && !actions.busy, minLines = 2)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(t("common.cancel"), { if (actions.canWrite) replying = false }, Modifier.weight(1f), enabled = canWrite && !actions.busy)
                        CtaButton(
                            t("common.reply"),
                            {
                                val body = reply
                                if (body.isBlank()) return@CtaButton
                                actions.run({ pull -> session.repo.reply(pull, thread.rootId, body) }) { reply = ""; replying = false }
                            },
                            Modifier.weight(1.6f), enabled = canWrite && reply.isNotBlank() && !actions.busy, leading = { if (actions.busy) Orb(size = 18.dp) },
                        )
                    }
                }
            } else {
                // The footer keeps the card's inside margin round Resolve, so the pill never meets the card's edge
                // ("these comments have no margin on the resolve button", recording 15 Oct 15:37, 10:28 to 10:33; L19).
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!thread.pending) {
                        Row(
                            Modifier.weight(1f).height(44.dp).cueClickable(enabled = canWrite, role = Role.Button) { if (actions.canWrite) replying = true },
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LuIcon(Lu.reply, tint = c.activeText, size = 18.dp)
                            Text(t("common.reply"), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
                        }
                    } else Spacer(Modifier.weight(1f))
                    if (canResolve) {
                        PillButton(
                            t("review.resolve"),
                            {
                                val threadId = thread.threadId ?: return@PillButton
                                actions.run({ pull -> session.repo.resolve(pull, threadId) })
                            },
                            Modifier.padding(end = 10.dp), enabled = !actions.busy, leading = { LuIcon(Lu.check, tint = c.text, size = 18.dp) },
                        )
                    }
                }
            }
        }
    }
}

/** `ReviewCommentCard`: avatar, login, time ago, Pending badge, edit/delete on the viewer's own. */
@Composable
fun ReviewCommentCard(comment: ThreadComment, isViewer: Boolean, busy: Boolean, onEdit: (String) -> Unit, onDelete: () -> Unit) {
    val canWrite by rememberUpdatedState(isViewer && LocalReviewCapabilities.current.canWriteComments)
    val c = LocalPullsColors.current
    var editing by rememberSaveable(comment.id) { mutableStateOf(false) }
    var draft by rememberSaveable(comment.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GithubAvatar(comment.author, avatarUrl = comment.avatarUrl, size = 28.dp)
            Text(comment.author, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            parseIsoMillis(comment.createdAt)?.let { Text(timeAgo(it), style = PullsType.sm, color = c.muted, maxLines = 1) }
            // Pending is a word in the warning colour, not a tag (rule 49).
            if (comment.pending) Text(t("review.pending"), style = PullsType.sm, color = c.amber, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (canWrite && !editing) {
                FloatIcon(Lu.edit, t("review.editComment"), { if (canWrite) { draft = comment.body; editing = true } }, flat = true, size = 40.dp, iconSize = 18.dp, tint = c.muted, enabled = !busy)
                FloatIcon(Lu.trash, t("review.deleteComment"), { if (canWrite) onDelete() }, flat = true, size = 40.dp, iconSize = 18.dp, tint = c.muted, enabled = !busy)
            }
        }
        if (editing && canWrite) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.xl)).background(c.paper)) {
                SheetField(null, draft, { if (canWrite) draft = it }, first = true, enabled = !busy, minLines = 3)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(t("common.cancel"), { editing = false }, Modifier.weight(1f), enabled = !busy)
                CtaButton(t("common.save"), { if (canWrite && draft.isNotBlank()) { onEdit(draft); editing = false } }, Modifier.weight(1.6f), enabled = !busy && draft.isNotBlank())
            }
        } else {
            Markdown(comment.body, style = PullsType.sm.copy(color = c.text))
        }
    }
}

