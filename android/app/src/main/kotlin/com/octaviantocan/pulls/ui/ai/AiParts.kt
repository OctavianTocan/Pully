package com.octaviantocan.pulls.ui.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.octaviantocan.pulls.data.Activity
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuGlyph
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import com.octaviantocan.pulls.ui.design.softBlur
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import kotlinx.coroutines.delay
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import com.octaviantocan.pulls.ui.review.ReviewSession
import androidx.compose.foundation.layout.Spacer

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

/**
 * The thinking shimmer, as Perplexity does it (rule 72): the content sits at full strength and a
 * tilted band dims it to a third as it sweeps left to right, 1200ms a pass, looping. With system
 * animations off nothing sweeps. Apply it to a row to shimmer verb and seconds as one unit.
 */
@Composable
internal fun Modifier.shimmer(): Modifier {
    val moving = rememberMotionEnabled()
    val transition = rememberInfiniteTransition(label = "ai-shimmer")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    if (!moving) return this
    // raw-value-ok: an alpha mask that takes opacity away from the content, never drawn as a colour
    val dim = Color.Black.copy(alpha = 0.67f)
    return this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val band = size.width * BAND_SHARE
        val x = -band + phase * (size.width + band)
        // The gradient's axis runs 20 degrees off horizontal, which tilts the band 20 degrees off vertical.
        drawRect(
            Brush.linearGradient(0f to Color.Transparent, 0.5f to dim, 1f to Color.Transparent, start = Offset(x, 0f), end = Offset(x + band, band * BAND_TILT)),
            blendMode = BlendMode.DstOut,
        )
    }
}

private const val SHIMMER_MS = 1200
private const val BAND_SHARE = 0.45f
private const val BAND_TILT = 0.36f // tan(20 degrees)

/** A line of text that shimmers while it is live. */
@Composable
internal fun Shimmer(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(text, modifier.shimmer(), style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * A line that is replaced by the next one swaps like transitions.dev's Thinking states: the old
 * line leaves upward through a 2px blur while the new one rises in from below, both at once in
 * 150ms ease-in-out, 8dp of travel, the arrival held back 50ms.
 */
@Composable
internal fun ThinkingSwap(target: String, modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    val travel = with(LocalDensity.current) { PullsMotion.SlideDistance.roundToPx() }
    val motion = rememberMotionEnabled()
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (!motion) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            val enter = fadeIn(tween(PullsMotion.QUICK, delayMillis = SWAP_GAP_MS, easing = PullsMotion.InOut)) +
                slideInVertically(tween(PullsMotion.QUICK, delayMillis = SWAP_GAP_MS, easing = PullsMotion.InOut)) { travel }
            val exit = fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.InOut)) +
                slideOutVertically(tween(PullsMotion.QUICK, easing = PullsMotion.InOut)) { -travel }
            (enter togetherWith exit) using SizeTransform(clip = false)
        },
        label = "thinking-swap",
    ) { line ->
        val blur by transition.animateDp(transitionSpec = { tween(PullsMotion.QUICK, easing = PullsMotion.InOut) }, label = "swap-blur") {
            if (it == EnterExitState.Visible) 0.dp else PullsMotion.BlurSmall
        }
        Box(Modifier.softBlur(blur)) { content(line) }
    }
}

private const val SWAP_GAP_MS = 50

/**
 * The one line above a running log (rule 72): a present-tense verb in the secondary colour and the
 * elapsed seconds in the tertiary one, in tabular digits so the number does not jitter. Verb and
 * seconds shimmer together.
 */
