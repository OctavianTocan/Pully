@file:OptIn(ExperimentalMaterial3Api::class)

package com.octaviantocan.pulls.ui.design

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        paint.asFrameworkPaint().setShadowLayer(radius, 0f, level.dy.toPx(), tint.toArgb())
        val outline = shape.createOutline(size, layoutDirection, this)
        drawIntoCanvas { it.drawOutline(outline, paint) }
    }
}

/** `.fl`: a 44dp circle that floats on the page with its own shadow; [flat] drops the fill and shadow (sheet headers). */
@Composable
fun FloatButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    flat: Boolean = false,
    container: Color = LocalPullsColors.current.raised,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(size)
            .then(if (flat) Modifier else Modifier.floatShadow(CircleShape))
            .clip(CircleShape)
            .background(if (flat) Color.Transparent else container)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A [FloatButton] holding one prototype icon. */
@Composable
internal fun FloatIcon(glyph: LuGlyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, flat: Boolean = false, size: Dp = 44.dp, iconSize: Dp = 20.dp, container: Color = LocalPullsColors.current.raised, tint: Color = LocalPullsColors.current.text) {
    FloatButton(onClick, label, modifier, size, enabled, flat, container) { LuIcon(glyph, tint = tint, size = iconSize) }
}

/** `.cap` with `.ind`: a white pill of equal segments, the selected one marked by a sliding paper pill. */
@Composable
fun CapsuleTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, height: Dp = 44.dp, flat: Boolean = false) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier.height(height).then(if (flat) Modifier else Modifier.floatShadow(shape)).clip(shape).background(c.raised).padding(2.dp),
    ) {
        val segment = maxWidth / labels.size.coerceAtLeast(1)
        val x by animateDpAsState(segment * selected, tween(240, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)), label = "capsule")
        Box(Modifier.offset(x = x).width(segment).fillMaxHeight().clip(shape).background(c.paper2))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(shape).clickable(role = Role.Tab) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = if (index == selected) c.text else c.muted, maxLines = 1)
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
        modifier.height(36.dp).clip(shape).background(fill).border(1.dp, edge, shape).clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp),
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
 */
@Composable
fun UnderlineTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, spread: Boolean = false) {
    val c = LocalPullsColors.current
    Row(
        modifier.fillMaxWidth().drawBehind { drawRect(c.borderMute, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) },
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val color by animateColorAsState(if (on) c.text else c.muted, tween(200), label = "tab-ink")
            Box(
                Modifier.then(if (spread) Modifier.weight(1f) else Modifier).height(48.dp).clickable(role = Role.Tab, onClickLabel = label) { onSelect(index) }
                    .padding(start = if (index == 0 || spread) 0.dp else 12.dp, end = if (spread) 0.dp else 12.dp),
                contentAlignment = if (spread) Alignment.CenterStart else Alignment.Center,
            ) {
                Box(
                    Modifier.fillMaxHeight().drawBehind {
                        if (on) drawRect(c.accentTeal, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                    },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = PullsType.base.copy(fontWeight = FontWeight.Medium), color = color, maxLines = 1) }
            }
        }
    }
}

/** `.cb`: a 22dp rounded box, white with a grey edge, filled accent with a white tick once on. */
@Composable
fun PullsCheck(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp, onClick: (() -> Unit)? = null, label: String? = null) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(size * 0.32f)
    val fill by animateColorAsState(if (checked) c.primary else c.raised, tween(150), label = "check-fill")
    val edge by animateColorAsState(if (checked) c.primary else Color(0xFFC9CFCE), tween(150), label = "check-edge")
    val click = if (onClick != null) Modifier.clickable(role = Role.Checkbox, onClickLabel = label, onClick = onClick) else Modifier
    Box(modifier.size(size).clip(shape).background(fill).border(1.5.dp, edge, shape).then(click), contentAlignment = Alignment.Center) {
        if (checked) LuIcon(Lu.tick, tint = Color.White, size = size * 0.64f)
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
            .clickable(enabled = enabled, role = Role.Switch, onClickLabel = label) { onChange(!checked) }
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
        modifier.height(52.dp).clip(CircleShape).background(container).clickable(enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else 0.5f).padding(horizontal = 24.dp),
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
        modifier.height(44.dp).clip(CircleShape).background(c.paper2).clickable(enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else 0.5f).padding(horizontal = 16.dp),
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
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

// --- Bottom sheet ---

/**
 * `.sheet`: a paper sheet with 24dp top corners, a grabber, and a header of close button, centered
 * title and a spacer. The body scrolls; [full] makes it nearly full height (chat), otherwise it is
 * as tall as its content up to 62% of the screen. [expandable] is for long content: the sheet opens at half
 * the screen and pulls up to the full height. [back] swaps the close X for a back arrow.
 */
@Composable
fun PullsSheet(
    onClose: () -> Unit,
    title: @Composable () -> Unit,
    full: Boolean = false,
    back: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    scroll: ScrollState? = null,
    expandable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPullsColors.current
    val scrollState = scroll ?: rememberScrollState()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = !expandable)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onClose() } }
    val density = LocalDensity.current
    val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val screen = LocalConfiguration.current.screenHeightDp.dp
    val ceiling = screen - maxOf(top, 16.dp)
    val sized = when {
        full -> Modifier.height(ceiling - 44.dp)
        expandable -> Modifier.heightIn(max = ceiling - 44.dp)
        else -> Modifier.heightIn(max = screen * 0.62f)
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = c.paper,
        contentColor = c.text,
        tonalElevation = 0.dp,
        scrimColor = c.scrim,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(Modifier.fillMaxWidth().then(sized).imePadding()) {
            Box(Modifier.padding(top = 10.dp, bottom = 14.dp).align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFD9D9D6)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (back != null) FloatIcon(Lu.back, "Back", back, flat = true, iconSize = 22.dp)
                else FloatIcon(Lu.close, "Close", dismiss, flat = true, iconSize = 22.dp)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides PullsType.lg.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)) { title() }
                }
                Spacer(Modifier.width(44.dp))
            }
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = full).verticalScroll(scrollState).padding(start = 16.dp, end = 16.dp, bottom = if (footer == null) 30.dp else 8.dp).let { if (footer == null) it.navigationBarsPadding() else it },
                content = content,
            )
            if (footer != null) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp).navigationBarsPadding(), content = footer)
        }
    }
}

