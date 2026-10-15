package com.octaviantocan.pulls.ui.design

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.octaviantocan.pulls.sound.cueClickable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// --- Bottom sheet ---

/**
 * `.sheet`: a paper sheet with 24dp top corners, a grabber, and a header of round close button, centered
 * title and a spacer, or the round [trailing] button in its place (Ask's new chat). The body scrolls
 * under the header, which stays put. Every sheet rests at two heights and slides between them (rule 77):
 * its normal height, which is its content up to half the screen, and its tall height near the top of the
 * screen, which a short sheet reaches too: the room under its content is the sheet's own paper. [full]
 * makes the body fill the sheet (chat, Commands); its normal height is then half the screen, and its
 * [footer] stays on screen at either height. [expandable] off keeps a sheet at its normal height. [back]
 * swaps the close X for a back arrow.
 *
 * The sheet is the kit's own window, not Material's, so its motion follows rule 73's panel reveal: the
 * panel rises from below the screen over 400ms while fading in through a 2px blur, and leaves in
 * 350ms, both on the smooth-out curve. It follows a finger: dragged up from its normal height it goes to
 * the tall one, down from the tall height to the normal one, and down from the normal height it closes.
 * A field that takes the keyboard lifts it to the tall height, so the field never sits behind the keyboard.
 */
