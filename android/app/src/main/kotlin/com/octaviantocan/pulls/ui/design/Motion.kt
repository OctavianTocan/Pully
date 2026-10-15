package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.ScrollState
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.core.BACKWARD
import com.octaviantocan.pulls.core.FORWARD
import com.octaviantocan.pulls.core.backWay
import com.octaviantocan.pulls.core.pageWay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Motion, from transitions.dev: one smooth-out curve, a short list of durations, and a little
 * blur and scale on things that appear. Menus and modals scale from just under full size and grow
 * out of their trigger; a page change slides eight pixels through a 3px blur; text swaps through
 * 2px. Closing is quicker than opening. Everything moves with transform, opacity and blur.
 */
object PullsMotion {
    const val STAGGER = 40
    const val MICRO = 80
    const val QUICK = 150
    const val FAST = 250
    const val MEDIUM = 350
    const val SLOW = 400
    const val VERY_SLOW = 500

    /** One pass of the skeleton's light band (rule 85). */
    const val SHIMMER = 1200

    /** `--ease-smooth-out`: modals, dropdowns, panels, page slides and resizes. */
    val SmoothOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** `ease-in-out`: icon and text swaps. */
    val InOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Gathering speed to the end: a page leaving the whole width, so the next one arriving on [SmoothOut] carries on its speed (H36). */
    val Accelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** A dropdown opens from 0.97 and closes to 0.99. */
    const val DROPDOWN_OPEN_SCALE = 0.97f
    const val DROPDOWN_CLOSE_SCALE = 0.99f

    /** A modal opens and closes through 0.96. */
    const val MODAL_SCALE = 0.96f

    /** An icon swap starts and ends at a quarter size. */
    const val ICON_SCALE = 0.25f

    val BlurSmall = 2.dp
    val BlurMedium = 3.dp

    /** The most an edge fade blurs, at the edge itself (rule 74). */
    val EdgeBlur = 9.dp

    /** How deep the fade under a pinned header goes, and under a bottom pill (rule 74). */
    val EdgeTop = 32.dp
    val EdgeBottom = 120.dp

    /** How far a page slides in (`--distance-base`). */
    val SlideDistance = 8.dp

    /** How far a finger can drag the rows of a page, and where they have faded most (rule 88). */
    val FingerRange = 96.dp

    /** The room between two pages that sit side by side, past the screen's own margins (H20, H36). */
    val PageGap = 32.dp

    /** A throw faster than this, per second, changes the page however short the drag was (rule 105). */
    val Fling = 500.dp

    /**
     * How a sheet comes up and settles (H3): a spring that goes a little past its stop and comes back, instead of a
     * straight glide. Damping 0.74 overshoots by about 3% of the way, a fifth less than the 0.72 that was "a bit much"
     * (J1), and stiffness 320 settles in about 450ms.
     */
    val SheetSpring = spring<Float>(dampingRatio = 0.74f, stiffness = 320f)

    /**
     * How pages that sit side by side settle after a tab or a swipe (H20): no overshoot, so the edge of the next page
     * never shows, landing in about 400ms and carrying the speed the finger let go at. It is measured in pages, so it
     * runs on to half a pixel: with the default threshold of a hundredth it stopped 10px short and snapped the rest (J7).
     */
    val PageSpring = spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.0005f)
}

/**
 * False when the person has turned system animations off ("Remove animations"): a looping effect
 * such as the thinking shimmer then holds still instead of sweeping (rule 73).
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}

/** Blur is a render effect: Android 12 and up. Below that the content is simply drawn sharp. */
val BlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** [blur] that costs nothing at zero and does nothing below Android 12; the content may bleed past its bounds. */
fun Modifier.softBlur(radius: Dp): Modifier =
    if (BlurSupported && radius > 0.dp) this.blur(radius, BlurredEdgeTreatment.Unbounded) else this

