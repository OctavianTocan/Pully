package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.sound.directionOf
import kotlinx.coroutines.delay
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.floor

/*
 * The Android prototype's building blocks: white floating surfaces on warm paper, pill
 * capsules, soft chips, a paper bottom sheet, a dark toast. Every screen composes these; the
 * numbers (44dp circles, 36dp chips, 52dp rows, 24dp sheet corners) come straight from it.
 */

/**
 * The canvas shadows from Row W. Each is `0 dy blur rgba(ink, alpha)`: a soft ink drop two pixels
 * down that reads as light, not as a border. Compose's `Modifier.shadow` cannot draw an offset,
 * so [floatShadow] draws these itself (rule 45).
 */
enum class PullsShadow(val dy: Dp, val blur: Dp, val alpha: Float) {
    /** `.fl`, `.cap`: header circles and capsules. */
    Button(2.dp, PullsElevation.button, 0.08f),

    /** `.note`: cards, the Tour card, quick actions. */
    Note(2.dp, PullsElevation.note, 0.10f),

    /** `.tbar`: the bottom bar and the comment bar. */
    Bar(2.dp, PullsElevation.bar, 0.12f),

    /** `.search`: the search pill and the Ask bar. */
    Search(2.dp, PullsElevation.search, 0.12f),

    /** Popovers that open over content: the Aa menu, the file list, the stop picker. */
    Menu(4.dp, PullsElevation.menu, 0.14f),

    /** `.tog>i`: the switch thumb. */
    Thumb(1.dp, PullsElevation.thumb, 0.20f),

    /** A centered dialog on a wide screen. */
    Modal(8.dp, PullsElevation.modal, 0.22f),
}

/** The kit's divider: a 1dp rule along the top edge, in [color], drawn instead of Material's (rule 49). */
/**
 * Lets an element run [by] past both sides of its parent, out over a list's side padding, so a full-width row such as a
 * fold heading lines up with the same row in a list that has no padding (J18).
 */
fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
    val placeable = measurable.measure(wide)
    layout(constraints.constrainWidth(placeable.width - extra), placeable.height) { placeable.place(-extra / 2, 0) }
}

fun Modifier.hairlineTop(color: Color): Modifier = drawBehind { drawRect(color, Offset.Zero, Size(size.width, 1.dp.toPx())) }

/** The tight layer of a shadow: a 1dp drop with a 1px blur, at half the strength of the wide layer. */
internal val EDGE_DROP = 1.dp
internal const val EDGE_RADIUS = 1f
internal const val EDGE_SHARE = 0.5f

/** The platform draws the same two-layer canvas shadow from the shared shadow levels. */
@Composable
expect fun Modifier.floatShadow(shape: Shape, level: PullsShadow = PullsShadow.Button): Modifier

