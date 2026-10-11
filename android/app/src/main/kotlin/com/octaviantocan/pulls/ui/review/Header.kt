@file:OptIn(ExperimentalLayoutApi::class)

package com.octaviantocan.pulls.ui.review

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.ai.AnalyzeButton
import com.octaviantocan.pulls.ui.ai.CritiqueButton
import com.octaviantocan.pulls.ui.ai.ShareButton
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.DiffStats
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PrStatusIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.ToggleGroup
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.NavControls
import kotlinx.coroutines.launch

/** `ACTIONS_BOT_LOGIN` in core: a shared analysis posted by the workflow, not a person. */
private const val ACTIONS_BOT_LOGIN = "github-actions[bot]"

/**
 * Page-top UI state the web keeps in `PrOverviewContext` and `PrOverviewStore` (panel open
 * and tab, persisted like its `overview:open` / `overview:tab` localStorage; the action in
 * flight and its error) plus which sheet is open.
 */
@Stable
class ReviewTopUi(context: Context) {
    private val prefs = context.getSharedPreferences("pulls-review-top", Context.MODE_PRIVATE)

    var overviewOpen by mutableStateOf(prefs.getBoolean("overviewOpen", true))
        private set
    var overviewTab by mutableStateOf(prefs.getString("overviewTab", null) ?: "conversation")
        private set

    /** Bumped whenever something asks to bring the overview panel into view. */
    var focusRequest by mutableIntStateOf(0)
        private set

    /** `merge`, `update-branch`, `close`, `reopen`, `ready` or `draft` while it runs. */
    var busy by mutableStateOf<String?>(null)
    var actionError by mutableStateOf<String?>(null)

    var progressOpen by mutableStateOf(false)
    var reviewOpen by mutableStateOf(false)
    var mergeOpen by mutableStateOf(false)

    /** Overview polls since the last refresh (`MAX_POLLS` 20 in `pr-overview-store.ts`). */
    var polls by mutableIntStateOf(0)

    fun setOpen(open: Boolean) { overviewOpen = open; prefs.edit().putBoolean("overviewOpen", open).apply() }
    fun setTab(tab: String) { overviewTab = tab; prefs.edit().putString("overviewTab", tab).apply() }

    /** Opens the panel on [tab] and scrolls it into view. */
    fun show(tab: String) { setTab(tab); setOpen(true); focusRequest++ }

    /**
     * `PrOverviewStore.run`: one action at a time; on success the PR page reloads (diff,
     * overview, reviews) and [onOk] runs; on failure the message stays until dismissed.
     */
    fun run(session: ReviewSession, action: String, onOk: () -> Unit = {}, task: suspend () -> Unit) {
        if (busy != null) return
        busy = action
        actionError = null
        session.scope.launch {
            val result = runCatching { task() }
            busy = null
            result.onSuccess { polls = 0; session.refresh(); onOk() }
                .onFailure { actionError = it.message ?: it.toString() }
        }
    }
}

// --- Shared bits of the page top ---

/** UnoCSS palette stops the page-top templates name (`text-green-600 dark:text-green-400`...). */
internal object WebColors {
    fun green(dark: Boolean) = Color(if (dark) 0xFF4ADE80 else 0xFF16A34A)
    fun red(dark: Boolean) = Color(if (dark) 0xFFF87171 else 0xFFDC2626)
    val amber500 = Color(0xFFF59E0B)
    /** `text-amber-600 dark:text-amber-400`. */
    fun amberIcon(dark: Boolean) = Color(if (dark) 0xFFFBBF24 else 0xFFD97706)
    /** `text-amber-700 dark:text-amber-400`. */
    fun amberText(dark: Boolean) = Color(if (dark) 0xFFFBBF24 else 0xFFB45309)
    /** `text-orange-700 dark:text-orange-400`. */
    fun orange(dark: Boolean) = Color(if (dark) 0xFFFB923C else 0xFFC2410C)
    /** `text-primary-600 dark:text-primary-400`. */
    fun primary(dark: Boolean) = Color(if (dark) 0xFF7EB267 else 0xFF49833E)
    val purple500 = Color(0xFFA855F7)
}

/** A Phosphor icon and how the web tints it: a color, `op-fade` or `op-mute`. */
internal data class Glyph(val name: String, val tint: @Composable () -> Color)

