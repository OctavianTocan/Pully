package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDp
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.core.BACKWARD
import com.octaviantocan.pulls.core.FORWARD
import com.octaviantocan.pulls.core.pageWay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
