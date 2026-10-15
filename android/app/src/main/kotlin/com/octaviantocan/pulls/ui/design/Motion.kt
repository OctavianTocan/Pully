package com.octaviantocan.pulls.ui.design

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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

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
 * side (`--p8-*`, 250ms smooth-out). [order] ranks the screens left to right, so going back
 * slides the other way; without it every change moves forward.
 */
@Composable
fun <S> BlurSwitch(target: S, modifier: Modifier = Modifier, order: (S) -> Int = { 0 }, content: @Composable AnimatedContentScope.(S) -> Unit) {
    val travel = with(LocalDensity.current) { PullsMotion.SlideDistance.roundToPx() }
    val motion = rememberMotionEnabled()
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            val way = if (order(targetState) >= order(initialState)) 1 else -1
            if (!motion) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            ((fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + slideInHorizontally(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { way * travel }) togetherWith
                (fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + slideOutHorizontally(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) { -way * travel }))
                // A screen of another height resizes the container on the same curve, so a sheet grows or shrinks with its page.
                .using(SizeTransform(clip = true) { _, _ -> tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut) })
        },
        label = "blur-switch",
    ) { state ->
        val blur by transition.animateDp(transitionSpec = { tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut) }, label = "blur") {
            if (it == EnterExitState.Visible) 0.dp else PullsMotion.BlurMedium
        }
        Box(Modifier.softBlur(blur)) { content(state) }
    }
}

/**
 * A page change inside a scrolling list (Summary, Commits and Conversation under their tabs). The rows of a lazy list
 * cannot sit inside an [AnimatedContent], so the page is changed in two beats instead: the rows on screen fade out
 * blurring and slide 8dp away (150ms), the next page's rows take their place and fade in sharpening from 8dp the
 * other side (250ms), both on the smooth-out curve like [BlurSwitch]. [shown] is the page the list holds right now, and
 * every row of it wears [pageSlide] with this state.
 */
@Stable
class PageSlide<T> internal constructor(initial: T) {
    var shown by mutableStateOf(initial)
        internal set

    /** -1 to 1: 0 at rest, and the way the rows have travelled while they leave or arrive. */
    internal val travel = Animatable(0f)

    /** How far a finger has dragged the rows, in pixels: they follow it, and fade a little as they go (rule 88). */
    var finger by mutableFloatStateOf(0f)
        private set

    /** The rows follow a drag of [delta] pixels, up to [limit] either way. */
    fun follow(delta: Float, limit: Float) {
        finger = (finger + delta).coerceIn(-limit, limit)
    }

    /** The finger is up: the rows return to where they belong while the page changes under them, or not. */
    suspend fun release() {
        animate(finger, 0f, animationSpec = tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) { value, _ -> finger = value }
    }
}

@Composable
fun <T> rememberPageSlide(target: T, order: (T) -> Int = { 0 }): PageSlide<T> {
    val slide = remember { PageSlide(target) }
    val motion = rememberMotionEnabled()
    LaunchedEffect(target) {
        // Back to the page still on screen before it finished leaving: the rows come back from wherever they got to.
        if (target == slide.shown) {
            if (slide.travel.value != 0f) slide.travel.animateTo(0f, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))
            return@LaunchedEffect
        }
        if (!motion) {
            slide.travel.snapTo(0f)
            slide.shown = target
            return@LaunchedEffect
        }
        val way = if (order(target) >= order(slide.shown)) 1f else -1f
        slide.travel.animateTo(-way, tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut))
        slide.shown = target
        slide.travel.snapTo(way)
        slide.travel.animateTo(0f, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))
    }
    return slide
}

/** The rows of a [PageSlide]: following the finger, then fading, blurring and sliding by how far the change has gone. Reads the animation in the layer, so no frame recomposes. */
fun Modifier.pageSlide(slide: PageSlide<*>): Modifier = graphicsLayer {
    val t = slide.travel.value
    val pull = (abs(slide.finger) / PullsMotion.FingerRange.toPx()).coerceIn(0f, 1f)
    translationX = t * PullsMotion.SlideDistance.toPx() + slide.finger
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
 * top stays sharp. Below Android 12 there is no blur and the fade alone is drawn.
 */
@Composable
fun Modifier.edgeFade(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    color: Color = LocalPullsColors.current.base,
    blur: Dp = PullsMotion.EdgeBlur,
    topStrength: () -> Float = { 1f },
    bottomStrength: () -> Float = { 1f },
): Modifier {
    if (top <= 0.dp && bottom <= 0.dp) return this
    val source = rememberGraphicsLayer()
    val topLayers = List(FADE_STEPS) { rememberGraphicsLayer() }
    val bottomLayers = List(FADE_STEPS) { rememberGraphicsLayer() }
    return this.drawWithContent {
        source.record { this@drawWithContent.drawContent() }
        drawLayer(source)
        if (top > 0.dp) fadeBand(source, topLayers, color, 0f, top.toPx(), true, blur.toPx(), topStrength())
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

/** How far a list has scrolled under its top edge: 0 at rest, 1 once it is [band] px past. */
fun LazyListState.topEdgeStrength(band: Float): Float =
    if (firstVisibleItemIndex > 0) 1f else (firstVisibleItemScrollOffset / band).coerceIn(0f, 1f)