/**
 * Swaps between screens of one page, such as the Inbox and Tours tabs: the leaving content fades
 * out blurring and slides 8dp away, the arriving content fades in sharpening from 8dp the other
 * side (`--p8-*`, 250ms smooth-out). [order] ranks the screens left to right, so a screen further
 * right comes in from the right and one further left from the left: the pages move the way the
 * person went (rule 105). A change [order] cannot know the way of, Back and a back swipe, comes
 * through [motion], which also holds a page under a back swipe (see [PageBack]). A centred title passes
 * [contentAlignment] `Center`, so the one leaving does not ride the edge of a container that shrinks around it.
 * [reach] above 0 makes the pages travel that share of their width instead of 8dp, and [slow] takes 350ms in and 250ms
 * out instead of 250 and 150: a sheet's pages move far and slow enough to see the way they went (H1).
 */
@Composable
fun <S> BlurSwitch(
    target: S,
    modifier: Modifier = Modifier,
    order: (S) -> Int,
    motion: PageMotion? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    reach: Float = 0f,
    slow: Boolean = false,
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val step = with(LocalDensity.current) { PullsMotion.SlideDistance.roundToPx() }
    val travel = { width: Int -> if (reach > 0f) (width * reach).toInt() else step }
    val inMs = if (slow) PullsMotion.MEDIUM else PullsMotion.FAST
    val outMs = if (slow) PullsMotion.FAST else PullsMotion.QUICK
    val moving = rememberMotionEnabled()
    val change = remember { WayOfChange(target) }
    change.update(target, order, motion)
    val pages = updateTransition(target, label = "blur-switch")
    if (motion != null) {
        SideEffect { motion.onScreen = target }
        // The page a back swipe held keeps where the finger left it until it has gone; then the hold lets go.
        val settled = pages.currentState
        LaunchedEffect(settled) { if (settled == pages.targetState) motion.letGo(settled) }
    }
    pages.AnimatedContent(
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = {
            if (!moving) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            val way = change.way
            ((fadeIn(tween(inMs, easing = PullsMotion.SmoothOut)) + slideInHorizontally(tween(inMs, easing = PullsMotion.SmoothOut)) { way * travel(it) }) togetherWith
                (fadeOut(tween(outMs, easing = PullsMotion.SmoothOut)) + slideOutHorizontally(tween(outMs, easing = PullsMotion.SmoothOut)) { -way * travel(it) }))
                // A screen of another height resizes the container on the same curve, so a sheet grows or shrinks with its page.
                .using(SizeTransform(clip = true) { _, _ -> tween(inMs, easing = PullsMotion.SmoothOut) })
        },
    ) { state ->
        val blur by transition.animateDp(transitionSpec = { tween(inMs, easing = PullsMotion.SmoothOut) }, label = "blur") {
            if (it == EnterExitState.Visible) 0.dp else PullsMotion.BlurMedium
        }
        Box(Modifier.then(if (motion != null) Modifier.heldBy(motion, state) else Modifier).softBlur(blur)) { content(state) }
    }
}

/** The way of the change in flight, fixed once when the target changes, so the leaving and arriving pages agree. */
private class WayOfChange<S>(private var from: S) {
    var way = FORWARD
        private set

    fun update(to: S, order: (S) -> Int, motion: PageMotion?) {
        if (to == from) return
        way = motion?.take() ?: pageWay(order(from), order(to))
        from = to
    }
}

/**
 * What moved a [BlurSwitch] last, when its order cannot tell (rule 105). Back always goes backward, and a back swipe
 * moves the page with the finger while it is held and then carries on the way the finger went, so a swipe from the
 * right edge sends the pages left. One [PageMotion] drives one [BlurSwitch].
 */
@Stable
class PageMotion {
    private var next: Int? = null

    /** The next change goes [way] ([FORWARD] or [BACKWARD]) whatever the order says. Call it just before the change. */
    fun next(way: Int) {
        next = way
    }

    internal fun take(): Int? = next.also { next = null }

    /** The page the switch shows, which is the one a back swipe takes hold of. */
    internal var onScreen: Any? = null

    /** The page under a back swipe: it keeps the swipe's offset while it leaves, and nothing else does. */
    internal var held: Any? by mutableStateOf(null)