/** `.fl`: a 44dp circle that floats on the page with its own shadow; [flat] drops the fill and shadow, for the small actions inside a row. */
@Composable
fun FloatButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    flat: Boolean = false,
    container: Color = LocalPullsColors.current.raised,
    /** The one main action on a screen (Send) sounds at full weight; the rest are quiet. */
    emphasis: Emphasis = Emphasis.Subtle,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(size)
            .then(if (flat) Modifier else Modifier.floatShadow(CircleShape))
            .clip(CircleShape)
            .background(if (flat) Color.Transparent else container)
            .semantics { contentDescription = label }
            .cueClickable(emphasis = emphasis, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A [FloatButton] holding one prototype icon. */
@Composable
fun FloatIcon(glyph: LuGlyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, flat: Boolean = false, size: Dp = 44.dp, iconSize: Dp = 20.dp, container: Color = LocalPullsColors.current.raised, tint: Color = LocalPullsColors.current.text, primary: Boolean = false) {
    val c = LocalPullsColors.current
    FloatButton(onClick, label, modifier, size, enabled, flat, if (primary) c.primary else container, if (primary) Emphasis.Normal else Emphasis.Subtle) {
        IconSwap(glyph) { LuIcon(it, tint = if (primary) Color.White else tint, size = iconSize) } // raw-value-ok: the ink on the primary fill is white in both themes
    }
}

/** The send circle inside a [PillInput]: 40dp on the primary fill, the Orb in its place while [busy]. */
@Composable
fun SendButton(onClick: () -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    FloatButton(onClick, label, modifier, size = 40.dp, enabled = enabled, container = LocalPullsColors.current.primary, emphasis = Emphasis.Normal) {
        IconSwap(busy) { working -> if (working) Orb(size = 20.dp) else LuIcon(Lu.arrow, tint = Color.White, size = 20.dp) } // raw-value-ok: the ink on the primary fill is white in both themes
    }
}

/** `.cap` with `.ind`: a white pill of equal segments, the selected one marked by a sliding paper pill. */
@Composable
fun CapsuleTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    flat: Boolean = false,
    /** A mark before a label, such as an engine's own; the only icons a tab may carry. */
    leading: (@Composable (index: Int) -> Unit)? = null,
    /** False for a picker that sounds its own choice, so a tab does not also make the generic one. */
    sound: Boolean = true,
    /**
     * Where the pages under the tabs are, in tabs ([SidePagesState.at]): the pill then sits exactly there, so it moves
     * with the pages under a finger and as they glide, instead of running its own animation beside them (J15).
     */
    position: (() -> Float)? = null,
) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier.height(height).then(if (flat) Modifier else Modifier.floatShadow(shape)).clip(shape).background(c.raised).padding(2.dp),
    ) {
        val segment = maxWidth / labels.size.coerceAtLeast(1)
        val x by animateDpAsState(segment * selected, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), label = "capsule")
        val pill = if (position != null) {
            val last = labels.lastIndex.coerceAtLeast(0).toFloat()
            Modifier.graphicsLayer { translationX = segment.toPx() * position().coerceIn(0f, last) }
        } else {
            Modifier.offset(x = x)
        }
        Box(pill.width(segment).fillMaxHeight().clip(shape).background(c.paper2))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(shape).then(
                        if (sound) Modifier.cueClickable(direction = directionOf(selected, index), role = Role.Tab) { onSelect(index) }
                        else Modifier.clickable(role = Role.Tab) { onSelect(index) },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    // The mark and the word sit centred in the tab with room either side; a word too long for it ends in …
                    // instead of wrapping out of sight, which pushed the mark against the tab's edge (recording 15 Oct
                    // 15:37, 11:50 to 12:07; L22).
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        leading?.invoke(index)
                        Text(
                            label, Modifier.weight(1f, fill = false), style = PullsType.sm.copy(fontWeight = FontWeight.Medium),
                            color = if (index == selected) c.text else c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** `.chip`: a 36dp outlined pill; selected it fills with `--chipOn`. [count] is muted and tabular. */
@Composable
fun SoftChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: String? = null) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(50)
    val fill by animateColorAsState(if (selected) c.chipOn else Color.Transparent, tween(200), label = "chip-fill")
    val edge by animateColorAsState(if (selected) c.chipOn else c.border, tween(200), label = "chip-edge")
    Row(
        modifier.height(36.dp).clip(shape).background(fill).border(1.dp, edge, shape).cueClickable(role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = PullsType.sm, color = c.text, maxLines = 1)
        if (count != null) Text(count, style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, maxLines = 1)
    }
}

/**
 * `.tb`: text tabs on a hairline, the selected one underlined in the accent. Each tab is a 48dp-tall target that reaches
 * halfway to its neighbours, so the space between two labels hits one of them; the underline stays as wide as the label.
 * [spread] lays the labels across the whole width, the first at the start and the last at the end with even gaps
 * between. When they do not fit with 16dp between them the labels step down to body size (15sp), and only when they
 * still do not fit does the row keep a 24dp gap and scroll sideways; a label is never cut.
 *
 * The underline is one stroke that slides from tab to tab when the selection changes (250ms smooth-out), and leans
 * toward a neighbour by [pull] (-1 to 1, toward the next tab when positive) while a finger drags the page under it, so
 * it moves the way the page does (rule 105).
 */
