package com.octaviantocan.pulls.ui.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Activity
import com.octaviantocan.pulls.data.Usage
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import kotlinx.coroutines.delay

/** `useNow({ interval: 1000 })`, ticking only while [running]. */
@Composable
internal fun rememberNow(running: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        now = System.currentTimeMillis()
        while (running) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The `ai-shimmer` text: a light band sweeping across at half/full opacity, 2s per pass. */
@Composable
internal fun Shimmer(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val c = LocalPullsColors.current
    val base = c.text.copy(alpha = 0.5f)
    val peak = c.text.copy(alpha = 0.95f)
    val transition = rememberInfiniteTransition(label = "ai-shimmer")
    val shift by transition.animateFloat(1f, -1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "shift")
    val width = 600f
    val brush = Brush.horizontalGradient(
        0.35f to base, 0.5f to peak, 0.65f to base,
        startX = shift * width, endX = shift * width + width * 2,
    )
    Text(text, modifier, style = style.copy(brush = brush), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** The web `AppModal`: a bottom sheet on phones, a centered dialog from `sm` up. */
@Composable
internal fun AiSheet(
    title: String,
    onDismiss: () -> Unit,
    description: String? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    scrollBody: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = com.octaviantocan.pulls.ui.design.AppModal(onDismiss, title = title, description = description, scrollBody = scrollBody, footer = footer, content = content)

/** `FormCheckbox`: a 16px box, primary fill and a white check when on, with a 44dp hit area. */
@Composable
internal fun AiCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, label: String? = null, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val c = LocalPullsColors.current
    Row(
        modifier.defaultMinSize(minHeight = TOUCH, minWidth = TOUCH).clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Checkbox, onClickLabel = contentDescription ?: label) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (label == null) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(if (checked) c.primary else c.raised)
                .border(1.dp, if (checked) c.primary else c.border, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) PhIcon("check-bold", tint = Color.White, size = 11.dp) }
        if (label != null) Text(label, style = PullsType.sm, color = c.text)
    }
}

/** `SeverityBadge`: `badge` tinted red (bug), amber (risk) or gray (nit). */
@Composable
internal fun SeverityBadge(severity: String) {
    val c = LocalPullsColors.current
    val color = when (severity) { "bug" -> c.error; "risk" -> c.amber; else -> c.gray }
    Text(
        t("critique.severity.$severity"),
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = PullsType.xs.copy(fontWeight = FontWeight.Medium, lineHeight = PullsType.xs.fontSize),
        color = if (severity == "nit") c.muted else color,
    )
}

/** `AiUsageLine`: model · tokens · cost · time, `text-xs tabular-nums op-mute`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiUsageLine(usage: Usage?, modifier: Modifier = Modifier, durationMs: Long? = null) {
    val c = LocalPullsColors.current
    val messages = LocalMessages.current
    fun tokens(key: String, n: Long?) = if (n != null && n > 0) messages.t("ai.usage.$key", "n" to formatTokens(n)) else null
    val u = usage ?: Usage()
    val duration = u.durationMs ?: durationMs
    val parts = listOfNotNull(
        u.model,
        tokens("input", u.inputTokens),
        tokens("output", u.outputTokens),
        tokens("cacheRead", u.cacheReadTokens),
        tokens("reasoning", u.reasoningTokens),
        // Codex reports no cost at all; an unset or zero cost is unknown, not free.
        u.costUsd?.takeIf { it > 0 }?.let(::formatCost),
        duration?.takeIf { it > 0 }?.let(::formatClockDuration),
    )
    if (parts.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Text("·", style = PullsType.xs, color = c.mute)
            Text(part, style = PullsType.xs, color = c.mute)
        }
    }
}

/** `AiWorkedFor`: "Worked for 2m 3s · Ran 3 commands" that unfolds the log. */
@Composable
private fun AiWorkedFor(label: String, summary: String, failed: Boolean, expandable: Boolean, open: Boolean, onToggle: () -> Unit, style: TextStyle, content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    val color = if (failed) c.error else c.fade
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(4.dp))
                .then(if (expandable) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PhIcon("clock", tint = color, size = 16.dp)
            Text(label, style = style, color = color, maxLines = 1)
            if (summary.isNotEmpty()) Text("· $summary", Modifier.weight(1f, fill = false), style = style, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (expandable) PhIcon(if (open) "caret-up" else "caret-down", tint = color, size = 13.dp)
        }
        if (expandable && open) content()
    }
}