    /** A given-up swipe putting its page back, which the next swipe stops so the two do not fight over the hold. */
    internal var putBack: Job? = null

    /** How far the back swipe has gone, 0 to 1, and which way it moves the page, with the finger. */
    internal var hold by mutableFloatStateOf(0f)
    internal var holdWay by mutableIntStateOf(BACKWARD)

    /** The page that held the swipe has gone (or the switch settled elsewhere): drop the hold. */
    internal fun letGo(settled: Any?) {
        if (held != null && held != settled) {
            held = null
            hold = 0f
        }
    }
}

/** A page under a back swipe follows the finger up to 96dp and fades to 40% on the way, like the rows of a tab swipe (rule 88). */
private fun Modifier.heldBy(motion: PageMotion, state: Any?): Modifier = graphicsLayer {
    // Read in the draw layer, all of them every time, so the layer redraws when any changes.
    val p = motion.hold
    if (state != motion.held) return@graphicsLayer
    translationX = -motion.holdWay * p * PullsMotion.FingerRange.toPx()
    alpha = 1f - 0.6f * p
}

/**
 * Back for pages drawn by a [BlurSwitch] with [motion] (rule 105). The Back button goes backward. A back swipe moves
 * the page with the finger while it is held, and when it lets go the pages carry on that way: right after a swipe from
 * the left edge, left after one from the right. A swipe given up puts the page back.
 */
@Composable
fun PageBack(motion: PageMotion, enabled: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onBack)
    val moving = rememberMotionEnabled()
    PredictiveBackHandler(enabled) { events ->
        motion.putBack?.cancel()
        motion.held = motion.onScreen
        var way = BACKWARD
        try {
            events.collect { event ->
                way = backWay(fromRightEdge = event.swipeEdge == BackEventCompat.EDGE_RIGHT)
                motion.holdWay = way
                if (moving) motion.hold = event.progress
            }
        } catch (e: CancellationException) {
            val held = motion.held
            motion.putBack = scope.launch {
                animate(motion.hold, 0f, animationSpec = tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) { v, _ -> motion.hold = v }
                if (motion.held == held) motion.held = null
            }
            throw e
        }
        motion.next(way)
        latest()
    }
}

/**
 * A label replaced by its neighbour, such as the file name between ‹ and › or the stop named on the Tour's strip: the
 * old one leaves through a 2px blur the way the change goes and the new one comes from the other side, 150ms
 * ease-in-out, 8dp of travel, the arrival held back 50ms, as the thinking line swaps (rule 73). [order] ranks the
 * labels, so the next file comes from the right and the previous one from the left (rule 105); [vertical] is for a list
 * read top to bottom, where the next stop rises from below and scrolling back brings the last one down from above.
 * Both labels sit in the middle of the space while it resizes ([contentAlignment]), so the leaving one moves only
 * the way it goes, not with an edge of the space as it shrinks around the next.
 */
@Composable
fun <S> LabelSwitch(
    target: S,
    modifier: Modifier = Modifier,
    order: (S) -> Int,
    vertical: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (S) -> Unit,
) {
    val travel = with(LocalDensity.current) { PullsMotion.SlideDistance.roundToPx() }
    val moving = rememberMotionEnabled()
    val change = remember { WayOfChange(target) }
    change.update(target, order, null)
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = {
            if (!moving) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            val way = change.way
            val arrive = tween<IntOffset>(PullsMotion.QUICK, delayMillis = LABEL_GAP_MS, easing = PullsMotion.InOut)
            val go = tween<IntOffset>(PullsMotion.QUICK, easing = PullsMotion.InOut)
            val enter = fadeIn(tween(PullsMotion.QUICK, delayMillis = LABEL_GAP_MS, easing = PullsMotion.InOut)) +
                if (vertical) slideInVertically(arrive) { way * travel } else slideInHorizontally(arrive) { way * travel }
            val exit = fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.InOut)) +
                if (vertical) slideOutVertically(go) { -way * travel } else slideOutHorizontally(go) { -way * travel }
            (enter togetherWith exit) using SizeTransform(clip = false) { _, _ -> tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut) }
        },
        label = "label-switch",
    ) { state ->
        val blur by transition.animateDp(transitionSpec = { tween(PullsMotion.QUICK, easing = PullsMotion.InOut) }, label = "label-blur") {
            if (it == EnterExitState.Visible) 0.dp else PullsMotion.BlurSmall
        }
        Box(Modifier.softBlur(blur)) { content(state) }
    }
}

