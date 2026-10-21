package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Native support for the shared blur recipe; unsupported Android versions remain sharp. */
expect val BlurSupported: Boolean

/** [blur] that costs nothing at zero and does nothing below Android 12; the content may bleed past its bounds. */
fun Modifier.softBlur(radius: Dp): Modifier =
    if (BlurSupported && radius > 0.dp) this.blur(radius, BlurredEdgeTreatment.Unbounded) else this

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