@Composable private fun fadeTint() = LocalPullsColors.current.fade
@Composable private fun muteTint() = LocalPullsColors.current.text.copy(alpha = 0.5f)

/** `CHECKS_ICON` in `status-icons.ts`, keyed by `ChecksStatus`. */
internal fun checksGlyph(state: String?): Glyph? = when (state) {
    "success" -> Glyph("check-bold") { WebColors.green(LocalPullsColors.current.dark) }
    "failure" -> Glyph("x-bold") { WebColors.red(LocalPullsColors.current.dark) }
    "pending" -> Glyph("circle-fill") { WebColors.amber500 }
    else -> null
}

/** `CHECK_BUCKET_ICON`. */
internal fun bucketGlyph(bucket: String): Glyph = when (bucket) {
    "failing" -> checksGlyph("failure")!!
    "pending" -> checksGlyph("pending")!!
    "passing" -> checksGlyph("success")!!
    else -> Glyph("prohibit") { muteTint() }
}

/** `REVIEW_DECISION_ICON`. */
internal fun decisionGlyph(decision: String?): Glyph? = when (decision) {
    "approved" -> Glyph("check-circle-duotone") { WebColors.green(LocalPullsColors.current.dark) }
    "changes_requested" -> Glyph("x-circle-duotone") { WebColors.red(LocalPullsColors.current.dark) }
    "review_required" -> Glyph("eye-duotone") { fadeTint() }
    else -> null
}

/** `REVIEW_DISPLAY` / `STATE_DISPLAY` / `REVIEW_ICON`: one review's state. */
internal fun reviewGlyph(state: String): Glyph = when (state) {
    "approved" -> decisionGlyph("approved")!!
    "changes_requested" -> decisionGlyph("changes_requested")!!
    "dismissed" -> Glyph("prohibit") { muteTint() }
    "pending" -> Glyph("hourglass-duotone") { muteTint() }
    else -> Glyph("chat-circle-dots-duotone") { fadeTint() }
}

@Composable
internal fun GlyphIcon(glyph: Glyph, size: Dp = 16.dp, modifier: Modifier = Modifier) = PhIcon(glyph.name, modifier, tint = glyph.tint(), size = size)