@Composable
fun PullsSheet(
    onClose: () -> Unit,
    title: String,
    full: Boolean = false,
    back: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    scroll: ScrollState? = null,
    expandable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) = PullsSheet(onClose, { Text(title, style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold), color = LocalPullsColors.current.text, maxLines = 1) }, full, back, footer, scroll, expandable, content)

/** `.r-card`: a white 16dp card on the sheet's paper. */
@Composable
fun SheetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().padding(top = 12.dp).floatShadow(shape, PullsShadow.Note).clip(shape).background(LocalPullsColors.current.raised), content = content)
}

/**
 * `.r-mrow`: a 52dp row, a 22dp icon column, the label, and a trailing value; a hairline above it
 * starts after the icon column. [first] drops that hairline.
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
    value: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    val inset = if (iconless) 16.dp else 52.dp
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(inset.toPx(), 0f), Size(size.width - inset.toPx(), 1.dp.toPx())) }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!iconless) Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon?.invoke() }
        Text(label, Modifier.weight(1f), style = PullsType.row, color = if (danger) c.error else c.text, maxLines = 1)
        if (value != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = value)
    }
}

/** `.r-qa button`: a 72dp tile with an icon over a label. */
@Composable
fun QuickAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.height(72.dp).floatShadow(shape, PullsShadow.Note).clip(shape).background(LocalPullsColors.current.raised).clickable(role = Role.Button, onClick = onClick),
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

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        message = ToastMessage(text, action, onAction)
    }

    internal fun clear(message: ToastMessage) {
        if (this.message === message) this.message = null
    }
}

val LocalToaster = staticCompositionLocalOf { Toaster() }

/** `.toast`: a dark pill above the bottom bars; it leaves after 2.4s, or 4.2s when it offers an action. */
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
    val visible by animateFloatAsState(if (current != null) 1f else 0f, tween(250), label = "toast")
    val message = current ?: shown
    if (message == null || visible < 0.01f) return
    Box(modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 100.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .graphicsLayer { alpha = visible; translationY = (1f - visible) * 10.dp.toPx() }
                .height(44.dp).clip(CircleShape).background(c.cta).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(message.text, style = PullsType.sm, color = c.onCta, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (message.action != null) {
                Text(
                    message.action,
                    Modifier.clickable(role = Role.Button) { toaster.clear(message); message.onAction?.invoke() },
                    style = PullsType.sm.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFF9AD3D6),
                )
            }
        }
    }
}

// --- Progress and status marks ---

/**
 * `.orb`: the live mark for AI work. While it works, a comet ring turns round a breathing dot; done,
 * it is a still wash with a tick. No robot, compass or sparkle: those marks were dropped (rule 48).
 */
@Composable
fun Orb(modifier: Modifier = Modifier, size: Dp = 30.dp, done: Boolean = false) {
    val c = LocalPullsColors.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (done) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(c.accentWash))
            LuIcon(Lu.check, tint = c.accentTeal, size = size * 0.5f)
        } else {
            RunningOrb(size)
        }
    }
}

@Composable
private fun RunningOrb(size: Dp) {
    val c = LocalPullsColors.current
    val transition = rememberInfiniteTransition(label = "orb")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "orb-turn")
    val breath by transition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "orb-breath")
    val head = Color(0xFF9AD3D6)
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = angle }) {
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
    Box(Modifier.size(size * 0.3f).graphicsLayer { alpha = breath; scaleX = 0.7f + 0.3f * breath; scaleY = 0.7f + 0.3f * breath }.clip(CircleShape).background(c.accentTeal))
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

/** The tour ring: a grey track and an accent arc for [n] of [of] stops. */
@Composable
fun ProgressRing(n: Int, of: Int, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val c = LocalPullsColors.current
    val sweep by animateFloatAsState(if (of <= 0) 0f else 360f * n.coerceIn(0, of) / of, tween(400), label = "ring")
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * (4f / 30f)
        // The arc's centre line sits 4/30 in from the edge, so a 4/30 stroke ends flush with it.
        val inset = this.size.minDimension * (4f / 30f)
        val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
        drawArc(c.border, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (sweep > 0f) drawArc(c.accentTeal, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** `.dot`: the live marker, a 10dp accent dot that breathes. */
@Composable
fun BreathingDot(modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "dot-phase")
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

/** `.sk`: a 12dp shimmering bar standing in for text that is still being written. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val x by transition.animateFloat(-240f, 240f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "skeleton-x")
    Box(
        modifier.height(12.dp).clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(c.paper2, c.accentWash, c.paper2), start = Offset(x, 0f), end = Offset(x + 480f, 0f))),
    )
}

/** A tiny style helper: [PullsType.sm] in [color]. */
@Composable
fun TextStyle.ink(color: Color): TextStyle = copy(color = color)