@Composable
fun UnderlineTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    spread: Boolean = false,
    pull: () -> Float = { 0f },
    /** Where the pages under the tabs are, in tabs ([SidePagesState.at]): the stroke then sits exactly there (J15). */
    position: (() -> Float)? = null,
) {
    val c = LocalPullsColors.current
    val hairline = Modifier.drawBehind { drawRect(c.borderMute, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
    val ink = rememberTabInk(selected)
    val inked = Modifier.onPlaced { ink.host = it }.drawBehind {
        if (position != null) ink.drawAt(this, position(), c.accentTeal) else ink.draw(this, pull(), c.accentTeal)
    }
    if (!spread) {
        Row(modifier.fillMaxWidth().then(hairline).then(inked)) {
            labels.forEachIndexed { index, label ->
                UnderlineTab(label, index, selected, onSelect, ink, Modifier.padding(start = if (index == 0) 0.dp else 12.dp, end = 12.dp), Alignment.Center)
            }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth().then(hairline)) {
        val viewport = constraints.maxWidth
        val last = labels.lastIndex
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = remember(labels, viewport, density) {
            val base = PullsType.base.copy(fontWeight = FontWeight.Medium)
            val room = viewport - with(density) { 16.dp.roundToPx() } * last
            if (labels.sumOf { measurer.measure(it, base, softWrap = false, maxLines = 1).size.width } <= room) base else PullsType.sm.copy(fontWeight = FontWeight.Medium)
        }
        Layout(
            content = {
                labels.forEachIndexed { index, label ->
                    val align = when (index) {
                        0 -> Alignment.CenterStart
                        last -> Alignment.CenterEnd
                        else -> Alignment.Center
                    }
                    UnderlineTab(label, index, selected, onSelect, ink, Modifier, align, style)
                }
            },
            // The ink is drawn in the scrolled content, so it moves with the labels when they scroll.
            modifier = Modifier.horizontalScroll(rememberScrollState()).then(inked),
        ) { measurables, constraints ->
            val height = 48.dp.roundToPx()
            val natural = measurables.map { it.maxIntrinsicWidth(height) }
            val fits = last == 0 || natural.sum() + 16.dp.roundToPx() * last <= viewport
            val gap = if (fits && last > 0) (viewport - natural.sum()) / last else 24.dp.roundToPx()
            // Every tab reaches halfway into the gaps beside it, so the whole row is a target.
            val placeables = measurables.mapIndexed { index, measurable ->
                val reach = (if (index > 0) gap / 2 else 0) + (if (index < last) gap - gap / 2 else 0)
                measurable.measure(Constraints.fixed(natural[index] + reach, height))
            }
            val width = if (fits) maxOf(viewport, placeables.sumOf { it.width }) else placeables.sumOf { it.width }
            layout(width.coerceAtLeast(constraints.minWidth), height) {
                var x = 0
                placeables.forEach { it.place(x, 0); x += it.width }
            }
        }
    }
}

@Composable
private fun UnderlineTab(label: String, index: Int, selected: Int, onSelect: (Int) -> Unit, ink: TabInk, padding: Modifier, align: Alignment, style: TextStyle = PullsType.base.copy(fontWeight = FontWeight.Medium)) {
    val c = LocalPullsColors.current
    val on = index == selected
    val color by animateColorAsState(if (on) c.text else c.muted, tween(200), label = "tab-ink")
    Box(
        Modifier.height(48.dp).cueClickable(direction = directionOf(selected, index), role = Role.Tab, onClickLabel = label) { onSelect(index) }.then(padding),
        contentAlignment = align,
    ) {
        Box(Modifier.fillMaxHeight().onPlaced { ink.labels[index] = it }, contentAlignment = Alignment.Center) {
            Text(label, style = style, color = color, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The underline of [UnderlineTabs]: where each label sits, read from the layout as it is drawn, and where the stroke was
 * last drawn, so a change of tab slides it on from there, including from part of the way a finger had pulled it.
 */
private class TabInk(var selected: Int) {
    var host: LayoutCoordinates? = null
    val labels = HashMap<Int, LayoutCoordinates>()
    var move: Animatable<Float, AnimationVector1D> = Animatable(1f)
    private var fromLeft = Float.NaN
    private var fromWidth = Float.NaN
    private var drawnLeft = Float.NaN
    private var drawnWidth = Float.NaN

    /** The selection changed to [to]: the stroke slides there from where it was last drawn. */
    fun moveTo(to: Int) {
        fromLeft = drawnLeft
        fromWidth = drawnWidth
        selected = to
    }

    private fun span(index: Int): Pair<Float, Float>? {
        val within = host?.takeIf { it.isAttached } ?: return null
        val label = labels[index]?.takeIf { it.isAttached } ?: return null
        val box = within.localBoundingBoxOf(label, clipBounds = false)
        return box.left to box.width
    }

    fun draw(scope: DrawScope, pull: Float, color: Color) = with(scope) {
        val (toLeft, toWidth) = span(selected) ?: return@with
        val m = move.value
        var left = if (fromLeft.isNaN()) toLeft else fromLeft + (toLeft - fromLeft) * m
        var width = if (fromWidth.isNaN()) toWidth else fromWidth + (toWidth - fromWidth) * m
        // At rest, a finger dragging the page leans the stroke toward the tab it is pulling in.
        val lean = pull.coerceIn(-1f, 1f)
        if (m >= 1f && lean != 0f) {
            span(selected + if (lean > 0f) 1 else -1)?.let { (nextLeft, nextWidth) ->
                val k = abs(lean)
                left = toLeft + (nextLeft - toLeft) * k
                width = toWidth + (nextWidth - toWidth) * k
            }
        }
        drawnLeft = left
        drawnWidth = width
        val stroke = 2.dp.toPx()
        drawRect(color, Offset(left, size.height - stroke), Size(width, stroke))
    }

    /** The stroke at [at] tabs, between two labels while the pages under them are between two pages. */
    fun drawAt(scope: DrawScope, at: Float, color: Color) = with(scope) {
        val low = floor(at).toInt().coerceAtLeast(0)
        val (fromLeft, fromWidth) = span(low) ?: return@with
        val (toLeft, toWidth) = span(low + 1) ?: (fromLeft to fromWidth)
        val k = (at - low).coerceIn(0f, 1f)
        val left = fromLeft + (toLeft - fromLeft) * k
        val width = fromWidth + (toWidth - fromWidth) * k
        drawnLeft = left
        drawnWidth = width
        val stroke = 2.dp.toPx()
        drawRect(color, Offset(left, size.height - stroke), Size(width, stroke))
    }
}

@Composable
private fun rememberTabInk(selected: Int): TabInk {
    val ink = remember { TabInk(selected) }
    val motion = rememberMotionEnabled()
    // A new selection gets a new run from 0; the first one starts where it belongs.
    val move = remember(selected) { Animatable(if (selected == ink.selected) 1f else 0f) }
    if (selected != ink.selected) ink.moveTo(selected)
    ink.move = move
    LaunchedEffect(move) {
        if (move.value < 1f) {
            if (motion) move.animateTo(1f, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) else move.snapTo(1f)
        }
    }
    return ink
}

/** `.cb`: a 22dp rounded box, white with a grey edge, filled accent with a white tick once on. */
@Composable
fun PullsCheck(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp, onClick: (() -> Unit)? = null, label: String? = null) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(size * 0.32f)
    val fill by animateColorAsState(if (checked) c.primary else c.raised, tween(150), label = "check-fill")
    val edge by animateColorAsState(if (checked) c.primary else Color(0xFFC9CFCE), tween(150), label = "check-edge")
    // The box fills in 150ms and the tick is drawn in over 350ms (the Success check recipe, rule 73).
    val tick by animateFloatAsState(if (checked) 1f else 0f, tween(if (checked) PullsMotion.MEDIUM else PullsMotion.QUICK, easing = PullsMotion.InOut), label = "check-tick")
    val click = if (onClick != null) Modifier.cueClickable(direction = directionOf(!checked), role = Role.Checkbox, onClickLabel = label, onClick = onClick) else Modifier
    Box(modifier.size(size).clip(shape).background(fill).border(1.5.dp, edge, shape).then(click), contentAlignment = Alignment.Center) {
        if (tick > 0f) LuIcon(Lu.tick, tint = Color.White, size = size * 0.64f, progress = { tick })
    }
}

/** `.tog`: a 52x32 switch. */
@Composable
fun PullsSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalPullsColors.current
    val track by animateColorAsState(if (checked) c.primary else c.border, tween(200), label = "switch-track")
    val thumb by animateDpAsState(if (checked) 23.dp else 3.dp, tween(200), label = "switch-thumb")
    Box(
        modifier.size(52.dp, 32.dp).clip(CircleShape).background(track)
            .semantics { contentDescription = label }
            .cueClickable(direction = directionOf(!checked), enabled = enabled, role = Role.Switch, onClickLabel = label) { onChange(!checked) }
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Box(Modifier.offset(x = thumb, y = 3.dp).size(26.dp).floatShadow(CircleShape, PullsShadow.Thumb).clip(CircleShape).background(Color.White))
    }
}

/** `.cta`: the 52dp pill that carries a screen's one main action. */
@Composable
fun CtaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = LocalPullsColors.current.cta,
    contentColor: Color = LocalPullsColors.current.onCta,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.height(52.dp).clip(CircleShape).background(container).cueClickable(emphasis = Emphasis.Normal, enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else 0.5f).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = contentColor, maxLines = 1)
        trailing?.invoke()
    }
}

/** `.pill`: the quiet 44dp action beside the one main one (Retry, Stop, Rewrite): paper fill, medium 15sp ink, no shadow. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    Row(
        modifier.height(44.dp).clip(CircleShape).background(c.paper2).cueClickable(enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else 0.5f).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1)
    }
}

/**
 * `.ask`: the raised pill that holds a one-line question or comment at the bottom of a page, its action inside at the
 * right. It is the kit's one field in its page frame, so it has Home's search pill's height, padding and words (rule
 * 132; N6).
 */
@Composable
fun PillInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Send,
    onSend: () -> Unit = {},
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    FieldFrame(FieldPlace.Page, modifier, button = trailing != null) {
        FieldText(
            value, onChange, placeholder, Modifier.weight(1f), enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = { onSend() }, onGo = { onSend() }),
        )
        trailing?.invoke(this)
    }
}