/** The pause before a label's replacement arrives, so the two do not cross (Thinking states on transitions.dev). */
private const val LABEL_GAP_MS = 50

/**
 * A page change inside a scrolling list (Summary, Commits and Conversation under their tabs). The rows of a lazy list
 * cannot sit inside an [AnimatedContent], so the page is changed in two beats instead: the rows on screen fade out
 * blurring and slide 8dp away (150ms), the next page's rows take their place and fade in sharpening from 8dp the
 * other side (250ms), both on the smooth-out curve like [BlurSwitch]. [shown] is the page the list holds right now, and
 * every row of it wears [pageSlide] with this state.
 *
 * A finger can hold the rows ([follow]). When it lets go ([release]) on a change, the rows never come back toward it:
 * they carry on the way it went, from where it left them and at the speed it let go at, while the next page comes in
 * from the other side (rule 105).
 */
@Stable
class PageSlide<T> internal constructor(initial: T) {
    var shown by mutableStateOf(initial)
        internal set

    /** -1 to 1: 0 at rest, and the way the rows have travelled while they leave or arrive. */
    internal val travel = Animatable(0f)

    /** How far a finger has dragged the rows, in pixels: they follow it, and fade a little as they go (rule 88). */
    var finger by mutableFloatStateOf(0f)
        internal set

    /** How far the leaving rows go past the finger, in px: a page's 8dp, or more when a swipe threw them. */
    internal var span by mutableFloatStateOf(0f)

    /** The way a swipe that let go threw the rows, until the change it made has taken them; 0 while they are the finger's. */
    internal var thrown by mutableIntStateOf(0)

    /** A page change is moving the rows. */
    internal var changing = false

    /** A page's 8dp and the finger's 96dp, in px, for this screen. */
    internal var step = 0f
    internal var reach = 0f

    /** The pages sit side by side: the rows travel the whole width and do not fade or blur (H36). */
    internal var full = false

    /** The window's width in px, which every row travels alike whatever its own width, so the page leaves as one. */
    internal var width = 0f

    /**
     * The rows follow a drag of [delta] pixels, up to [limit] either way. With no page before ([back] false) or after
     * ([forward] false) they do not leave their place that way, but always come back under a finger that returns.
     */
    fun follow(delta: Float, limit: Float, back: Boolean = true, forward: Boolean = true) {
        finger = (finger + delta).coerceIn(if (forward) -limit else 0f, if (back) limit else 0f)
    }

    /**
     * The finger's share of the way toward the next page, -1 to 1, while it holds the rows or they spring back under it;
     * 0 once a swipe has thrown them on. Tab ink leans with it.
     */
    fun pull(range: Float): Float = if (thrown != 0 || range <= 0f) 0f else (-finger / range).coerceIn(-1f, 1f)

    /**
     * The finger is up. [way] is where the swipe went (rule 105): 0 when it changed nothing, and the rows return under
     * it; [FORWARD] or [BACKWARD] when it changed this slide's page, and the page change takes the rows on from where
     * the finger left them, as fast as it let go ([velocity], px/s).
     */
    suspend fun release(way: Int, velocity: Float = 0f) {
        if (way == 0) {
            animate(finger, 0f, animationSpec = tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) { value, _ -> finger = value }
            return
        }
        thrown = way
        span = throwSpan(velocity)
        // The change starts on the next frame. If none came (the page could not change after all), let the rows go back.
        delay(PullsMotion.QUICK.toLong())
        if (thrown == way && !changing) {
            thrown = 0
            span = 0f
            animate(finger, 0f, animationSpec = tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) { value, _ -> finger = value }
        }
    }

