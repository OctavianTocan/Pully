package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

/*
 * The one bar at the foot of the app (Q2). Pages do not draw their own search or Ask bar any more: each says what its
 * bar holds, and [FootBarLayer], drawn once over every page, keeps one pill in place while the pages slide under it. A
 * new page changes only what is inside the pill, its words fading out blurred and the next ones fading in, the way the
 * Inbox and Tours already share theirs ("one component that's kind of over the screen, and then we can swap the text
 * inside it with that nice blur fade ... we want that kind of seamlessness when we go into a PR as well": recording
 * 17 Oct 14:45, 01:06 to 01:51; Q2). A bar of another shape, the diff's file bar, takes the pill's place the same way.
 */

/** Every page's bar, newest last: the newest is the one shown, since a page that opens composes after the one it covers. */
@Stable
class FootBars {
    internal val slots = mutableStateListOf<FootSlot>()
    internal val top: FootSlot? get() = slots.lastOrNull()
}

/** The app's [FootBars], or none where a bar draws in place (the UI Book, a still). */
val LocalFootBars = staticCompositionLocalOf<FootBars?> { null }

/** The pill's field, so a tap anywhere on the pill puts the cursor in it rather than on the page under it (Q6). */
val LocalFootFocus = compositionLocalOf<FocusRequester?> { null }

/** One page's bar, as [FootPill] or [FootFree] last said. */
@Stable
internal class FootSlot {
    var key: Any by mutableStateOf(Unit)
    var free by mutableStateOf(false)
    var search by mutableStateOf(false)
    var button by mutableStateOf(false)
    var compact by mutableStateOf<String?>(null)
    var onExpand by mutableStateOf<(() -> Unit)?>(null)
    var veil by mutableStateOf<Modifier>(Modifier)
    var trailing by mutableStateOf<(@Composable () -> Unit)?>(null)
    var content by mutableStateOf<@Composable RowScope.() -> Unit>({})
    var freeContent by mutableStateOf<@Composable () -> Unit>({})
    val focus = FocusRequester()

    /** A tap opened the short pill: its field takes the cursor once it is there. */
    var focusOnOpen = false
}

/** The width of the short pill that reads "Ask" (Q5). */
private val COMPACT_WIDTH = 104.dp

/**
 * A page's bar in the one pill: [content] is what sits in it, after a search glass when [search], with the pill's end
 * made for a round button when [button]. [trailing] sits beside the pill, as the Inbox's sort does, and leaves with the
 * page. With [compact] the pill is short and reads that one word, centred, until a tap ([onExpand]) opens it to the full
 * width with its field focused ("a shorter bar that says Ask. And when you tap on it ... it kind of expands", Q5).
 * [veil] dims or holds the bar with its page. Outside a [FootBarLayer] the bar draws here, placed by [modifier].
 */
@Composable
fun FootPill(
    key: Any,
    modifier: Modifier = Modifier,
    search: Boolean = false,
    button: Boolean = false,
    compact: String? = null,
    onExpand: (() -> Unit)? = null,
    veil: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val bars = LocalFootBars.current
    val slot = remember { FootSlot() }
    val fill: FootSlot.() -> Unit = {
        this.key = key
        free = false
        this.search = search
        this.button = button
        this.compact = compact
        this.onExpand = onExpand
        this.veil = veil
        this.trailing = trailing
        this.content = content
    }
    if (bars == null) {
        slot.fill()
        Box(modifier.floatingBar()) { PillBar(slot, rememberMotionEnabled()) }
        return
    }
    SideEffect { slot.fill() }
    Register(bars, slot)
}

/** A page's bar of its own shape (the diff's ‹ file ›), in the pill's place at the foot. */
@Composable
fun FootFree(key: Any, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val bars = LocalFootBars.current
    if (bars == null) {
        Box(modifier.floatingBar()) { content() }
        return
    }
    val slot = remember { FootSlot() }
    SideEffect {
        slot.key = key
        slot.free = true
        slot.freeContent = content
    }
    Register(bars, slot)
}

@Composable
private fun Register(bars: FootBars, slot: FootSlot) {
    DisposableEffect(bars, slot) {
        bars.slots.add(slot)
        onDispose { bars.slots.remove(slot) }
    }
}

/**
 * The foot of the app: the pill of the page on top, or its own bar, resting where every bar rests ([floatingBar]). Draw
 * it over the pages and under anything that covers them (Settings, Commands, toasts).
 */
@Composable
fun FootBarLayer(bars: FootBars, modifier: Modifier = Modifier) {
    val moving = rememberMotionEnabled()
    val top = bars.top
    val pill = top?.takeIf { !it.free }
    // The pill last shown, kept while it fades out under a page with no bar or a bar of its own.
    val kept = remember { arrayOfNulls<FootSlot>(1) }
    if (pill != null) kept[0] = pill
    Box(modifier.floatingBar()) {
        AnimatedVisibility(pill != null, enter = barEnter(moving), exit = barExit(moving)) {
            kept[0]?.let { PillBar(it, moving) }
        }
        AnimatedContent(
            top?.takeIf { it.free },
            contentKey = { it?.key },
            transitionSpec = { blurFade(moving) },
            label = "foot-free",
        ) { slot ->
            if (slot != null) Box(Modifier.blurredBy(transition)) { slot.freeContent() }
        }
    }
}