/** `DisplayDonut`: a progress ring, track at `op-mute`, value in `color-active`. */
@Composable
internal fun HeaderDonut(value: Float, size: Dp = 18.dp, thickness: Dp = 3.dp, color: Color = LocalPullsColors.current.activeText, track: Color = LocalPullsColors.current.text) {
    Canvas(Modifier.size(size)) {
        val stroke = thickness.toPx()
        val inset = stroke / 2
        val arc = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(track.copy(alpha = 0.5f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        val sweep = 360f * value.coerceIn(0f, 1f)
        if (sweep > 0f) drawArc(color, -90f, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A bordered chip button (`flex items-center gap-1.5 border border-base rounded px-2 py-0.5 coarse:min-h-11`). */
@Composable
internal fun ChipButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null, content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        modifier
            .defaultMinSize(minHeight = TOUCH)
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .border(1.dp, c.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** `DisplayNumberBadge`: a small muted count pill. */
@Composable
internal fun NumberBadge(value: Int, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Text(
        "$value",
        modifier.clip(RoundedCornerShape(50)).background(if (c.dark) Color(0xFF2A2A2A) else Color(0xFFEDEDED)).padding(horizontal = 5.dp, vertical = 1.dp),
        style = PullsType.micro.copy(fontWeight = FontWeight.Medium),
        color = c.text,
    )
}

/** What the header names: the PR (from the overview once loaded), else the opened diff. */
private data class HeaderDisplay(val title: String, val label: String, val url: String, val state: String, val base: String, val head: String, val authorLogin: String?, val authorAvatar: String?)

/**
 * Port of `DiffsHeader.vue` (phone order: icon and controls on the first row, the title on a
 * row of its own, then "Analyze by"; the `md:` layout puts the title inline), with
 * `PrHeaderChips`, `DiffGroupNav` and `DiffPrMeta`.
 */
@Composable
fun ReviewHeader(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?, nav: Nav, visibleGroups: Set<String> = emptySet(), modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    val settings = session.settings
    val opened = session.prOpened ?: session.opened ?: return
    val pr = data?.overview
    val display = if (pr != null) {
        HeaderDisplay(pr.title, "#${pr.number}", pr.url, pr.state, pr.baseRef, pr.headRef, pr.author?.login, pr.author?.avatarUrl)
    } else {
        HeaderDisplay(opened.title, opened.label, opened.url, opened.state, opened.baseRef, opened.headRef, opened.author?.login?.takeIf { it.isNotBlank() }, opened.author?.avatarUrl)
    }
    val parent = when (val target = session.target) {
        is OpenTarget.Pull -> target.owner to target.repo
        is OpenTarget.Compare -> target.owner to target.repo
        is OpenTarget.Commit -> target.owner to target.repo
        else -> null
    }
    val sharedBy = session.saved?.analysis?.sharedBy

    BoxWithConstraints(
        modifier.fillMaxWidth().background(c.base).windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        val wide = maxWidth >= 768.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // First row.
            val homeIcon: @Composable () -> Unit = {
                Box(
                    Modifier.negativeStart(12.dp).size(TOUCH).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = "Home") { nav.home() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (display.state.isNotBlank()) PrStatusIcon(display.state) else PhIcon("house-line-duotone", size = 20.dp)
                }
            }
            val title: @Composable (Modifier) -> Unit = { m ->
                FlowRow(m, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    Text(display.title, Modifier.align(Alignment.CenterVertically), style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    if (display.label.isNotBlank()) {
                        Box(Modifier.align(Alignment.CenterVertically).defaultMinSize(minHeight = TOUCH).clickable(enabled = display.url.isNotBlank()) { uri.openUri(display.url) }, contentAlignment = Alignment.Center) {
                            Text(display.label, style = PullsType.base, color = c.fade)
                        }
                    }
                    IconButton("arrows-clockwise-duotone", t("common.refresh"), { top.polls = 0; session.refresh() }, Modifier.align(Alignment.CenterVertically))
                }
            }
            val analyzeBy: @Composable () -> Unit = {
                if (session.hasAiResult) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t("pr.analyzeBy"), style = PullsType.sm, color = c.fade)
                        ToggleGroup(
                            options = listOf("rules" to t("pr.rules"), "ai" to t("pr.ai")),
                            value = session.analyzeMode,
                            onChange = { session.analyzeMode = it },
                            icons = mapOf("ai" to "sparkle-duotone"),
                        )
                    }
                }
            }
            val threadsToggle: @Composable () -> Unit = {
                if (session.reviewData != null) {
                    val show = settings.showThreads
                    IconButton(
                        if (show) "chats-duotone" else "chats",
                        t(if (show) "review.hideComments" else "review.showComments"),
                        { settings.updateShowThreads(!show) },
                        tint = if (show) c.text else c.fade,
                    )
                }
            }
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    homeIcon()
                    title(Modifier.weight(1f))
                    analyzeBy()
                    threadsToggle()
                    NavControls(nav)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    homeIcon()
                    Spacer(Modifier.weight(1f))
                    threadsToggle()
                    NavControls(nav)
                }
                title(Modifier.fillMaxWidth())
                analyzeBy()
            }

            // Second row: repo, author, branches, chips, AI actions.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (parent != null) {
                    Box(Modifier.align(Alignment.CenterVertically).defaultMinSize(minHeight = TOUCH).clickable(role = Role.Button) { nav.openRepo(parent.first, parent.second) }, contentAlignment = Alignment.CenterStart) {
                        Text("${parent.first}/${parent.second}", style = PullsType.sm, color = c.fade)
                    }
                }
                if (display.authorLogin != null) {
                    Row(Modifier.align(Alignment.CenterVertically).defaultMinSize(minHeight = TOUCH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t("pr.by"), style = PullsType.sm, color = c.fade)
                        GithubAvatar(display.authorLogin, avatarUrl = display.authorAvatar, size = 16.dp)
                        Text(display.authorLogin, style = PullsType.sm, color = c.fade)
                    }
                }
                if (display.base.isNotBlank() && display.head.isNotBlank()) {
                    Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RefChip(display.base, Modifier.weight(1f, fill = false))
                        Text("←", style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text)
                        RefChip(display.head, Modifier.weight(1f, fill = false))
                    }
                }
                if (data != null) PrHeaderChips(session, top, data.overview, Modifier.align(Alignment.CenterVertically))
                if (sharedBy != null && session.analyzeMode != "rules" && session.hasAiResult) {
                    Row(
                        Modifier.align(Alignment.CenterVertically).border(1.dp, c.border, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (sharedBy == ACTIONS_BOT_LOGIN) Text(t("pr.sharedAnalysis"), style = PullsType.sm, color = c.text)
                        else {
                            Text(t("pr.sharedBy"), style = PullsType.sm, color = c.text)
                            GithubAvatar(sharedBy, size = 16.dp)
                            Text(sharedBy, style = PullsType.sm, color = c.text)
                        }
                    }
                }
                AnalyzeButton(session, Modifier.align(Alignment.CenterVertically))
                CritiqueButton(session, Modifier.align(Alignment.CenterVertically))
                ShareButton(session, Modifier.align(Alignment.CenterVertically))
            }

            // Third row: group nav, then the totals and review actions (`DiffPrMeta`, `ml-auto self-end`).
            FlowRow(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupNav(session, visibleGroups, Modifier.weight(1f).defaultMinSize(minWidth = 240.dp))
                Box(Modifier.weight(1f, fill = false).align(Alignment.Bottom).padding(top = 8.dp)) { PrMeta(session, top) }
            }
        }
    }
}