@Composable
internal fun ThinkingHeader(verb: String, seconds: String, style: TextStyle, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Row(modifier.shimmer(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThinkingSwap(verb, Modifier.weight(1f, fill = false)) { Text(it, style = style, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(seconds, style = style.copy(fontFeatureSettings = "tnum"), color = c.mute, maxLines = 1)
    }
}

/** Where a step line's text starts. The dot sits 7dp in, so its centre is 10dp from the margin. */
private val STEP_INDENT = 28.dp

/**
 * What a step did, as the icon in front of its line (rule 72): a file read, a search, a command, an edit, thinking,
 * writing the answer, another tool, or the engine itself for a status line. A failed step shows the cross, a notice
 * (a retry after a rate limit) the retry arrows.
 */
internal fun stepGlyph(activity: Activity, warning: Boolean): LuGlyph = when {
    activity.status == "failed" -> Lu.fail
    warning -> Lu.refresh
    activity.kind == "thinking" -> Lu.think
    activity.kind == "text" -> Lu.write
    activity.kind == "status" -> Lu.cog
    else -> when (toolCategory(activity.tool)) {
        ToolCategory.Read -> Lu.file
        ToolCategory.Search -> Lu.search
        ToolCategory.Command -> Lu.terminal
        ToolCategory.Edit -> Lu.edit
        ToolCategory.Other -> Lu.tool
    }
}

/** The icon in front of a step line: 16dp, centred on the first line of text, its centre 10dp in (rule 72). */
@Composable
private fun StepIcon(glyph: LuGlyph, color: Color, style: TextStyle) {
    val lineHeight = with(LocalDensity.current) { (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f).toDp() }
    Box(Modifier.width(STEP_INDENT).height(maxOf(lineHeight, 16.dp)), contentAlignment = Alignment.CenterStart) {
        LuIcon(glyph, Modifier.padding(start = 2.dp), tint = color, size = 16.dp)
    }
}

/**
 * A finding's first line: the severity as a word in its colour (red bug, amber risk, muted nit), then the title, both
 * at body size and wrapping as one sentence. No tinted tag (rules 14 and 49).
 */
@Composable
internal fun FindingHeading(severity: String, title: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val color = when (severity) { "bug" -> c.error; "risk" -> c.amber; else -> c.muted }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(t("critique.severity.$severity")) }
            append("  ")
            append(title)
        },
        modifier, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text,
    )
}

/** What a finding suggests, under a quiet label, as the diff's code view coloured for the file's language (rule 95). */
@Composable
internal fun FindingSuggestion(session: ReviewSession, path: String, suggestion: String) {
    val c = LocalPullsColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t("critique.suggestion"), style = PullsType.sm, color = c.muted)
        AiCodeView(session, suggestion, path.substringAfterLast('.', ""))
    }
}

/** `AiWorkedFor`: "Worked for 2m 3s · Ran 3 commands", a button with a chevron (right folded, down open) that unfolds the log. */
@Composable
private fun AiWorkedFor(label: String, summary: String, failed: Boolean, expandable: Boolean, open: Boolean, onToggle: () -> Unit, style: TextStyle, content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    val color = if (failed) c.error else c.fade
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(4.dp))
                .then(if (expandable) Modifier.cueClickable(role = Role.Button, onClick = onToggle) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = style, color = color, maxLines = 1)
            if (summary.isNotEmpty()) Text("· $summary", Modifier.weight(1f, fill = false), style = style, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (expandable) LuIcon(if (open) Lu.chevd else Lu.chev, tint = color, size = 14.dp)
        }
        if (expandable && open) content()
    }
}

/** `AiActivityRow`: the icon of what the step did, the step as one sentence (shimmering while it runs), timing, unfoldable text. Never italic (rule 72). */
@Composable
private fun AiActivityRow(tracked: TrackedActivity, now: Long, warning: Boolean, compact: Boolean, style: TextStyle) {
    val c = LocalPullsColors.current
    val messages = LocalMessages.current
    val activity = tracked.activity
    var expanded by remember(activity.id) { mutableStateOf(false) }
    val running = activity.status == "running"
    val failed = activity.status == "failed"
    val prose = activity.kind == "thinking" || activity.kind == "text"
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
        else -> c.muted
    }
    val ink = when {
        failed -> c.error
        warning -> c.amber
        else -> c.muted
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                .then(if (expandable) Modifier.defaultMinSize(minHeight = TOUCH).cueClickable(role = Role.Button) { expanded = !expanded } else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                StepIcon(stepGlyph(activity, warning), ink, style)
                Row(Modifier.weight(1f)) {
                    // Phones: two lines (`max-md:line-clamp-2`).
                    if (running) Shimmer(activity.title, style, tone, maxLines = 2)
                    else Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(activity.title)
                            if (preview.isNotEmpty()) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = c.mute))
                                append(" $preview")
                                pop()
                            }
                        },
                        style = style, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (timingLabel != null) Text(timingLabel, Modifier.padding(start = 6.dp), style = PullsType.sm, color = if (timing.state == TimingState.Idle) c.amber else c.mute, maxLines = 1)
                if (expandable) LuIcon(if (expanded) Lu.chevd else Lu.chev, Modifier.padding(start = 6.dp, top = 3.dp), tint = c.mute, size = 14.dp)
            }
        }
        if (failed && fullText.isNotEmpty()) Text(fullText, Modifier.padding(start = STEP_INDENT), style = PullsType.sm, color = c.error)
        if (expanded) {
            Box(Modifier.padding(start = STEP_INDENT).fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(4.dp)).background(c.raised).verticalScroll(rememberScrollState()).padding(8.dp)) {
                SelectionContainer { Text(fullText, style = PullsType.sm, color = c.muted) }
            }
        }
    }
}