/** The avatar placeholder: the prototype's warm gradient disc. */
@Composable
fun ProfileDot(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFD9B38C), Color(0xFF8C5A3C)))))
}

// --- Bottom sheet (PullsSheet lives in Sheet.kt) ---

/** `.r-card`: a white 16dp card on the sheet's paper, [gap] below what comes before it. */
@Composable
fun SheetCard(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().padding(top = gap).floatShadow(shape, PullsShadow.Note).clip(shape).background(LocalPullsColors.current.raised), content = content)
}

/**
 * `.r-mrow`: a 52dp row, a 22dp icon column, the label, and a trailing value; a hairline above it
 * starts after the icon column. [first] drops that hairline. [detail] is a second, muted line under the label.
 */
@Composable
fun SheetRow(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    /** `.noi`: no icon column, so the label starts at the edge and the hairline spans the row. */
    iconless: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    detail: String? = null,
    value: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    val inset = if (iconless) 16.dp else 52.dp
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
            .then(if (onClick != null) Modifier.cueClickable(emphasis = if (danger) Emphasis.Strong else Emphasis.Subtle, enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(inset.toPx(), 0f), Size(size.width - inset.toPx(), 1.dp.toPx())) }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!iconless) Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon?.invoke() }
        // A label wraps rather than being cut: no title ends in an ellipsis or a clipped word.
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = PullsType.row, color = if (danger) c.error else c.text)
            if (detail != null) Text(detail, style = PullsType.sm, color = c.muted)
        }
        if (value != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = value)
    }
}

