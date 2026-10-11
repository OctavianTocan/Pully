@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewCheckbox
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.TextInput
import com.octaviantocan.pulls.ui.design.ToggleGroup
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import kotlinx.coroutines.launch

/**
 * `AppModal` below `sm`: a bottom sheet (`rounded-t-xl border border-base bg-base`), a header
 * with title, description and a close button (`px-3 py-2`, bottom border), and a `p-3` body
 * that scrolls and clears the keyboard and the navigation bar.
 */
@Composable
internal fun AppSheet(open: Boolean, onClose: () -> Unit, title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (!open) return
    val c = LocalPullsColors.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    fun close() { scope.launch { state.hide() }.invokeOnCompletion { onClose() } }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = state,
        sheetMaxWidth = 576.dp,
        shape = shape,
        containerColor = c.base,
        contentColor = c.text,
        tonalElevation = 0.dp,
        scrimColor = if (c.dark) Color.Black.copy(alpha = 0.4f) else Color(0x66DDDDDD),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().border(1.dp, c.border, shape)) {
            Row(
                Modifier.fillMaxWidth().bottomBorder(c.border).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = PullsType.base.copy(fontWeight = FontWeight.Medium), color = c.text)
                    if (description != null) Text(description, style = PullsType.sm, color = c.fade)
                }
                IconButton("x", t("common.close"), { close() }, compact = true)
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** Port of `ReviewProgressModal.vue`: Mark all / Invert / Reset, then every file (by path) with its checkbox and status; a tap jumps to the file. */
@Composable
fun ReviewProgressSheet(session: ReviewSession, top: ReviewTopUi) {
    val c = LocalPullsColors.current
    val rows = session.files.map { it to session.reviewState(it) }.sortedBy { it.first.path }
    val reviewedCount = rows.count { it.second == ReviewState.Reviewed }
    fun shasWhere(reviewed: Boolean) = rows.filter { (it.second == ReviewState.Reviewed) == reviewed }.map { it.first.sha }
    AppSheet(
        top.progressOpen, { top.progressOpen = false }, t("reviewProgress.title"),
        t("group.filesReviewed", "reviewed" to reviewedCount, "total" to rows.size),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton({ session.setReviewed(shasWhere(false), true) }, icon = "checks", small = true, enabled = reviewedCount != rows.size) { Text(t("reviewProgress.markAll")) }
            ActionButton({
                val (unmark, mark) = shasWhere(true) to shasWhere(false)
                session.setReviewed(unmark, false)
                session.setReviewed(mark, true)
            }, icon = "arrows-left-right", small = true, enabled = rows.isNotEmpty()) { Text(t("reviewProgress.invert")) }
            ActionButton(
                { session.setReviewed(rows.map { it.first.sha }, false) }, icon = "arrow-counter-clockwise", small = true,
                enabled = reviewedCount > 0 || session.changedSinceReviewed.isNotEmpty(),
            ) { Text(t("reviewProgress.reset")) }
        }
        Column {
            rows.forEach { (file, state) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewCheckbox(state, { session.setReviewed(listOf(file.sha), it) }, label = t("file.markReviewed"))
                    Box(
                        Modifier.weight(1f).defaultMinSize(minHeight = TOUCH).clickable(role = Role.Button) {
                            top.progressOpen = false
                            session.focus = Focus(file.path)
                        },
                        contentAlignment = Alignment.CenterStart,
                    ) { FilePath(file.path) }
                    val (label, color) = when (state) {
                        ReviewState.Changed -> "changed" to WebColors.orange(c.dark)
                        ReviewState.Reviewed -> "reviewed" to WebColors.primary(c.dark)
                        else -> "unreviewed" to c.fade
                    }
                    Text(t("reviewProgress.status.$label"), style = PullsType.xs, color = color)
                }
            }
        }
    }
}

/** `DisplayFilePath`: the folder faded, the file name in full color; wraps on phones so the name is never cut. */
@Composable
private fun FilePath(path: String) {
    val c = LocalPullsColors.current
    val slash = path.lastIndexOf('/')
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            if (slash >= 0) {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = c.fade)); append(path.substring(0, slash + 1)); pop()
            }
            append(path.substring(slash + 1))
        },
        style = PullsType.sm, color = c.text,
    )
}