    /**
     * The swipe took the whole view away (past the last file to the Tour, before the first to the Overview), which leaves
     * as a page: the rows carry on with the finger, [way] and as fast as it let go, fading, and are put back once they
     * are out of sight.
     */
    suspend fun leave(way: Int, velocity: Float = 0f) {
        thrown = way
        span = throwSpan(velocity)
        try {
            travel.animateTo(-way.toFloat(), tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut))
        } finally {
            if (!changing) {
                travel.snapTo(0f)
                finger = 0f
                span = 0f
                thrown = 0
            }
        }
    }

    /** How far rows let go at [velocity] travel in a 150ms leave on the smooth-out curve, which starts at 4.5 times its mean speed, so they leave as fast as the finger moved them. */
    private fun throwSpan(velocity: Float): Float =
        (abs(velocity) * PullsMotion.QUICK / 1000f / SMOOTH_OUT_START).coerceIn(step, maxOf(step, reach))
}

/** The smooth-out curve's speed at its start, as a multiple of its mean: `cubic-bezier(0.22, 1, ...)` leaves at 1 / 0.22. */
private const val SMOOTH_OUT_START = 4.55f

@Composable
fun <T> rememberPageSlide(target: T, order: (T) -> Int, full: Boolean = false): PageSlide<T> {
    val slide = remember { PageSlide(target) }
    slide.full = full
    slide.width = LocalWindowInfo.current.containerSize.width.toFloat()
    val motion = rememberMotionEnabled()
    val density = LocalDensity.current
    slide.step = with(density) { PullsMotion.SlideDistance.toPx() }
    slide.reach = with(density) { PullsMotion.FingerRange.toPx() }
    LaunchedEffect(target) {
        // Back to the page still on screen before it finished leaving: the rows come back from wherever they got to.
        if (target == slide.shown) {
            slide.thrown = 0
            slide.span = 0f
            coroutineScope {
                launch { if (slide.travel.value != 0f) slide.travel.animateTo(0f, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) }
                launch { animate(slide.finger, 0f, animationSpec = tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { value, _ -> slide.finger = value } }
            }
            return@LaunchedEffect
        }
        if (!motion) {
            slide.travel.snapTo(0f)
            slide.finger = 0f
            slide.thrown = 0
            slide.shown = target
            return@LaunchedEffect
        }
        slide.changing = true
        try {
            // The way the pages sit, which is the way a swipe that made this change went (rule 105).
            val way = pageWay(order(slide.shown), order(target)).toFloat()
            if (slide.span <= 0f) slide.span = slide.step
            slide.travel.animateTo(-way, tween(PullsMotion.QUICK, easing = if (slide.full) PullsMotion.Accelerate else PullsMotion.SmoothOut))
            slide.shown = target
            slide.finger = 0f
            slide.span = 0f
            slide.thrown = 0
            slide.travel.snapTo(way)
            slide.travel.animateTo(0f, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))
        } finally {
            slide.changing = false
        }
    }
    return slide
}

/**
 * The rows of a [PageSlide]: following the finger, then fading, blurring and sliding by how far the change has gone.
 * With pages side by side ([PageSlide.full]) they only slide, the whole width and a gap, so the page goes out of sight
 * and the next comes in from the edge. Reads the animation in the layer, so no frame recomposes.
 */