/**
 * A field inside a sheet's white card: the label small and muted over the text, a hairline above it
 * unless it is the [first] row. [minLines] above one makes it a note area; [trailing] is for a word
 * such as Show, never an icon.
 */
@Composable
fun SheetField(
    label: String?,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    first: Boolean = false,
    enabled: Boolean = true,
    minLines: Int = 1,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onGo: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    Row(
        modifier.fillMaxWidth().drawBehind { if (!first) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
            .padding(start = 16.dp, end = if (trailing != null) 8.dp else 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (label != null) Text(label, style = PullsType.sm, color = c.muted)
            // The kit's one field, so its words, placeholder and scrolling edge are every field's (N6, N9).
            FieldText(
                value, onChange, placeholder, Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = minLines <= 1,
                minLines = minLines,
                secret = secret,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = keyboardType == KeyboardType.Text),
                keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }, onDone = { onGo?.invoke() }),
            )
        }
        trailing?.invoke()
    }
}

/**
 * The air between a sheet's ✕ and the first thing under it, a search or a heading: the Settings sheet's, so a field
 * never sits tight under the ✕ ("the gap between ... the closing button and the first field, it's too small":
 * recording 16 Oct 09:50, 03:18 to 03:27; M7).
 */
val SheetTopGap = 20.dp

/** How high a bar at the foot of a page or a sheet rests over the navigation bar ([floatingBar]). */
val BarLift = 24.dp

/**
 * Where a bar rests at the foot of a page or a sheet: 16dp in from the sides and [BarLift] over the navigation bar, on
 * every page alike, so the Inbox's search, the Overview's and the Tour's Ask bars, the diff's file bar and a sheet's own
 * field never sit at different heights ("the placement of the search and bottom bars and the buttons ... should be
 * consistent throughout all of them", recording of 17 Oct 13:26, 00:49 to 01:26, P3). It rises with the keyboard.
 */