/** The pill and what sits beside it. */
@Composable
private fun PillBar(slot: FootSlot, moving: Boolean) {
    val c = LocalPullsColors.current
    val short = slot.compact != null
    val open by animateFloatAsState(
        if (short) 0f else 1f,
        if (moving) tween(PullsMotion.MEDIUM, easing = PullsMotion.SmoothOut) else tween(0),
        label = "foot-open",
    )
    // The field takes the cursor once the pill a tap opened is there.
    LaunchedEffect(slot, short) {
        if (short || !slot.focusOnOpen) return@LaunchedEffect
        slot.focusOnOpen = false
        withFrameNanos { }
        runCatching { slot.focus.requestFocus() }
    }
    val trailing = slot.trailing
    val keptTrailing = remember { arrayOfNulls<@Composable () -> Unit>(1) }
    if (trailing != null) keptTrailing[0] = trailing
    Row(slot.veil.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val width: Dp = lerp(COMPACT_WIDTH.coerceAtMost(maxWidth), maxWidth, open)
            Box(
                Modifier.width(width).height(FieldHeight)
                    .floatShadow(CircleShape, PullsShadow.Search)
                    .clip(CircleShape).background(c.raised)
                    // A tap anywhere on the pill is the pill's: it opens a short one, or puts the cursor in the field, and
                    // never reaches the page under it ("I keep trying to tap on the ask about this pull request, and it
                    // keeps getting me to the description": recording 17 Oct 14:45, 03:00 to 03:10; Q6).
                    .pointerInput(slot) {
                        detectTapGestures {
                            val expand = slot.onExpand
                            if (slot.compact != null && expand != null) {
                                slot.focusOnOpen = true
                                expand()
                            } else {
                                runCatching { slot.focus.requestFocus() }
                            }
                        }
                    }
                    .then(
                        if (short) Modifier.semantics {
                            role = Role.Button
                            onClick(label = slot.compact) { slot.focusOnOpen = true; slot.onExpand?.invoke(); true }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    PillFace(slot, short),
                    contentKey = { it.key },
                    transitionSpec = { blurFade(moving) },
                    contentAlignment = Alignment.Center,
                    label = "foot-pill",
                ) { face ->
                    Box(Modifier.fillMaxSize().blurredBy(transition), contentAlignment = Alignment.Center) {
                        if (face.short) {
                            Text(face.slot.compact.orEmpty(), style = PullsType.base.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1)
                        } else {
                            Row(
                                Modifier.fillMaxSize().padding(start = FieldInset, end = if (face.slot.button) FieldButtonInset else FieldInset),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FieldGap),
                            ) {
                                if (face.slot.search) LuIcon(Lu.search, tint = c.muted, size = 20.dp)
                                androidx.compose.runtime.CompositionLocalProvider(LocalFootFocus provides face.slot.focus) { face.slot.content(this) }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            trailing != null,
            enter = if (!moving) EnterTransition.None else fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + expandHorizontally(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)),
            exit = if (!moving) ExitTransition.None else fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + shrinkHorizontally(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)),
        ) { keptTrailing[0]?.invoke() }
    }
}

/** What the pill shows: a page's field, or its one word while short. Short pills of any page are one face, so "Ask" never fades into "Ask". */
private class PillFace(val slot: FootSlot, val short: Boolean) {
    val key: Any get() = if (short) "short:${slot.compact}" else slot.key
}

/** Words leave fading out and blurring and the next arrive sharpening, in place: nothing slides, the pill stays (Q2). */
private fun <S> AnimatedContentTransitionScope<S>.blurFade(moving: Boolean): ContentTransform {
    if (!moving) return EnterTransition.None togetherWith ExitTransition.None
    return (fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) togetherWith fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)))
        .using(SizeTransform(clip = false))
}

/** The blur that goes with [blurFade]: sharp while shown, [PullsMotion.BlurMedium] on the way in or out. */
@Composable
private fun Modifier.blurredBy(transition: androidx.compose.animation.core.Transition<EnterExitState>): Modifier {
    val blur by transition.animateDp(transitionSpec = { tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut) }, label = "foot-blur") {
        if (it == EnterExitState.Visible) 0.dp else PullsMotion.BlurMedium
    }
    return softBlur(blur)
}

/** A bar arriving where there was none, and leaving with a page that has none: a quick fade. */
private fun barEnter(moving: Boolean): EnterTransition = if (!moving) EnterTransition.None else fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))
private fun barExit(moving: Boolean): ExitTransition = if (!moving) ExitTransition.None else fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut))