fun Modifier.pageSlide(slide: PageSlide<*>): Modifier = graphicsLayer {
    val t = slide.travel.value
    if (slide.full) {
        translationX = t * (slide.width + PullsMotion.PageGap.toPx()) + slide.finger
        alpha = 1f
        renderEffect = null
        return@graphicsLayer
    }
    val pull = (abs(slide.finger) / PullsMotion.FingerRange.toPx()).coerceIn(0f, 1f)
    val span = if (slide.span > 0f) slide.span else PullsMotion.SlideDistance.toPx()
    translationX = t * span + slide.finger
    alpha = (1f - abs(t)) * (1f - 0.6f * pull)
    val blur = abs(t) * PullsMotion.BlurMedium.toPx()
    renderEffect = if (BlurSupported && blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
}

/**
 * A menu or dropdown appearing and leaving (`dropdown` on transitions.dev): it opens from 97% size
 * through a 2px blur in 250ms and closes to 99% in 150ms, both growing out of [origin], the point of
 * the trigger. Compose [content] only inside; the scrim that dismisses it lives outside.
 */
@Composable
fun MenuReveal(visible: Boolean, modifier: Modifier = Modifier, origin: TransformOrigin = TransformOrigin(0.5f, 0f), content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    AnimatedVisibility(
        visible, modifier,
        enter = if (!motion) EnterTransition.None else fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + scaleIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), PullsMotion.DROPDOWN_OPEN_SCALE, origin),
        exit = if (!motion) ExitTransition.None else fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + scaleOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut), PullsMotion.DROPDOWN_CLOSE_SCALE, origin),
    ) {
        val blur by transition.animateDp(transitionSpec = { tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut) }, label = "menu-blur") {
            if (it == EnterExitState.Visible) 0.dp else PullsMotion.BlurSmall
        }
        Box(Modifier.softBlur(blur)) { content() }
    }
}

/**
 * An icon replaced by another, such as Copy by a tick (`icon swap` on transitions.dev, rule 73):
 * the old one shrinks to a quarter through a 2px blur while the new one grows from a quarter, both at
 * once in 250ms ease-in-out. Nothing moves while [target] stays the same.
 */
@Composable
fun <S> IconSwap(target: S, modifier: Modifier = Modifier, content: @Composable (S) -> Unit) {
    val motion = rememberMotionEnabled()
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (!motion) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            (fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.InOut)) + scaleIn(tween(PullsMotion.FAST, easing = PullsMotion.InOut), PullsMotion.ICON_SCALE)) togetherWith
                (fadeOut(tween(PullsMotion.FAST, easing = PullsMotion.InOut)) + scaleOut(tween(PullsMotion.FAST, easing = PullsMotion.InOut), PullsMotion.ICON_SCALE))
        },
        label = "icon-swap",
    ) { state ->
        val blur by transition.animateDp(transitionSpec = { tween(PullsMotion.FAST, easing = PullsMotion.InOut) }, label = "icon-blur") {
            if (it == EnterExitState.Visible) 0.dp else PullsMotion.BlurSmall
        }
        Box(Modifier.softBlur(blur)) { content(state) }
    }
}

/**
 * A dialog window that opens in 250ms from 96% size and leaves in 150ms (`modal` on transitions.dev,
 * rule 73). The window is kept until the exit has played, so [visible] going false does not cut it.
 */
@Composable
fun ModalReveal(visible: Boolean, onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false), content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    // Not yet idle covers a close during the entrance, when currentState is still false but the window is up.
    if (!visible && !state.currentState && state.isIdle) return
    Dialog(onDismissRequest, properties) { ModalSurface(state, motion, content) }
}

/** What [ModalReveal] draws inside its window: the 250ms grow-in and 150ms leave. */
@Composable
internal fun ModalSurface(state: MutableTransitionState<Boolean>, motion: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        state,
        enter = if (!motion) EnterTransition.None else fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + scaleIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), PullsMotion.MODAL_SCALE),
        exit = if (!motion) ExitTransition.None else fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + scaleOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut), PullsMotion.MODAL_SCALE),
    ) { content() }
}

/**
 * The top padding of a screen header. Row W puts the header 48dp from the top of the screen; a
 * phone whose status bar is taller than 40dp keeps its own inset plus 8dp instead.
 */
@Composable
fun headerTopPadding(): Dp = max(WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value + 8f, 48f).dp