fun Modifier.floatingBar(): Modifier = imePadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = BarLift)

/**
 * A heading over a card on a sheet, the one kind every sheet uses: the Settings sheet's small muted words and nothing
 * else, no count, no chevron and no line under it, and it folds nothing ("we get rid of this number, we get rid of this
 * divider, we get rid of this chevron, we keep them consistent": recording 16 Oct 09:50, 02:55 to 04:05; M7). [extra]
 * sits at its end, for the + and − a group of files shows (L20). [first] is the heading right under the ✕ or a search.
 */
@Composable
fun SheetHeading(label: String, modifier: Modifier = Modifier, first: Boolean = false, extra: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalPullsColors.current
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (first) 12.dp else 24.dp, bottom = 2.dp).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.weight(1f), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        extra?.invoke(this)
    }
}

/**
 * `.find`: the search field at the top of a sheet's list (Repositories, Files, Commands). It is the kit's one field in its
 * sheet frame, made like the cards under it, white with their 16dp corners and their soft shadow, so it reads as part of
 * the list and not as a pill floating over it: the magnifier, the placeholder in the muted ink, and ✕ to clear once
 * something is typed. The keyboard's [imeAction] runs [onAction], or puts the keyboard away when there is none.
 */
@Composable
fun SheetSearch(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Search,
    onAction: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val act = { if (onAction != null) onAction() else focus.clearFocus() }
    FieldFrame(FieldPlace.Sheet, modifier, search = true, button = value.isNotEmpty()) {
        FieldText(
            value, onChange, placeholder, Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { act() }, onGo = { act() }, onDone = { act() }, onSend = { act() }),
        )
        if (value.isNotEmpty()) FieldClear({ onChange("") })
    }
}

/**
 * The list a sheet picks from, with a [SheetSearch] over it once it is longer than [searchFrom] rows: themes, models,
 * past chats. Most sheets are easier with a search ("most bottom sheets that we have actually benefit a lot from
 * search": recording 15 Oct 15:37, 12:28 to 12:36; L4, L23, L36), so every picking list is this one component. [group]
 * splits the rows under small headings in their order, each run in its own card; [row] draws one, told whether it is
 * the first in its card. [text] is what a search matches, every word in any order (rule 101).
 */
@Composable
fun <T> ColumnScope.SearchList(
    items: List<T>,
    text: (T) -> String,
    placeholder: String,
    modifier: Modifier = Modifier,
    group: ((T) -> String?)? = null,
    searchFrom: Int = 8,
    empty: String = "Nothing matches",
    row: @Composable (item: T, first: Boolean) -> Unit,
) {
    val c = LocalPullsColors.current
    var query by remember { mutableStateOf("") }
    if (items.size > searchFrom) SheetSearch(query, { query = it }, placeholder, modifier.padding(top = SheetTopGap, bottom = 2.dp))
    val found = if (query.isBlank()) items else items.filter { matchesSearch(text(it), query) }
    if (found.isEmpty() && items.isNotEmpty()) {
        Text(empty, Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
        return
    }
    val runs = if (group == null) listOf(null to found) else searchRuns(found, group)
    runs.forEach { (label, rows) ->
        if (label != null) SheetHeading(label)
        SheetCard(gap = if (label != null) 8.dp else 12.dp) {
            rows.forEachIndexed { index, item -> row(item, index == 0) }
        }
    }
}

/** [items] split where [group] changes, in their order: keys A, B, A make three runs, never A twice over and then B. */
fun <T> searchRuns(items: List<T>, group: (T) -> String?): List<Pair<String?, List<T>>> {
    val runs = mutableListOf<Pair<String?, MutableList<T>>>()
    items.forEach { item ->
        val key = group(item)
        if (runs.isNotEmpty() && runs.last().first == key) runs.last().second.add(item) else runs.add(key to mutableListOf(item))
    }
    return runs
}

/** `.r-qa button`: a 72dp tile with an icon over a label. */
@Composable
fun QuickAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.height(72.dp).floatShadow(shape, PullsShadow.Note).clip(shape).background(LocalPullsColors.current.raised).cueClickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        icon()
        Text(label, style = PullsType.sm, color = LocalPullsColors.current.text, maxLines = 1)
    }
}

// --- Toast ---

@Stable
class ToastMessage(val text: String, val action: String?, val onAction: (() -> Unit)?)

/** `toast()`: one message at a time, with an optional action ("Undo"). */
@Stable
class Toaster {
    var message by mutableStateOf<ToastMessage?>(null)
        private set

