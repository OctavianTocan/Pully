package com.octaviantocan.pulls.ui.design

import android.os.Build
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.sound.directionOf
import kotlinx.coroutines.delay

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

/** The tight layer of a shadow: a 1dp drop with a 1px blur, at half the strength of the wide layer. */
private val EDGE_DROP = 1.dp
private const val EDGE_RADIUS = 1f
private const val EDGE_SHARE = 0.5f

/**
 * Draws [level] behind this element. Put it before `clip` and `background`, so the shadow lands
 * outside the shape. The drop is a Paint shadow layer, which draws on a hardware canvas from
 * API 28; older phones get a plain elevation, which is close but has no offset.
 */
@Composable
fun Modifier.floatShadow(shape: Shape, level: PullsShadow = PullsShadow.Button): Modifier {
    val c = LocalPullsColors.current
    val tint = c.shadow.copy(alpha = if (c.dark) (level.alpha * 2.7f).coerceAtMost(0.9f) else level.alpha)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return this.shadow(level.blur / 2, shape, clip = false, ambientColor = tint, spotColor = tint)
    }
    val fill = c.raised
    return this.drawBehind {
        // Skia turns a Paint shadow radius into a Gaussian sigma of 0.57735 * radius + 0.5, and CSS
        // turns a blur of B into sigma B / 2. This is the radius that gives the canvas the same blur.
        val radius = ((level.blur.toPx() / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
        val paint = Paint().apply { color = fill }
        val outline = shape.createOutline(size, layoutDirection, this)
        // Two layers, as in the reference apps: a tight, faint edge right at the border, then the wide soft drop (rule 45).
        val edge = Paint().apply { color = fill }
        edge.asFrameworkPaint().setShadowLayer(EDGE_RADIUS, 0f, EDGE_DROP.toPx(), tint.copy(alpha = tint.alpha * EDGE_SHARE).toArgb())
        paint.asFrameworkPaint().setShadowLayer(radius, 0f, level.dy.toPx(), tint.toArgb())
        drawIntoCanvas {
            it.drawOutline(outline, edge)
            it.drawOutline(outline, paint)
        }
    }
}

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
internal fun FloatIcon(glyph: LuGlyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, flat: Boolean = false, size: Dp = 44.dp, iconSize: Dp = 20.dp, container: Color = LocalPullsColors.current.raised, tint: Color = LocalPullsColors.current.text, primary: Boolean = false) {
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
) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier.height(height).then(if (flat) Modifier else Modifier.floatShadow(shape)).clip(shape).background(c.raised).padding(2.dp),
    ) {
        val segment = maxWidth / labels.size.coerceAtLeast(1)
        val x by animateDpAsState(segment * selected, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), label = "capsule")
        Box(Modifier.offset(x = x).width(segment).fillMaxHeight().clip(shape).background(c.paper2))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(shape).then(
                        if (sound) Modifier.cueClickable(direction = directionOf(selected, index), role = Role.Tab) { onSelect(index) }
                        else Modifier.clickable(role = Role.Tab) { onSelect(index) },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        leading?.invoke(index)
                        Text(label, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = if (index == selected) c.text else c.muted, maxLines = 1)
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
 */
@Composable
fun UnderlineTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, spread: Boolean = false) {
    val c = LocalPullsColors.current
    val hairline = Modifier.drawBehind { drawRect(c.borderMute, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
    if (!spread) {
        Row(modifier.fillMaxWidth().then(hairline)) {
            labels.forEachIndexed { index, label ->
                UnderlineTab(label, index, selected, onSelect, Modifier.padding(start = if (index == 0) 0.dp else 12.dp, end = 12.dp), Alignment.Center)
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
                    UnderlineTab(label, index, selected, onSelect, Modifier, align, style)
                }
            },
            modifier = Modifier.horizontalScroll(rememberScrollState()),
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
private fun UnderlineTab(label: String, index: Int, selected: Int, onSelect: (Int) -> Unit, padding: Modifier, align: Alignment, style: TextStyle = PullsType.base.copy(fontWeight = FontWeight.Medium)) {
    val c = LocalPullsColors.current
    val on = index == selected
    val color by animateColorAsState(if (on) c.text else c.muted, tween(200), label = "tab-ink")
    Box(
        Modifier.height(48.dp).cueClickable(direction = directionOf(selected, index), role = Role.Tab, onClickLabel = label) { onSelect(index) }.then(padding),
        contentAlignment = align,
    ) {
        Box(
            Modifier.fillMaxHeight().drawBehind {
                if (on) drawRect(c.accentTeal, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
            },
            contentAlignment = Alignment.Center,
        ) { Text(label, style = style, color = color, maxLines = 1, softWrap = false) }
    }
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

/** `.ask`: the 52dp raised pill that holds a one-line question or comment, its action inside at the right. */
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
    val c = LocalPullsColors.current
    Row(
        modifier.fillMaxWidth().height(52.dp).floatShadow(CircleShape, PullsShadow.Search).clip(CircleShape).background(c.raised).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = PullsType.base.copy(color = c.text),
            cursorBrush = SolidColor(c.accentTeal),
            keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = { onSend() }, onGo = { onSend() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = PullsType.base, color = c.muted, maxLines = 1)
                    inner()
                }
            },
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

/** `.r-card`: a white 16dp card on the sheet's paper. */
@Composable
fun SheetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().padding(top = 12.dp).floatShadow(shape, PullsShadow.Note).clip(shape).background(LocalPullsColors.current.raised), content = content)
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
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = minLines <= 1,
                minLines = minLines,
                textStyle = PullsType.row.copy(color = c.text),
                cursorBrush = SolidColor(c.accentTeal),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = keyboardType == KeyboardType.Text),
                keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }, onDone = { onGo?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (value.isEmpty()) Text(placeholder, style = PullsType.row, color = c.faint)
                        inner()
                    }
                },
            )
        }
        trailing?.invoke()
    }
}