/** `AiActivityRow`: icon, title (shimmering while running), newest line of prose, timing, unfoldable text. */
@Composable
private fun AiActivityRow(tracked: TrackedActivity, now: Long, warning: Boolean, compact: Boolean, style: TextStyle) {
    val c = LocalPullsColors.current
    val messages = LocalMessages.current
    val activity = tracked.activity
    var expanded by remember(activity.id) { mutableStateOf(false) }
    val running = activity.status == "running"
    val failed = activity.status == "failed"
    val prose = activity.kind == "thinking" || activity.kind == "text"
    val icon = when {
        failed -> "x-circle"
        warning -> "warning"
        activity.kind == "thinking" -> "brain"
        activity.kind == "text" -> "pencil-simple-line"
        activity.kind == "status" -> "info"
        else -> when (toolCategory(activity.tool)) {
            ToolCategory.Command -> "terminal"
            ToolCategory.Edit -> "pencil-simple"
            ToolCategory.Read -> "file-text"
            ToolCategory.Search -> "magnifying-glass"
            ToolCategory.Other -> "wrench"
        }
    }
    val fullText = activity.text?.trim().orEmpty()
    val preview = if (prose && !failed) lastLine(fullText) else ""
    val expandable = prose && !failed && fullText.isNotEmpty() && fullText != preview
    val timing = activityTiming(tracked, now)
    val timingLabel = timing?.takeIf { !(compact && it.state != TimingState.Idle) }?.let {
        val liveness = when (it.state) {
            TimingState.Idle -> messages.t("ai.idleFor", "since" to it.since)
            TimingState.Now -> messages.t("ai.activeNow")
            TimingState.Active -> messages.t("ai.activeAgo", "since" to it.since)
        }
        if (compact) liveness else "$liveness · ${messages.t("ai.elapsed", "elapsed" to it.elapsed)}"
    }
    val tone = when {
        failed -> c.error
        warning -> c.amber
        activity.kind == "status" -> c.mute
        running -> c.text
        else -> c.fade
    }
    val italic = warning || activity.kind == "status"
    val rowStyle = style.copy(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                .then(if (expandable) Modifier.defaultMinSize(minHeight = TOUCH).clickable(role = Role.Button) { expanded = !expanded } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PhIcon(icon, tint = tone, size = 16.dp)
            Row(Modifier.weight(1f)) {
                // Phones: two lines (`max-md:line-clamp-2`).
                if (running) Shimmer(activity.title, rowStyle, maxLines = 2)
                else Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(activity.title)
                        if (preview.isNotEmpty()) {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = c.mute))
                            append(" $preview")
                            pop()
                        }
                    },
                    style = rowStyle, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (timingLabel != null) Text(timingLabel, style = PullsType.xs, color = if (timing.state == TimingState.Idle) c.amber else c.mute, maxLines = 1)
            if (expandable) PhIcon(if (expanded) "caret-up" else "caret-down", tint = c.mute, size = 13.dp)
        }
        if (failed && fullText.isNotEmpty()) Text(fullText, Modifier.padding(start = 20.dp), style = PullsType.xs, color = c.error)
        if (expanded) {
            Box(Modifier.padding(start = 20.dp).fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(4.dp)).background(c.raised).verticalScroll(rememberScrollState()).padding(8.dp)) {
                SelectionContainer { Text(fullText, style = PullsType.xs, color = c.fade) }
            }
        }
    }
}

/**
 * `AiActivityLog`, complete: while [running], "Working for …", the newest rows (older ones
 * behind "+n more") and a shimmering tail; once settled, a "Worked for … · Ran 3 commands"
 * disclosure. [outcome] is `done`, `failed` or `stopped`.
 */