private val VERDICTS = listOf("COMMENT" to R.drawable.ic_oct_comment_16, "APPROVE" to R.drawable.ic_oct_check_circle_16, "REQUEST_CHANGES" to R.drawable.ic_oct_code_review_16)

/** Port of `ReviewSubmitModal.vue`: pending note, summary, verdict, Discard review, Submit. */
@Composable
fun ReviewSubmitSheet(session: ReviewSession, top: ReviewTopUi) {
    val c = LocalPullsColors.current
    val pull = session.pull ?: return
    val scope = rememberCoroutineScope()
    var body by rememberSaveable { mutableStateOf("") }
    var verdict by rememberSaveable { mutableStateOf("COMMENT") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending = session.reviewData?.pendingCount ?: 0
    fun run(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            runCatching { action() }
                .onSuccess { body = ""; verdict = "COMMENT"; top.reviewOpen = false; session.reloadReviews() }
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }
    AppSheet(top.reviewOpen, { top.reviewOpen = false }, t("review.reviewChanges")) {
        if (pending > 0) Text(tc("review.includesPending", pending), style = PullsType.sm, color = c.fade)
        TextInput(body, { body = it }, t("review.summaryPlaceholder"), Modifier.fillMaxWidth(), singleLine = false, minLines = 4, enabled = !busy)
        error?.let { Text(it, style = PullsType.sm, color = WebColors.red(c.dark)) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            VerdictToggle(verdict) { verdict = it }
            Spacer(Modifier.weight(1f))
            if (session.reviewData?.pendingReviewId != null) {
                ActionButton({ run { session.repo.discardReview(pull) } }, variant = ButtonVariant.Text, enabled = !busy) {
                    Text(t("review.discard"), color = WebColors.red(c.dark))
                }
            }
            ActionButton(
                { run { session.repo.submitReview(pull, verdict, body) } },
                variant = ButtonVariant.Primary, loading = busy,
                enabled = verdict == "APPROVE" || body.isNotBlank() || pending > 0,
            ) { Text(t("common.submit")) }
        }
    }
}

/** The verdict `ActionToggleGroup` with GitHub's Octicons, the same look as [ToggleGroup]. */
@Composable
private fun VerdictToggle(value: String, onChange: (String) -> Unit) {
    val c = LocalPullsColors.current
    val labels = mapOf("COMMENT" to t("review.comment"), "APPROVE" to t("review.approve"), "REQUEST_CHANGES" to t("review.requestChanges"))
    FlowRow(Modifier.clip(RoundedCornerShape(8.dp)).background(c.sunken).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        VERDICTS.forEach { (option, icon) ->
            val on = option == value
            Row(
                Modifier.defaultMinSize(minHeight = 36.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) (if (c.dark) Color(0xFF1C1C1C) else Color.White) else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(option) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painterResource(icon), null, Modifier.size(16.dp), tint = if (on) c.text else c.muted)
                Text(labels.getValue(option), style = PullsType.sm, color = if (on) c.text else c.muted, maxLines = 1)
            }
        }
    }
}

