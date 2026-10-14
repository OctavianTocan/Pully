package com.octaviantocan.pulls.ui.design

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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

    /** `--ease-smooth-out`: modals, dropdowns, panels, page slides and resizes. */
    val SmoothOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** `ease-in-out`: icon and text swaps. */
    val InOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** A dropdown opens from 0.97 and closes to 0.99. */
    const val DROPDOWN_OPEN_SCALE = 0.97f
    const val DROPDOWN_CLOSE_SCALE = 0.99f

    /** A modal opens and closes through 0.96. */
    const val MODAL_SCALE = 0.96f

    val BlurSmall = 2.dp
    val BlurMedium = 3.dp

    /** How far a page slides in (`--distance-base`). */
    val SlideDistance = 8.dp
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
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            val way = if (order(targetState) >= order(initialState)) 1 else -1
            (fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + slideInHorizontally(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { way * travel }) togetherWith
                (fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + slideOutHorizontally(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) { -way * travel })
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
 * A menu or dropdown appearing and leaving (`dropdown` on transitions.dev): it opens from 97% size
 * through a 2px blur in 250ms and closes to 99% in 150ms, both growing out of [origin], the point of
 * the trigger. Compose [content] only inside; the scrim that dismisses it lives outside.
 */
@Composable
fun MenuReveal(visible: Boolean, modifier: Modifier = Modifier, origin: TransformOrigin = TransformOrigin(0.5f, 0f), content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible, modifier,
        enter = fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + scaleIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), PullsMotion.DROPDOWN_OPEN_SCALE, origin),
        exit = fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + scaleOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut), PullsMotion.DROPDOWN_CLOSE_SCALE, origin),
    ) {
        val blur by transition.animateDp(transitionSpec = { tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut) }, label = "menu-blur") {
            if (it == EnterExitState.Visible) 0.dp else PullsMotion.BlurSmall
        }
        Box(Modifier.softBlur(blur)) { content() }
    }
}

/**
 * The top padding of a screen header. Row W puts the header 48dp from the top of the screen; a
 * phone whose status bar is taller than 40dp keeps its own inset plus 8dp instead.
 */
@Composable
fun headerTopPadding(): Dp = max(WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value + 8f, 48f).dp

/**
 * Content that scrolls under a floating header or bar dissolves instead of being cut: the [top]
 * and [bottom] bands are drawn again, blurred, fading in towards the edge. [topStrength] and
 * [bottomStrength] (0 to 1) read scroll state in the draw phase, so a list at rest at the top
 * stays sharp. Below Android 12 there is no blur and the content is drawn as it is.
 */
@Composable
fun Modifier.edgeBlur(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    radius: Dp = 10.dp,
    topStrength: () -> Float = { 1f },
    bottomStrength: () -> Float = { 1f },
): Modifier {
    if (!BlurSupported || (top <= 0.dp && bottom <= 0.dp)) return this
    val source = rememberGraphicsLayer()
    val topBand = rememberGraphicsLayer()
    val bottomBand = rememberGraphicsLayer()
    return this.drawWithContent {
        source.record { this@drawWithContent.drawContent() }
        drawLayer(source)
        val r = radius.toPx()
        if (top > 0.dp) blurBand(source, topBand, 0f, top.toPx(), true, r, topStrength())
        if (bottom > 0.dp) blurBand(source, bottomBand, size.height - bottom.toPx(), bottom.toPx(), false, r, bottomStrength())
    }
}

private fun DrawScope.blurBand(source: GraphicsLayer, band: GraphicsLayer, y: Float, height: Float, strongAtStart: Boolean, radius: Float, strength: Float) {
    if (strength <= 0f || height < 1f) return
    band.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
    band.compositingStrategy = CompositingStrategy.Offscreen
    band.alpha = strength.coerceIn(0f, 1f)
    band.record(size = IntSize(size.width.toInt(), height.toInt())) {
        translate(top = -y) { drawLayer(source) }
        // raw-value-ok: an alpha mask, never drawn as a colour
        val mask = if (strongAtStart) arrayOf(0f to Color.Black, 1f to Color.Transparent) else arrayOf(0f to Color.Transparent, 1f to Color.Black)
        drawRect(Brush.verticalGradient(colorStops = mask), blendMode = BlendMode.DstIn)
    }
    translate(top = y) { drawLayer(band) }
}

/** How far a list has scrolled under its top edge: 0 at rest, 1 once it is [band] px past. */
fun LazyListState.topEdgeStrength(band: Float): Float =
    if (firstVisibleItemIndex > 0) 1f else (firstVisibleItemScrollOffset / band).coerceIn(0f, 1f)