/** Does [text] match what was typed in a [SheetSearch]? Every word of [query] is somewhere in it, in any order and case (rule 101). */
fun matchesSearch(text: String, query: String): Boolean =
    query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { text.lowercase().contains(it) }

/**
 * `.find`: the search field at the top of a sheet's list (Repositories, Files). It is made like the cards under it,
 * white with their 16dp corners and their soft shadow, so it reads as part of the list and not as a pill floating over
 * it: a 48dp row with the magnifier, the placeholder in the muted ink, and ✕ to clear once something is typed.
 */
@Composable
fun SheetSearch(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(PullsRadius.card)
    Row(
        modifier.fillMaxWidth().height(48.dp).floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LuIcon(Lu.search, tint = c.muted, size = 20.dp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = PullsType.row.copy(color = c.text),
            cursorBrush = SolidColor(c.accentTeal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = PullsType.row, color = c.muted, maxLines = 1)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) FloatIcon(Lu.close, "Clear", { onChange("") }, flat = true, size = 40.dp, iconSize = 18.dp, tint = c.muted)
    }
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
        if (cue != null) Sounds.play(cue, Emphasis.Subtle)
    }

    internal fun clear(message: ToastMessage) {
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
 * `.orb`: the live mark for AI work. While it works, a comet ring turns round a breathing dot. When
 * [done] turns true the ring finishes the turn it is on and then settles into a still wash with a
 * tick (rule 72); one that starts out done is already still. [idle] is the same still wash with a small
 * dot instead of the tick, for work that has not run, or did not finish, so a tick never claims
 * something that was not done. [stop] turns the breathing dot into a small square, because the orb is
 * then the Stop button (rule 87). No robot, compass or sparkle: those marks were dropped (rule 48).
 */
@Composable
fun Orb(modifier: Modifier = Modifier, size: Dp = 30.dp, done: Boolean = false, idle: Boolean = false, stop: Boolean = false) {
    val c = LocalPullsColors.current
    val rest = done || idle
    var settled by remember { mutableStateOf(rest) }
    LaunchedEffect(rest) { if (!rest) settled = false }
    Crossfade(rest && settled, modifier.size(size), tween(PullsMotion.QUICK), label = "orb-settle") { still ->
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (still) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(c.accentWash))
                if (done) LuIcon(Lu.check, tint = c.accentTeal, size = size * 0.5f)
                else Box(Modifier.size(size * 0.3f).clip(CircleShape).background(c.accentTeal))
            } else {
                RunningOrb(size, finishing = rest, stop = stop) { settled = true }
            }
        }
    }
}

/** The time one turn of the orb's ring takes. */
private const val ORB_TURN_MS = 1600