/**
 * `AiActivityLog`, complete (rule 72). While [running]: one shimmering line, a verb with the elapsed
 * seconds ("Working 12s"; "Thinking" before the first step, or [workingVerb] when given), the newest
 * steps under it, each after the icon of what it did (older ones behind "+n more"), and the live orb
 * on the icons' column. When the work stops the orb finishes its turn and settles before it leaves. Once settled: a "Worked for
 * … · Ran 3 commands" button that unfolds the steps. [outcome] is `done`, `failed` or `stopped`.
 * [hint] puts three grey bars under the orb while it runs, a sign of where the answer will arrive
 * (rule 71): they sit under the live mark and never stand in for it. A screen that draws the orb
 * itself, where the log is replaced when the work ends, passes `mark = false` and puts
 * [AiLiveMark] in a place that stays. With [onStop] (from `guardedStop`) the orb is the Stop button
 * at the end of the shimmering line instead (rule 87): there is never a Stop pill beside the log.
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
    workingVerb: String? = null,
    hint: Boolean = false,
    mark: Boolean = true,
    onStop: (() -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    val messages = LocalMessages.current
    val motion = rememberMotionEnabled()
    val now = rememberNow(running)
    // Plain holder, not state: the tracked log is derived during composition and must not trigger it again.
    val log = remember { arrayOf(emptyList<TrackedActivity>()) }
    val tracked = remember(activities) { trackActivities(log[0], activities).also { log[0] = it } }
    var showAll by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    // Compact keeps fewer steps, not smaller text: nothing is set below 15 (rule 14).
    val style = PullsType.sm
    val cap = if (compact) 3 else 6
    val hidden = if (showAll) 0 else maxOf(0, tracked.size - cap)
    val start = startedAt ?: activities.firstOrNull()?.startedAt
    val end = endedAt ?: tracked.maxOfOrNull { it.activity.endedAt ?: it.seenAt }
    val duration = if (start == null) formatClockDuration(0) else formatClockDuration((if (running) now else end ?: start) - start)

    Column(
        modifier.animateContentSize(if (motion) tween(PullsMotion.SLOW, easing = PullsMotion.SmoothOut) else snap()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (running) {
            val verb = workingVerb ?: if (activities.isEmpty()) messages.t("ai.verb.thinking.now") else messages.t("ai.working")
            if (onStop != null) Row(verticalAlignment = Alignment.CenterVertically) {
                ThinkingHeader(verb, duration, style, Modifier.weight(1f))
                StopOrb(onStop)
            } else ThinkingHeader(verb, duration, style)
            if (hidden > 0 || showAll) {
                Text(
                    if (showAll) messages.t("ai.showLess") else messages.t("ai.more", "n" to hidden),
                    Modifier.padding(start = STEP_INDENT).defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(4.dp)).cueClickable(role = Role.Button) { showAll = !showAll }.padding(vertical = 12.dp),
                    style = PullsType.sm, color = c.muted,
                )
            }
            tracked.drop(hidden).forEachIndexed { index, row ->
                key(row.id) { AiActivityRow(row, now, isWarning(row.activity, index + hidden), compact, style) }
            }
        } else if (activities.isNotEmpty() || startedAt != null) {
            val label = when (outcome) {
                "failed" -> messages.t("ai.failedAfter", "duration" to duration)
                "stopped" -> messages.t("ai.stoppedAfter", "duration" to duration)
                else -> messages.t("ai.workedFor", "duration" to duration)
            }
            val summary = summarizeTools(activities).joinToString(", ") { (category, count) -> messages.tc("ai.groups.${category.key}", count) }
            AiWorkedFor(label, summary, outcome == "failed", activities.isNotEmpty(), open, { open = !open }, style) {
                // The icons make the column the steps hang from, so no line runs through them.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tracked.forEachIndexed { index, row -> key(row.id) { AiActivityRow(row, now, isWarning(row.activity, index), compact, style) } }
                }
            }
        }
        // One call site for both states, so the orb that was running is the one that settles.
        if (mark) AiLiveMark(running, hint, orb = onStop == null)
        if (!running && !compact && (activities.isNotEmpty() || startedAt != null)) Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.border))
    }
}

/**
 * The live mark of AI work, for a [ColumnScope]: the orb under the newest line, centred on the icons'
 * column (10dp in), and with [hint] the three grey bars of rule 71 under it. When [active] turns false
 * the orb finishes its turn, settles into its tick and stays a moment before it leaves; a mark that is
 * first drawn inactive never shows one. It has to stay in one place of the composition for the orb that
 * ran to be the one that settles.
 */