/** Port of `PrMergeModal.vue`: method, commit title and message prefilled from `defaultCommits`, then Merge. */
@Composable
fun MergeSheet(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData) {
    val c = LocalPullsColors.current
    val overview = data.overview
    var method by rememberSaveable(overview.id) { mutableStateOf(overview.merge.defaultMethod) }
    val defaults = data.defaultCommits[method] ?: ("" to "")
    var title by rememberSaveable(overview.id) { mutableStateOf("") }
    var body by rememberSaveable(overview.id) { mutableStateOf("") }
    LaunchedEffect(top.mergeOpen, method) {
        if (!top.mergeOpen) return@LaunchedEffect
        title = defaults.first
        body = defaults.second
        top.actionError = null
    }
    val busy = top.busy == "merge"
    AppSheet(
        top.mergeOpen, { top.mergeOpen = false }, t("overview.merge.title", "n" to overview.number),
        t("overview.merge.into", "head" to overview.headRef, "base" to overview.baseRef),
    ) {
        if (overview.merge.methods.size > 1) {
            ToggleGroup(overview.merge.methods.map { it to t("overview.merge.method.$it") }, method, { method = it })
        }
        if (method != "rebase") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("overview.merge.commitTitle"), style = PullsType.sm, color = c.fade)
                TextInput(title, { title = it }, "", Modifier.fillMaxWidth(), enabled = !busy)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("overview.merge.commitMessage"), style = PullsType.sm, color = c.fade)
                TextInput(body, { body = it }, "", Modifier.fillMaxWidth(), singleLine = false, minLines = 6, enabled = !busy)
            }
        } else {
            Text(t("overview.merge.rebaseHint", "base" to overview.baseRef), style = PullsType.sm, color = c.fade)
        }
        data.mergeability.warning?.let { Text(t("overview.merge.$it"), style = PullsType.sm, color = WebColors.amberText(c.dark)) }
        top.actionError?.let { Text(t("overview.actions.failed", "message" to it), style = PullsType.sm, color = WebColors.red(c.dark)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ActionButton({ top.mergeOpen = false }, variant = ButtonVariant.Text, enabled = !busy) { Text(t("common.cancel")) }
            ActionButton(
                {
                    // Untouched fields stay unset so GitHub applies the repository's own defaults.
                    val sendTitle = title.takeIf { it != defaults.first }
                    val sendBody = body.takeIf { it != defaults.second }
                    top.run(session, "merge", onOk = { top.mergeOpen = false }) {
                        session.repo.prAction(overview.id, "merge", method = method, title = sendTitle, body = sendBody, expectedHeadOid = overview.headOid)
                    }
                },
                variant = ButtonVariant.Primary, icon = "git-merge-duotone", loading = busy,
                enabled = data.mergeability.ready && (method == "rebase" || title.isNotBlank()),
            ) { Text(t("overview.merge.confirm")) }
        }
    }
}

/**
 * Port of `CommitPicker.vue`: a chip naming the selection (All commits / Commit sha / n
 * commits) opening a menu with "All commits" and the compact commit list. A plain tap
 * picks and closes; a long-press extends the range and keeps the menu open.
 */
@Composable
fun CommitPicker(session: ReviewSession, overview: PrOverview, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    var open by remember { mutableStateOf(false) }
    val selection = session.selection?.let { CommitSelection(it.first, it.second) }
    val count = selection?.let { resolveCommitSelection(overview.commits, it) }?.let { it.last - it.first + 1 }
    val screen = LocalConfiguration.current.screenWidthDp.dp
    Box(modifier) {
        ChipButton({ open = !open }, label = t("overview.selection.picker")) {
            val fg = if (selection != null) c.activeText else c.fade
            PhIcon("git-commit-duotone", tint = fg, size = 16.dp)
            val text = when {
                selection == null -> t("overview.selection.all")
                selection.from == selection.to -> t("overview.selection.one", "sha" to selection.from.take(7))
                count != null -> tc("overview.selection.range", count)
                else -> "${selection.from.take(7)}..${selection.to.take(7)}"
            }
            Text(text, style = PullsType.sm.copy(fontFamily = if (selection != null && count == null && selection.from != selection.to) PullsType.mono else null), color = fg, maxLines = 1)
            PhIcon("caret-down", tint = fg, size = 12.dp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(8.dp),
            containerColor = c.base,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
            modifier = Modifier.width(minOf(512.dp, screen - 32.dp)).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(6.dp))
                        .clickable { session.selectCommits(null); open = false }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(t("overview.selection.all"), Modifier.weight(1f), style = PullsType.sm, color = if (selection == null) c.activeText else c.text)
                    Text("${overview.commits.size + overview.commitsHidden}", style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.text.copy(alpha = 0.5f))
                    if (selection == null) PhIcon("check", tint = c.activeText, size = 13.dp)
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).topBorder(c.border).padding(top = 1.dp))
                CommitList(
                    commits = overview.commits, hidden = overview.commitsHidden, url = overview.url, selection = selection, compact = true,
                    onSelect = { picked, extend -> session.selectCommits(picked?.pair); if (!extend) open = false },
                )
                if (overview.commits.size > 1) Text(t("overview.commitList.hint"), Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), style = PullsType.xs, color = c.text.copy(alpha = 0.5f))
            }
        }
    }
}