@Composable
private fun RunningOrb(size: Dp, finishing: Boolean, stop: Boolean, onTurnDone: () -> Unit) {
    val c = LocalPullsColors.current
    val moving = rememberMotionEnabled()
    val turn = remember { Animatable(0f) }
    LaunchedEffect(finishing, moving) {
        when {
            !moving -> if (finishing) onTurnDone()
            // Round and round. An infinite animation, so a test that waits for idle is not held up by it.
            !finishing -> {
                turn.snapTo(0f)
                turn.animateTo(360f, infiniteRepeatable(tween(ORB_TURN_MS, easing = LinearEasing)))
            }
            // Stopped: complete the turn it is on, then settle.
            else -> {
                turn.animateTo(360f, tween(((360f - turn.value) / 360f * ORB_TURN_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
                onTurnDone()
            }
        }
    }
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "orb-breath")
    val head = Color(0xFF9AD3D6)
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = turn.value }) {
        val stroke = this.size.minDimension * (4f / 30f)
        drawArc(
            brush = Brush.sweepGradient(0f to c.accentTeal.copy(alpha = 0f), 0.7f to c.accentTeal, 1f to head, center = center),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke),
        )
    }
    val mark = Modifier.graphicsLayer { alpha = breath; scaleX = 0.7f + 0.3f * breath; scaleY = 0.7f + 0.3f * breath }
    if (stop) Box(Modifier.size(size * 0.34f).then(mark).clip(RoundedCornerShape(PullsRadius.xs)).background(c.accentTeal))
    else Box(Modifier.size(size * 0.3f).then(mark).clip(CircleShape).background(c.accentTeal))
}

/**
 * The mark of an AI engine, in its own colour: Claude Code's orange or Codex's ink. It is the only AI
 * artwork the app keeps, and it names the engine a row or a setting is using (rule 48).
 */
@Composable
fun EngineMark(engine: String, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val c = LocalPullsColors.current
    val claude = engine != "codex"
    Image(
        painterResource(if (claude) R.drawable.ic_si_claude else R.drawable.ic_si_openai),
        if (claude) "Claude Code" else "Codex",
        modifier.size(size),
        colorFilter = ColorFilter.tint(if (claude) Color(0xFFD97757) else c.text), // raw-value-ok: the brand colour of the Claude mark
    )
}

/** The tour ring: a grey track and an accent arc for [n] of [of] stops, moving to a new count over 400ms. */
@Composable
fun ProgressRing(n: Int, of: Int, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val fraction by animateFloatAsState(if (of <= 0) 0f else n.coerceIn(0, of).toFloat() / of, tween(400), label = "ring")
    ProgressRing(fraction, modifier, size)
}

/** The tour ring filled to [fraction] (0 to 1) as given, for a ring that follows the tour's scroll without jumps (rule 97). */
@Composable
fun ProgressRing(fraction: Float, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val c = LocalPullsColors.current
    val sweep = 360f * fraction.coerceIn(0f, 1f)
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * (4f / 30f)
        // The arc's centre line sits 4/30 in from the edge, so a 4/30 stroke ends flush with it.
        val inset = this.size.minDimension * (4f / 30f)
        val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
        drawArc(c.border, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (sweep > 0f) drawArc(c.accentTeal, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** `.dot`: the live marker, a 10dp accent dot that breathes. [delayMs] staggers a row of them (typing dots start 150ms apart). */
@Composable
fun BreathingDot(modifier: Modifier = Modifier, size: Dp = 10.dp, delayMs: Int = 0) {
    val transition = rememberInfiniteTransition(label = "dot")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse, StartOffset(delayMs)), label = "dot-phase")
    Box(
        modifier.size(size).graphicsLayer { alpha = 0.45f + 0.55f * phase; scaleX = 0.82f + 0.18f * phase; scaleY = 0.82f + 0.18f * phase }
            .clip(CircleShape).background(LocalPullsColors.current.accentTeal),
    )
}

/** `.pend`: a hollow 10dp ring for a step that has not started. */
@Composable
fun PendingDot(modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).border(1.5.dp, Color(0xFFB9C4C5), CircleShape))
}

/** `.okd`: a 14dp green dot with a white tick, for a finished step. */
@Composable
fun OkDot(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Box(modifier.size(size).clip(CircleShape).background(LocalPullsColors.current.success), contentAlignment = Alignment.Center) {
        LuIcon(Lu.tick, tint = Color.White, size = size * 0.72f, strokeWidth = 3.2f)
    }
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