@Composable
fun PullsSheet(
    onClose: () -> Unit,
    title: @Composable () -> Unit,
    full: Boolean = false,
    back: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    scroll: ScrollState? = null,
    expandable: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = scroll ?: rememberScrollState()
    SheetHost(onClose, onBack = back) { position, dismiss ->
        SheetPanel(position, dismiss, full, expandable) {
            SheetHeader(
                leading = { if (back != null) FloatIcon(Lu.back, "Back", back) else FloatIcon(Lu.close, "Close", dismiss) },
                title = title,
                trailing = trailing,
            )
            SheetPage(scrollState, full, footer, Modifier.weight(1f, fill = full), content)
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
    expandable: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = PullsSheet(onClose, { SheetTitle(title) }, full, back, footer, scroll, expandable, trailing, content)

/**
 * A sheet whose pages slide inside it (rule 78). Language from Settings, Select model from the Analyze
 * card, Effort from the model list: the panel, the lip and the header stay where they are, the ✕ turns
 * into ← and the body slides sideways, 250ms through a 3px blur, exactly like a page change. [parent]
 * names the page ← goes back to (null for the first page); [onPage] changes the page, so the caller
 * owns where it is. Each page keeps its own scroll position, so back finds a list where it was left.
 * At its normal height the panel grows or shrinks to the next page's height as the body slides.
 */
@Composable
fun <P : Any> PullsPagedSheet(
    onClose: () -> Unit,
    page: P,
    parent: (P) -> P?,
    onPage: (P) -> Unit,
    title: @Composable (P) -> String,
    full: Boolean = false,
    footer: (@Composable ColumnScope.(P) -> Unit)? = null,
    content: @Composable ColumnScope.(P) -> Unit,
) {
    SheetHost(onClose, onBack = parent(page)?.let { up -> { onPage(up) } }) { position, dismiss ->
        PagedSheetPanel(position, dismiss, full, page, parent, onPage, title, footer, content)
    }
}

/**
 * The dialog window and the position of the panel in it; [panel] draws the scrim and the panel from where they are.
 * The system back goes to [onBack] when the sheet has a page to go back to, and closes the sheet when it has not.
 */
@Composable
private fun SheetHost(onClose: () -> Unit, onBack: (() -> Unit)? = null, panel: @Composable (SheetPosition, () -> Unit) -> Unit) {
    val motion = rememberMotionEnabled()
    val scope = rememberCoroutineScope()
    val position = remember { SheetPosition() }
    val dismiss: () -> Unit = {
        // The panel leaves first and the caller is told once it has gone.
        if (position.startClosing()) scope.launch {
            position.hide(motion)
            onClose()
        }
    }
    val latestBack by rememberUpdatedState(onBack)
    Dialog(
        onDismissRequest = { latestBack?.invoke() ?: dismiss() },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SheetWindow()
        panel(position, dismiss)
    }
}

/** The window the sheet draws in: edge to edge, no dim of its own (the scrim fades with the panel) and no window animation. */
@Composable
private fun SheetWindow() {
    val view = LocalView.current
    val light = LocalPullsColors.current.paper.luminance() > 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        // The panel's paper runs under the navigation bar, so its icons follow the paper.
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = light
    }
}

/**
 * Where a sheet's panel is, and how it gets there. The panel is always a tall height's worth of paper
 * ([height]); [offset] is how far it is pushed down from fully up, in px, while it is on its way or under
 * a finger. It rests at two stops (rule 77): fully up, its tall height, and its normal height, where it
 * shows its content up to half the window (half the window for a [fills] sheet). [reveal] is 0 to 1 and
 * drives the fade, the 2px blur and the scrim. Opening and closing take 400ms and 350ms (rule 73); a
 * finger moves [offset] directly and the panel settles on release. Once the panel rests it is pinned to
 * its stop: [shift] then works the stop out from the content's size on every frame, so a panel whose
 * content grows or shrinks (a page of another height sliding in) keeps its top where it belongs.
 */
@Stable
internal class SheetPosition {
    var offset by mutableFloatStateOf(0f)
        private set
    var reveal by mutableFloatStateOf(0f)
        private set

    /** The panel, which is the sheet's tall height, in px. */
    private var height by mutableIntStateOf(0)

    /** What the sheet's content takes, in px, at most [height]. A [fills] sheet does not measure it. */
    private var content by mutableIntStateOf(0)
    private var pinned by mutableStateOf(false)

    /** Half the window: the most a sheet shows at its normal height. */
    var half = 0f

    /** The sheet has a tall height to lift to; without one it rests at its normal height only. */
    var expandable = true

    /** Its content fills the panel (chat, Commands), so its normal height is half the window whatever it holds. */
    var fills = false

    /** Resting at the tall height, or on its way there. */
    var tall by mutableStateOf(false)
        private set

    /** Where the panel is resting or heading, so a change of [rest] can tell it has to move. */
    private var goal = 0f

    private var closing = false
    private var opened = false
    private var dragging = false
    private var offsetJob: Job? = null
    private var revealJob: Job? = null

    /** How far down a panel [h] px tall sits at its normal height. */
    private fun normalFor(h: Float): Float {
        val shows = when {
            fills -> if (expandable) half else h
            expandable -> minOf(content.toFloat(), half)
            else -> content.toFloat()
        }
        return (h - shows).coerceIn(0f, h)
    }

    private fun restFor(h: Float): Float = if (tall && expandable) 0f else normalFor(h)
    private val rest: Float get() = restFor(height.toFloat())

    /** The highest the panel goes: its tall height, or its normal one when it has no other. */
    private val highest: Float get() = if (expandable) 0f else normalFor(height.toFloat())

    /** How far down the panel sits, in px, for a panel [h] px tall: its stop while it rests, [offset] while it moves. */
    fun shift(h: Float): Float = if (pinned) restFor(h) else offset

    /** The panel has come to rest: not opening, not under a finger, not leaving. */
    val settled: Boolean get() = pinned

    /** Lower than it can go, so a finger moving up should raise it before the content scrolls. */
    val canRise: Boolean get() = shift(height.toFloat()) > highest + 0.5f

    /** A finger has moved the panel and not let go yet. */
    val held: Boolean get() = dragging

    /**
     * How tall a [fills] sheet lays its content out, in px: as much of the panel as shows, and never less than
     * its normal height, so the footer stays on screen at either height and rides down with the panel as it leaves.
     */
    fun shown(): Int {
        val h = height.toFloat()
        return (h - minOf(shift(h), normalFor(h))).roundToInt()
    }

    /** The scrim: fully there while the panel rests, gone as it is pushed off the screen. */
    val scrim: Float
        get() {
            val span = height - rest
            val gone = if (span <= 0f) 0f else ((shift(height.toFloat()) - rest) / span).coerceIn(0f, 1f)
            return reveal * (1f - gone)
        }

    fun startClosing(): Boolean {
        if (closing) return false
        closing = true
        return true
    }

    /** Takes the panel off its stop, so [offset] is where it is and can be moved. */
    private fun unpin() {
        if (!pinned) return
        offset = rest
        pinned = false
    }

    /** The panel has its size, the tall height: the first time it opens (once the content has a size too), later the stops follow it. */
    fun measured(px: Int, scope: CoroutineScope, motion: Boolean) {
        if (px == height || px <= 0) return
        height = px
        sized(scope, motion)
    }

    /** The content has a size; a [fills] sheet's is the panel's, so it is not asked. */
    fun measuredContent(px: Int, scope: CoroutineScope, motion: Boolean) {
        if (fills || px == content || px <= 0) return
        content = px
        sized(scope, motion)
    }

    private fun sized(scope: CoroutineScope, motion: Boolean) {
        when {
            closing -> Unit
            !opened -> if (height > 0 && (fills || content > 0)) open(scope, motion)
            else -> resized(scope, motion)
        }
    }

    /**
     * The panel or its content changed size while open. One resting on its stop stays pinned, and [shift]
     * moves it with the size; one on its way is sent to the stop's new place. One under a finger or on its
     * way out is left alone.
     */
    private fun resized(scope: CoroutineScope, motion: Boolean) {
        if (!opened || closing || dragging) return
        if (pinned) {
            goal = rest
            return
        }
        follow(scope, motion)
    }

    /**
     * Where the panel rests moved with no change of size, because half the window did (split screen): a panel
     * that was resting, or on its way, goes to the new place. One under a finger or on its way out is left alone.
     */
    fun follow(scope: CoroutineScope, motion: Boolean) {
        if (!opened || closing || dragging || abs(goal - rest) < 2f) return
        glide(scope, rest, PullsMotion.FAST, motion)
    }

    /** Half the window moved: a panel resting on its old stop is taken off it first, so it glides rather than jumps. */
    fun updateHalf(value: Float) {
        if (value == half) return
        unpin()
        half = value
    }

    private fun open(scope: CoroutineScope, motion: Boolean) {
        opened = true
        if (!motion) {
            offset = rest
            goal = rest
            reveal = 1f
            pinned = true
            return
        }
        offset = height.toFloat()
        glide(scope, rest, PullsMotion.SLOW, true)
        revealJob = scope.launch { animate(0f, 1f, animationSpec = tween(PullsMotion.SLOW, easing = PullsMotion.SmoothOut)) { v, _ -> reveal = v } }
    }

    private fun glide(scope: CoroutineScope, target: Float, ms: Int, motion: Boolean) {
        offsetJob?.cancel()
        unpin()
        goal = target
        if (!motion) {
            offset = target
            pinned = !closing && abs(target - rest) < 2f
            return
        }
        offsetJob = scope.launch {
            animate(offset, target, animationSpec = tween(ms, easing = PullsMotion.SmoothOut)) { v, _ -> offset = v }
            // It got where it was going: from here the stop follows the panel's size.
            pinned = !closing && abs(target - rest) < 2f
        }
    }

    /**
     * Up to the tall height, because a field took the keyboard or the content asked. A sheet that is not open
     * yet opens straight to it; one under a finger is left to the finger, and one with no tall height stays.
     */
    fun lift(scope: CoroutineScope, motion: Boolean) {
        if (!expandable || closing || tall) return
        tall = true
        if (opened && !dragging) glide(scope, 0f, PullsMotion.FAST, motion)
    }

    /** Moves the panel with a finger; returns how much of [dy] it used. */
    fun dragBy(dy: Float): Float {
        if (closing || height == 0) return 0f
        offsetJob?.cancel()
        unpin()
        dragging = true
        val next = (offset + dy).coerceIn(minOf(highest, offset), height.toFloat())
        val used = next - offset
        offset = next
        return used
    }

    /**
     * The finger lets go at [velocity] (px/s, down is positive): a quick throw goes on to the next
     * place in its direction, a slow release to the nearest one, and one dragged well past its normal
     * height dismisses.
     */
    fun settle(scope: CoroutineScope, velocity: Float, speed: Float, motion: Boolean, dismiss: () -> Unit) {
        if (closing) return
        unpin()
        dragging = false
        val normal = normalFor(height.toFloat())
        val rests = if (expandable && normal > 1f) listOf(0f, normal) else listOf(highest)
        val gone = height.toFloat()
        val target = sheetTarget(offset, velocity, speed, rests, gone)
        if (target >= gone) {
            dismiss()
            return
        }
        tall = expandable && target < 1f
        glide(scope, target, PullsMotion.FAST, motion)
    }

    /** Down and out in 350ms, fading through the blur as it goes. */
    suspend fun hide(motion: Boolean) {
        offsetJob?.cancel()
        revealJob?.cancel()
        unpin()
        if (!motion) {
            reveal = 0f
            offset = height.toFloat()
            return
        }
        coroutineScope {
            launch { animate(offset, height.toFloat(), animationSpec = tween(PullsMotion.MEDIUM, easing = PullsMotion.SmoothOut)) { v, _ -> offset = v } }
            launch { animate(reveal, 0f, animationSpec = tween(PullsMotion.MEDIUM, easing = PullsMotion.SmoothOut)) { v, _ -> reveal = v } }
        }
    }
}

/**
 * Where a sheet let go at [offset] goes: one of the [rests] (highest first) or [gone], off the screen.
 * A throw faster than [speed] (px/s, down is positive) goes on to the next place in its direction; a
 * slower release goes to the nearest rest, or dismisses when it was dragged more than 30% of the way
 * from the lowest rest to [gone].
 */
internal fun sheetTarget(offset: Float, velocity: Float, speed: Float, rests: List<Float>, gone: Float): Float {
    val low = rests.last()
    return when {
        velocity > speed -> rests.firstOrNull { it > offset + 1f } ?: gone
        velocity < -speed -> rests.lastOrNull { it < offset - 1f } ?: rests.first()
        offset - low > (gone - low) * 0.3f -> gone
        else -> rests.minBy { abs(it - offset) }
    }
}

/**
 * Lets the panel follow the content: scrolling up raises a lowered sheet first, and pulling down at the top of the
 * content lowers it. A fling settles the panel only when the finger moved it, so a fling inside a list scrolls the list.
 */
private class SheetScroll(
    private val position: SheetPosition,
    private val scope: CoroutineScope,
    private val speed: Float,
    private val motion: () -> Boolean,
    private val dismiss: () -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && position.canRise && source == NestedScrollSource.UserInput) Offset(0f, position.dragBy(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f && source == NestedScrollSource.UserInput) Offset(0f, position.dragBy(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!position.held) return Velocity.Zero
        position.settle(scope, available.y, speed, motion(), dismiss)
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (available.y > speed) position.settle(scope, available.y, speed, motion(), dismiss)
        return Velocity.Zero
    }
}

/**
 * The scrim and the panel with its lip; [body] is what goes in the panel, the header first and then a page. The window is
 * [PullsSheet]'s; this part can be drawn in a page. The panel is the tall height's worth of paper, pushed down to its
 * normal height or not at all; [full] lays the body out at the height that shows, and [detents] gives the panel its tall height.
 */
@Composable
internal fun SheetPanel(
    position: SheetPosition,
    dismiss: () -> Unit,
    full: Boolean,
    detents: Boolean,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPullsColors.current
    val motion = rememberMotionEnabled()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val screen = LocalConfiguration.current.screenHeightDp.dp
    // The tall height: near the top of the screen, with a strip of the page still showing above it.
    val tall = screen - maxOf(top, 16.dp) - 44.dp
    val window = LocalWindowInfo.current.containerSize.height
    position.expandable = detents
    position.fills = full
    position.updateHalf((if (window > 0) window.toFloat() else with(density) { screen.toPx() }) / 2f)
    // Half the window can change with no change to the panel (split screen): a sheet at its normal height moves to its new rest.
    LaunchedEffect(position.half, detents) { position.follow(scope, motion) }
    val latestDismiss by rememberUpdatedState(dismiss)
    val latestMotion by rememberUpdatedState(motion)
    // A field that takes the keyboard lifts the sheet to its tall height, so the field never sits behind the keyboard,
    // and a sheet brought down to its normal height puts the keyboard away.
    val ime = WindowInsets.ime
    val keyboard by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val latestKeyboard by rememberUpdatedState(keyboard)
    val focusManager = LocalFocusManager.current
    LaunchedEffect(keyboard) { if (keyboard) position.lift(scope, latestMotion) }
    LaunchedEffect(position.tall) { if (!position.tall && latestKeyboard) focusManager.clearFocus() }
    val control = remember(position, scope) { SheetControl(position) { position.lift(scope, latestMotion) } }
    val speed = with(density) { 200.dp.toPx() }
    val connection = remember(position, scope, speed) { SheetScroll(position, scope, speed, { latestMotion }, { latestDismiss() }) }
    val dragState = rememberDraggableState { position.dragBy(it) }
    val shape = RoundedCornerShape(topStart = PullsRadius.sheet, topEnd = PullsRadius.sheet)
    // A full sheet's body is as tall as what shows of the panel, so its footer is on screen at the normal height too;
    // any other body is as tall as its content, and the panel's paper runs on under it.
    val sized = if (full) {
        Modifier.layout { measurable, constraints ->
            val shown = position.shown().takeIf { it > 0 }?.coerceAtMost(constraints.maxHeight) ?: constraints.maxHeight
            val placeable = measurable.measure(constraints.copy(minHeight = shown, maxHeight = shown))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    } else {
        Modifier.heightIn(max = tall)
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = position.scrim }.background(c.scrim)
                .cueClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", role = Role.Button) { dismiss() },
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(tall)
                .onSizeChanged { position.measured(it.height, scope, motion) }
                .graphicsLayer {
                    translationY = position.shift(size.height)
                    val t = position.reveal
                    alpha = t
                    // The cross-blur of the panel reveal: 2px at the start, sharp once it is up.
                    if (BlurSupported && t < 1f) {
                        val radius = PullsMotion.BlurSmall.toPx() * (1f - t)
                        renderEffect = BlurEffect(radius, radius, TileMode.Decal)
                    }
                }
                .clip(shape).background(c.paper)
                .nestedScroll(connection)
                .draggable(dragState, Orientation.Vertical, onDragStopped = { v -> position.settle(scope, v, speed, latestMotion, latestDismiss) }),
        ) {
            Column(Modifier.fillMaxWidth().then(sized).imePadding().onSizeChanged { position.measuredContent(it.height, scope, motion) }) {
                Box(Modifier.padding(top = 10.dp, bottom = 14.dp).align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(c.grabber))
                CompositionLocalProvider(LocalSheetSettled provides position.settled, LocalSheet provides control) { body() }
            }
        }
    }
}

/** False while a sheet slides in, drags or leaves, true once its panel rests. */
val LocalSheetSettled = compositionLocalOf { true }

/**
 * The sheet a piece of content sits in, as the content sees it: whether the panel rests, whether it is at its
 * tall height, and a way to lift it there. Commands takes the keyboard once it is tall. Null outside a sheet.
 */
@Stable
class SheetControl internal constructor(private val position: SheetPosition, private val onLift: () -> Unit) {
    val settled: Boolean get() = position.settled
    val tall: Boolean get() = position.tall

    /** Up to the tall height; nothing when the sheet is there already or has none. */
    fun lift() = onLift()
}

val LocalSheet = compositionLocalOf<SheetControl?> { null }

/**
 * The header of a sheet: a round button, the title centered and a spacer as wide as the button. It sits
 * above the body (`zIndex`), so the button's shadow is never cut by the content below it, and the body
 * fades out under it (rule 74).
 */
@Composable
private fun SheetHeader(leading: @Composable () -> Unit, title: @Composable () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    // The same round white button, with its soft shadow, as on the page behind the sheet (rule 70).
    Row(Modifier.fillMaxWidth().zIndex(1f).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalTextStyle provides PullsType.lg.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)) { title() }
        }
        // A button on the right, or the room one takes, so the title stays centred.
        if (trailing != null) trailing() else Spacer(Modifier.width(44.dp))
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold), color = LocalPullsColors.current.text, maxLines = 1)
}