@Composable
internal fun AiActivityLogFull(
    activities: List<Activity>,
    running: Boolean,
    modifier: Modifier = Modifier,
    startedAt: Long? = null,
    endedAt: Long? = null,
    outcome: String = "done",
    compact: Boolean = false,
) {
    val c = LocalPullsColors.current
    val messages = LocalMessages.current
    val now = rememberNow(running)
    // Plain holder, not state: the tracked log is derived during composition and must not trigger it again.
    val log = remember { arrayOf(emptyList<TrackedActivity>()) }
    val tracked = remember(activities) { trackActivities(log[0], activities).also { log[0] = it } }
    var showAll by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val style = if (compact) PullsType.xs else PullsType.sm
    val cap = if (compact) 3 else 6
    val hidden = if (showAll) 0 else maxOf(0, tracked.size - cap)
    val start = startedAt ?: activities.firstOrNull()?.startedAt
    val end = endedAt ?: tracked.maxOfOrNull { it.activity.endedAt ?: it.seenAt }
    val duration = if (start == null) formatClockDuration(0) else formatClockDuration((if (running) now else end ?: start) - start)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhIcon("hammer", tint = c.fade, size = 16.dp)
                Text(messages.t("ai.workingFor", "duration" to duration), style = style, color = c.fade)
            }
            HorizontalDivider(color = c.border)
            if (hidden > 0 || showAll) {
                Text(
                    if (showAll) messages.t("ai.showLess") else messages.t("ai.more", "n" to hidden),
                    Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { showAll = !showAll }.padding(vertical = 12.dp),
                    style = PullsType.xs, color = c.mute,
                )
            }
            tracked.drop(hidden).forEachIndexed { index, row ->
                key(row.id) { AiActivityRow(row, now, isWarning(row.activity, index + hidden), compact, style) }
            }
            val last = activities.lastOrNull()
            val tail = when {
                last == null -> messages.t("ai.loading")
                last.status == "running" -> null
                else -> messages.t("ai.verb.thinking.now")
            }
            if (tail != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PhIcon("brain", tint = c.text, size = 16.dp)
                    Shimmer(tail, style)
                }
            }
        } else if (activities.isNotEmpty() || startedAt != null) {
            val label = when (outcome) {
                "failed" -> messages.t("ai.failedAfter", "duration" to duration)
                "stopped" -> messages.t("ai.stoppedAfter", "duration" to duration)
                else -> messages.t("ai.workedFor", "duration" to duration)
            }
            val summary = summarizeTools(activities).joinToString(", ") { (category, count) -> messages.tc("ai.groups.${category.key}", count) }
            AiWorkedFor(label, summary, outcome == "failed", activities.isNotEmpty(), open, { open = !open }, style) {
                Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tracked.forEachIndexed { index, row -> key(row.id) { AiActivityRow(row, now, isWarning(row.activity, index), compact, style) } }
                }
            }
            if (!compact) HorizontalDivider(color = c.border)
        }
    }
}

/** A red alert box: `border border-red-500/30 rounded bg-red-500/5 p-3`. */
@Composable
internal fun ErrorBox(title: String, message: String?) {
    val c = LocalPullsColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(c.error.copy(alpha = 0.05f)).border(1.dp, c.error.copy(alpha = 0.3f), RoundedCornerShape(4.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhIcon("x-circle", tint = c.error, size = 16.dp)
            Text(title, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.error)
        }
        if (!message.isNullOrBlank()) SelectionContainer { Text(message, style = PullsType.sm, color = c.error) }
    }
}

/** The user bubble of the chat and ask threads: `max-w-[85%] rounded-lg bg-active px-3 py-2 text-sm`. */
@Composable
internal fun UserBubble(text: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.CenterEnd) {
            SelectionContainer {
                Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(c.active).padding(horizontal = 12.dp, vertical = 8.dp), style = PullsType.sm, color = c.text)
            }
        }
    }
}