@Composable
internal fun ColumnScope.AiLiveMark(active: Boolean, hint: Boolean = false, orb: Boolean = true) {
    val motion = rememberMotionEnabled()
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) lingering = true
        else if (lingering) {
            delay(ORB_LINGER_MS)
            lingering = false
        }
    }
    // What sits below it glides up as the orb and the bars leave, instead of jumping.
    val leave = if (motion) fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + shrinkVertically(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) else ExitTransition.None
    // Without [orb] the Stop button elsewhere is the orb, and only the bars are drawn here.
    if (orb) AnimatedVisibility(active || lingering, enter = EnterTransition.None, exit = leave) {
        Orb(Modifier.padding(start = 2.dp), size = 16.dp, done = !active)
    }
    AnimatedVisibility(active && hint, exit = leave) {
        Column(Modifier.padding(start = STEP_INDENT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HINT_BAR_WIDTHS.forEach { width -> Skeleton(Modifier.fillMaxWidth(width).height(14.dp)) }
        }
    }
}

/** How long the orb stays after the work stops: its last turn (1600ms) and the settle. */
private const val ORB_LINGER_MS = 2300L

/** The three hint bars of rule 71, the second and third a little shorter than the first. */
private val HINT_BAR_WIDTHS = listOf(1f, 0.86f, 0.7f)

/**
 * What failed, in the kit's words: the title in the error colour and the reason under it, selectable, with no box,
 * border or cross (rule 49). [onRetry] puts the try-again arrow at the end of the title, labelled [retryLabel] (rule 87).
 */
@Composable
internal fun ErrorBox(title: String, message: String?, onRetry: (() -> Unit)? = null, retryLabel: String = t("common.retry")) {
    val c = LocalPullsColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = PullsType.base.copy(fontWeight = FontWeight.Medium), color = c.error)
            if (onRetry != null) FloatIcon(Lu.redo, retryLabel, onRetry, flat = true, iconSize = 22.dp, tint = c.muted)
        }
        if (!message.isNullOrBlank()) SelectionContainer { Text(message, style = PullsType.sm, color = c.muted) }
    }
}

/** The orb as the Stop button (rule 87): the turning ring round a small square, in a 44dp target. [onStop] comes from `guardedStop`. */
@Composable
internal fun StopOrb(onStop: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(TOUCH).clip(CircleShape).cueClickable(role = Role.Button, onClickLabel = t("common.stop"), onClick = onStop),
        contentAlignment = Alignment.Center,
    ) { Orb(size = 22.dp, stop = true) }
}

/**
 * Your own message in Ask and in a question on lines of the diff, the one bubble there is (rule 99): on the right, at
 * most 85% wide, white on the accent fill, its corner by the edge pinched to point at you. The AI's reply is plain text.
 */
@Composable
internal fun UserBubble(text: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(topStart = PullsRadius.card, topEnd = PullsRadius.card, bottomEnd = PullsRadius.sm, bottomStart = PullsRadius.card)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.CenterEnd) {
            SelectionContainer {
                Text(
                    text,
                    Modifier.clip(shape).background(c.primary).padding(horizontal = 14.dp, vertical = 10.dp),
                    style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f),
                    color = Color.White, // raw-value-ok: the ink on the primary fill is white in both themes
                )
            }
        }
    }
}