/** How deep the body fades out under the header and above a footer. */
private val PageFade = 20.dp

/**
 * One page of a sheet: the scrolling body, which dissolves under the header as it scrolls (rule 74,
 * so the ✕ never overlaps text and nothing is sliced), and the [footer] under it.
 */
@Composable
private fun SheetPage(scroll: ScrollState, full: Boolean, footer: (@Composable ColumnScope.() -> Unit)?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPullsColors.current
    val density = LocalDensity.current
    val band = with(density) { PageFade.toPx() }
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = full)
                .edgeFade(top = PageFade, color = c.paper, blur = PullsMotion.BlurMedium, topStrength = { (scroll.value / band).coerceIn(0f, 1f) })
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, bottom = if (footer == null) 30.dp else 8.dp).let { if (footer == null) it.navigationBarsPadding() else it },
            content = content,
        )
        if (footer != null) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp).navigationBarsPadding(), content = footer)
    }
}

/** The panel of a [PullsPagedSheet]: the header changes with the page, and the page slides through [BlurSwitch]. */
@Composable
internal fun <P : Any> PagedSheetPanel(
    position: SheetPosition,
    dismiss: () -> Unit,
    full: Boolean,
    page: P,
    parent: (P) -> P?,
    onPage: (P) -> Unit,
    title: @Composable (P) -> String,
    footer: (@Composable ColumnScope.(P) -> Unit)?,
    content: @Composable ColumnScope.(P) -> Unit,
) {
    // Each page's scroll position lives with the sheet, not with the page, so going back finds the list where it was left.
    val scrolls = remember { mutableMapOf<P, ScrollState>() }
    val depth = { p: P -> generateSequence(p, parent).count() }
    val up = parent(page)
    SheetPanel(position, dismiss, full, detents = true) {
        SheetHeader(
            leading = { if (up != null) FloatIcon(Lu.back, "Back", { onPage(up) }) else FloatIcon(Lu.close, "Close", dismiss) },
            title = { BlurSwitch(page, order = depth) { SheetTitle(title(it)) } },
        )
        BlurSwitch(page, Modifier.weight(1f, fill = full), order = depth) { p ->
            val pageFooter: (@Composable ColumnScope.() -> Unit)? = footer?.let { f -> { f(p) } }
            SheetPage(scrolls.getOrPut(p) { ScrollState(0) }, full, pageFooter, if (full) Modifier.fillMaxSize() else Modifier) { content(p) }
        }
    }
}