/**
 * Content that scrolls under a pinned header or bar dissolves instead of being cut (rule 74). The
 * [top] and [bottom] bands go to the page [color] over their length, and the content in them is
 * blurred a little more the nearer it is to the edge, in [FADE_STEPS] layers of growing radius.
 * Each blurred layer is made from content that reaches [pad] beyond the band on both sides, then
 * clipped to the band: a blur that only saw the band would stretch its own edge into streaks, and
 * one that was not clipped would smear the header above it.
 *
 * [topStrength] and [bottomStrength] (0 to 1) are read in the draw phase, so a list at rest at the
 * top stays sharp. [topInset] (px, also read while drawing) starts the top band lower, under something pinned over the
 * top of the content, such as a list's sticky heading. Below Android 12 there is no blur and the fade alone is drawn.
 */
@Composable
fun Modifier.edgeFade(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    color: Color = LocalPullsColors.current.base,
    blur: Dp = PullsMotion.EdgeBlur,
    topStrength: () -> Float = { 1f },
    bottomStrength: () -> Float = { 1f },
    topInset: () -> Float = { 0f },
): Modifier {
    if (top <= 0.dp && bottom <= 0.dp) return this
    val source = rememberGraphicsLayer()
    val topLayers = List(FADE_STEPS) { rememberGraphicsLayer() }
    val bottomLayers = List(FADE_STEPS) { rememberGraphicsLayer() }
    return this.drawWithContent {
        source.record { this@drawWithContent.drawContent() }
        drawLayer(source)
        if (top > 0.dp) fadeBand(source, topLayers, color, topInset(), top.toPx(), true, blur.toPx(), topStrength())
        if (bottom > 0.dp) fadeBand(source, bottomLayers, color, size.height - bottom.toPx(), bottom.toPx(), false, blur.toPx(), bottomStrength())
    }
}

/** How many blur layers make a band: radius a third, two thirds and all of the blur, reaching all, two thirds and a third of the way in. */
private const val FADE_STEPS = 3

/** How opaque the page colour is at each tenth of the way out from the inside of a band to its edge: an ease, so the rows go quietly. */
private val FADE_CURVE = floatArrayOf(0f, 0.03f, 0.12f, 0.26f, 0.44f, 0.62f, 0.78f, 0.9f, 0.97f, 1f)

private fun DrawScope.fadeBand(source: GraphicsLayer, layers: List<GraphicsLayer>, color: Color, y: Float, height: Float, atTop: Boolean, blur: Float, strength: Float) {
    if (strength <= 0f || height < 1f || size.width < 1f) return
    val amount = strength.coerceIn(0f, 1f)
    if (BlurSupported && blur > 0f) {
        layers.forEachIndexed { i, layer ->
            val radius = blur * (i + 1) / layers.size
            val reach = height * (layers.size - i) / layers.size
            val pad = ceil(radius * 3f)
            val edge = if (atTop) pad else pad + height
            layer.renderEffect = BlurEffect(radius, radius, TileMode.Decal)
            layer.compositingStrategy = CompositingStrategy.Offscreen
            layer.alpha = amount
            layer.record(size = IntSize(size.width.toInt(), (height + 2f * pad).toInt())) {
                translate(top = pad - y) { drawLayer(source) }
                // raw-value-ok: an alpha mask, never drawn as a colour
                val keep = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = edge, endY = if (atTop) edge + reach else edge - reach)
                drawRect(keep, blendMode = BlendMode.DstIn)
            }
            clipRect(0f, y, size.width, y + height) { translate(top = y - pad) { drawLayer(layer) } }
        }
    }
    val stops = Array(FADE_CURVE.size) { n ->
        val outward = n / (FADE_CURVE.size - 1f)
        // Index 0 is the inside of the band and the last is the page edge; a top band runs the other way round.
        val at = if (atTop) 1f - outward else outward
        at to color.copy(alpha = FADE_CURVE[n])
    }.sortedBy { it.first }.toTypedArray()
    drawRect(Brush.verticalGradient(*stops, startY = y, endY = y + height), topLeft = Offset(0f, y), size = Size(size.width, height), alpha = amount)
}

/**
 * [sideFade] for a row that scrolls sideways with [scroll]: each edge fades over [band] only while there is more past it,
 * so a row at rest at its start is sharp on the left (J14, J23). [color] is what the row sits on.
 */