    /** [cue] is the sound of what the message reports: a confirmed save, a refusal. A message that only echoes a tap that already sounded has none. */
    fun show(text: String, action: String? = null, cue: Cue? = null, onAction: (() -> Unit)? = null) {
        message = ToastMessage(text, action, onAction)
        if (cue != null) ControlFeedback.play(cue, Emphasis.Subtle)
    }

    fun clear(message: ToastMessage) {
        if (this.message === message) this.message = null
    }
}

val LocalToaster = staticCompositionLocalOf { Toaster() }

/**
 * `.toast`: a dark pill above the bottom bars; it leaves after 2.4s, or 4.2s when it offers an action.
 * It rises 16dp from 97% size through a 2px blur in 350ms and leaves in 250ms (the Toast recipe, rule 73).
 */
@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val current = toaster.message
    var shown by remember { mutableStateOf<ToastMessage?>(null) }
    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        shown = current
        delay(if (current.action != null) 4200 else 2400)
        toaster.clear(current)
    }
    val visible by animateFloatAsState(
        if (current != null) 1f else 0f,
        tween(if (current != null) PullsMotion.MEDIUM else PullsMotion.FAST, easing = PullsMotion.SmoothOut),
        label = "toast",
    )
    val message = current ?: shown
    if (message == null || visible < 0.01f) return
    Box(modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 100.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .softBlur(PullsMotion.BlurSmall * (1f - visible).coerceIn(0f, 1f))
                .graphicsLayer {
                    alpha = visible
                    translationY = (1f - visible) * 16.dp.toPx()
                    val scale = PullsMotion.DROPDOWN_OPEN_SCALE + (1f - PullsMotion.DROPDOWN_OPEN_SCALE) * visible
                    scaleX = scale
                    scaleY = scale
                }
                .height(44.dp).clip(CircleShape).background(c.cta).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(message.text, style = PullsType.sm, color = c.onCta, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (message.action != null) {
                Text(
                    message.action,
                    Modifier.cueClickable(role = Role.Button) { toaster.clear(message); message.onAction?.invoke() },
                    style = PullsType.sm.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFF9AD3D6),
                )
            }
        }
    }
}

// --- Progress and status marks ---

/**
 * The mark of an AI engine, in its own colour: Claude Code's orange, Codex's ink, or Pi's π in ink. It is the only AI
 * artwork the app keeps, and it names the engine a row or a setting is using (rule 48).
 */
@Composable
fun EngineMark(engine: String, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val c = LocalPullsColors.current
    val (art, name) = when (engine) {
        "codex" -> "ic_si_openai" to "Codex"
        "pi" -> "ic_ph_pi_bold" to "Pi"
        else -> "ic_si_claude" to "Claude Code"
    }
    Image(
        rememberVectorPainter(requireNotNull(sharedVector(art))),
        name,
        modifier.size(size),
        colorFilter = ColorFilter.tint(if (art == "ic_si_claude") Color(0xFFD97757) else c.text), // raw-value-ok: the brand colour of the Claude mark
    )
}

/** `.pend`: a hollow 10dp ring for a step that has not started. */
@Composable
fun PendingDot(modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).border(1.5.dp, Color(0xFFB9C4C5), CircleShape))
}

/**
 * `.sk`: a 12dp bar standing in for content that is still arriving. A soft light band, 40% of the bar wide, sweeps across it
 * from left to right every 1200ms on a linear curve (rule 85). With the system setting "Remove animations" it sits still.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val motion = rememberMotionEnabled()
    val transition = rememberInfiniteTransition(label = "skeleton")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(PullsMotion.SHIMMER, easing = LinearEasing)), label = "skeleton-sweep")
    val sheen = if (c.dark) c.active else c.raised
    Box(
        modifier.height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.chipOn)
            .drawBehind {
                if (!motion) return@drawBehind
                val band = size.width * 0.4f
                val left = -band + (size.width + band) * sweep
                drawRect(
                    Brush.horizontalGradient(listOf(sheen.copy(alpha = 0f), sheen, sheen.copy(alpha = 0f)), startX = left, endX = left + band),
                    topLeft = Offset(left, 0f), size = Size(band, size.height),
                )
            },
    )
}

/** A tiny style helper: [PullsType.sm] in [color]. */
@Composable
fun TextStyle.ink(color: Color): TextStyle = copy(color = color)