/** A negative start margin (`-ml-3`): the box draws [by] further left and takes that much less room. */
internal fun Modifier.negativeStart(by: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx()
    layout((placeable.width - shift).coerceAtLeast(0), placeable.height) { placeable.place(-shift, 0) }
}

/** The `bg-code` mono branch chip (`truncate border border-base rounded px-2 py-0.5 text-xs font-mono`). */
@Composable
private fun RefChip(name: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Text(
        name,
        modifier.clip(RoundedCornerShape(4.dp)).background(c.code).border(1.dp, c.border, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = PullsType.xs.copy(fontFamily = PullsType.mono),
        color = c.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `PrHeaderChips`: checks counts and the conversation chip (both open the overview), then the commit picker. */
@Composable
private fun PrHeaderChips(session: ReviewSession, top: ReviewTopUi, overview: PrOverview, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (overview.checksSummary.total > 0) {
            ChipButton({ top.show("checks") }, label = overview.checksSummary.state?.let { t("pulls.checks.$it") }) {
                Text(t("overview.tab.checks"), style = PullsType.sm, color = c.fade)
                CheckCounts(overview.checksSummary)
            }
        }
        ChipButton({ top.show("conversation") }, label = t("overview.tab.conversation")) {
            decisionGlyph(overview.reviewDecision)?.let { GlyphIcon(it) }
            overview.reviewDecision?.let { Text(t("pulls.review.$it"), style = PullsType.sm, color = c.fade) }
            PhIcon("chat-circle-text-duotone", tint = c.fade, size = 16.dp)
            Text("${overview.conversationCount}", style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.fade)
        }
    }
    CommitPicker(session, overview, modifier)
}

/** `DiffPrMeta`: total +/-, the reviewed donut (opens the progress sheet) and "Add Review". */
@Composable
fun PrMeta(session: ReviewSession, top: ReviewTopUi, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val files = session.files
    val reviewedCount = files.count { it.sha in session.reviewed }
    val progress = if (files.isEmpty()) 1f else reviewedCount.toFloat() / files.size
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiffStats(files.sumOf { it.additions }, files.sumOf { it.deletions })
        Row(
            Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClickLabel = t("reviewProgress.title")) { top.progressOpen = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeaderDonut(progress, 18.dp, 3.dp)
            Text("$reviewedCount", style = PullsType.sm, color = c.text)
            Text(t("pr.reviewedOf", "total" to files.size), style = PullsType.xs, color = c.text.copy(alpha = 0.5f), maxLines = 1)
        }
        if (session.pull != null && session.reviewData?.viewer != null) {
            val pending = session.reviewData?.pendingCount ?: 0
            Box {
                ActionButton({ top.reviewOpen = true }, icon = "chat-centered-text-duotone", small = true) {
                    Text(t("review.addReview"), style = PullsType.sm, maxLines = 1)
                }
                if (pending > 0) NumberBadge(pending, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp))
            }
        }
    }
}