@Composable
fun Modifier.sideScrollFade(scroll: ScrollState, band: Dp = SIDE_BAND, color: Color = LocalPullsColors.current.base): Modifier {
    val px = with(LocalDensity.current) { band.toPx() }
    return sideFade(
        start = band, end = band, color = color,
        startStrength = { (scroll.value / px).coerceIn(0f, 1f) },
        endStrength = { ((scroll.maxValue - scroll.value) / px).coerceIn(0f, 1f) },
    )
}

/** How far a sideways row fades at an edge it can still scroll toward. */
val SIDE_BAND = 24.dp

/**
 * [edgeFade] for content that scrolls sideways, such as the branch row (J14): what runs past the [start] and [end] edges
 * dissolves into the page [color] through the same blur, instead of being cut. [startStrength] and [endStrength] (0 to 1,
 * read while drawing) let an edge with nothing past it stay sharp.
 */
@Composable
fun Modifier.sideFade(
    start: Dp = 0.dp,
    end: Dp = 0.dp,
    color: Color = LocalPullsColors.current.base,
    blur: Dp = PullsMotion.BlurMedium,
    startStrength: () -> Float = { 1f },
    endStrength: () -> Float = { 1f },
): Modifier {
    if (start <= 0.dp && end <= 0.dp) return this
    val source = rememberGraphicsLayer()
    val startLayers = List(FADE_STEPS) { rememberGraphicsLayer() }
    val endLayers = List(FADE_STEPS) { rememberGraphicsLayer() }
    return this.drawWithContent {
        source.record { this@drawWithContent.drawContent() }
        drawLayer(source)
        if (start > 0.dp) sideBand(source, startLayers, color, 0f, start.toPx(), true, blur.toPx(), startStrength())
        if (end > 0.dp) sideBand(source, endLayers, color, size.width - end.toPx(), end.toPx(), false, blur.toPx(), endStrength())
    }
}

/** [fadeBand] turned on its side: a band [width] px wide from [x], dissolving toward the start edge when [atStart]. */
private fun DrawScope.sideBand(source: GraphicsLayer, layers: List<GraphicsLayer>, color: Color, x: Float, width: Float, atStart: Boolean, blur: Float, strength: Float) {
    if (strength <= 0f || width < 1f || size.height < 1f) return
    val amount = strength.coerceIn(0f, 1f)
    if (BlurSupported && blur > 0f) {
        layers.forEachIndexed { i, layer ->
            val radius = blur * (i + 1) / layers.size
            val reach = width * (layers.size - i) / layers.size
            val pad = ceil(radius * 3f)
            val edge = if (atStart) pad else pad + width
            layer.renderEffect = BlurEffect(radius, radius, TileMode.Decal)
            layer.compositingStrategy = CompositingStrategy.Offscreen
            layer.alpha = amount
            layer.record(size = IntSize((width + 2f * pad).toInt(), size.height.toInt())) {
                translate(left = pad - x) { drawLayer(source) }
                // raw-value-ok: an alpha mask, never drawn as a colour
                val keep = Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = edge, endX = if (atStart) edge + reach else edge - reach)
                drawRect(keep, blendMode = BlendMode.DstIn)
            }
            clipRect(x, 0f, x + width, size.height) { translate(left = x - pad) { drawLayer(layer) } }
        }
    }
    val stops = Array(FADE_CURVE.size) { n ->
        val outward = n / (FADE_CURVE.size - 1f)
        val at = if (atStart) 1f - outward else outward
        at to color.copy(alpha = FADE_CURVE[n])
    }.sortedBy { it.first }.toTypedArray()
    drawRect(Brush.horizontalGradient(*stops, startX = x, endX = x + width), topLeft = Offset(x, 0f), size = Size(width, size.height), alpha = amount)
}

/** How far a list has scrolled under its top edge: 0 at rest, 1 once it is [band] px past. */
fun LazyListState.topEdgeStrength(band: Float): Float =
    if (firstVisibleItemIndex > 0) 1f else (firstVisibleItemScrollOffset / band).coerceIn(0f, 1f)
