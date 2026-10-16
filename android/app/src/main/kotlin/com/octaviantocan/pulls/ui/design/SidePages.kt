package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.octaviantocan.pulls.sound.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where a row of side-by-side pages sits, in pages: 0 is the first page exactly, 0.5 halfway to the second. It is
 * hoisted so the parts of a screen around the pages can use it too: a header that swipes the pages ([sideSwipe]) and
 * tabs whose marker sits where the pages are ([at]), so the two move as one (J15, J21).
 */
@Stable
class SidePagesState internal constructor(start: Float) {
    /** Where the pages are, in pages. Read it in a layer or a draw block, so following it never recomposes. */
    var at by mutableFloatStateOf(start)
        internal set

    internal var glide: Job? = null

    /** The page a swipe settled on that the caller has yet to take up, so the change is not glided twice. */
    internal var thrownTo by mutableIntStateOf(-1)

    /** How fast the finger let go, in pages per second, for the glide that follows it. */
    internal var speed = 0f
    internal var width by mutableIntStateOf(0)

    /** The room between two pages (px); a page moves by its width and this, so the drag divides by both. */
    internal var gap = 0f

    /** Where the pages were when the finger went down, so a throw counts only once the finger has moved them. */
    private var startAt = 0f
    internal var index = 0
    internal var last = 0
    internal var moving = true
    internal var fling = 0f
    internal var scope: CoroutineScope? = null
    internal var onPage: (Int) -> Unit = {}

    /** Called when a swipe goes on past the last page, and how far past it the finger may pull the pages (in pages). */
    internal var onPastEnd: (() -> Unit)? = null
    internal var stretch = 0f

    internal fun glideTo(page: Int, speed: Float = 0f) {
        glide?.cancel()
        if (!moving) {
            at = page.toFloat()
            return
        }
        glide = scope?.launch {
            animate(at, page.toFloat(), initialVelocity = speed, animationSpec = PullsMotion.PageSpring) { value, _ -> at = value }
        }
    }

    /** One page's travel (px): its width and the gap to the next, the distance [SidePages] moves it by. */
    private val travel get() = width + gap

    // The pages move exactly as far as the finger: dividing by the width alone ran them ahead of it by the gap ("it
    // doesn't follow my fingers properly", recording 15 Oct 15:37, 04:15 to 04:27; L6).
    internal val drag = DraggableState { delta ->
        if (width <= 0) return@DraggableState
        val end = last + if (onPastEnd != null) stretch else 0f
        at = (at - delta / travel).coerceIn(0f, end)
    }

    internal fun started() {
        glide?.cancel()
        startAt = at
    }

    internal fun stopped(velocity: Float) {
        val now = at
        val speed = if (width > 0) -velocity / travel else 0f
        // A flick turns the page only once the finger has taken it part of the way: a small nudge let go quickly settles
        // back instead of the page going on by itself ("it just kind of swipes by itself", 04:22 to 04:27; L6).
        val moved = abs(now - startAt) >= THROW_AFTER
        // Pulled on past the last page, or thrown that way from it: the screen goes on to what follows (rule 93).
        if (onPastEnd != null && now > last && (now - last > stretch / 2f || velocity < -fling)) {
            glideTo(last, speed)
            onPastEnd?.invoke()
            return
        }
        // A throw goes on to the next page the way it went; a slow drag settles on the nearer one.
        val to = when {
            moved && velocity < -fling -> ceil(now).toInt()
            moved && velocity > fling -> floor(now).toInt()
            else -> now.roundToInt()
        }.coerceIn(0, last)
        if (to != index) {
            Haptics.page()
            thrownTo = to
            this.speed = speed
            onPage(to)
        } else {
            glideTo(to, speed)
        }
    }
}

/**
 * The state of a row of [count] side-by-side pages showing [index]. A change of [index] from elsewhere, a tab, glides
 * the row there; [onPage] takes the page a swipe settled on. [onPastEnd], when given, lets a swipe go on past the last
 * page (the Overview's last tab on to the Diff, rule 93).
 */
@Composable
fun rememberSidePages(index: Int, count: Int, onPage: (Int) -> Unit, onPastEnd: (() -> Unit)? = null): SidePagesState {
    val state = remember { SidePagesState(index.toFloat()) }
    val latest by rememberUpdatedState(onPage)
    val latestPast by rememberUpdatedState(onPastEnd)
    state.scope = rememberCoroutineScope()
    state.moving = rememberMotionEnabled()
    state.fling = with(LocalDensity.current) { PullsMotion.Fling.toPx() }
    state.gap = with(LocalDensity.current) { PullsMotion.PageGap.toPx() }
    state.index = index
    state.last = (count - 1).coerceAtLeast(0)
    state.onPage = { latest(it) }
    state.onPastEnd = if (onPastEnd != null) ({ latestPast?.invoke() }) else null
    state.stretch = PAST_END
    LaunchedEffect(index) {
        val speed = if (state.thrownTo == index) state.speed else 0f
        state.thrownTo = -1
        state.speed = 0f
        if (state.at != index.toFloat()) state.glideTo(index, speed)
    }
    return state
}

/** How far (in pages) the finger must move the pages before a throw turns them. */
private const val THROW_AFTER = 0.12f

/** How far past the last page a finger may pull, in pages, when a swipe can go on from there. */
private const val PAST_END = 0.3f

/**
 * A sideways drag here moves [state]'s pages, for the parts of a screen beside them that have no sideways swipe of
 * their own: a header over the pages, the empty room under a short list (J21).
 */
fun Modifier.sideSwipe(state: SidePagesState): Modifier = draggable(
    state.drag, Orientation.Horizontal,
    onDragStarted = { state.started() },
    onDragStopped = { velocity -> state.stopped(velocity) },
)

/**
 * Screens that sit side by side, such as the Inbox and Tours (H20, as Grok's Ask, Imagine and Build do): the next one
 * is just past the right edge, a drag anywhere the screens do not use themselves moves both with the finger, and
 * letting go settles on the nearer one, or on the next one the way a throw went (rule 105). A change of [target] from
 * elsewhere, a tab, glides the row there. Nothing fades or blurs: the screens only move. [pages] is the order they sit
 * in and [onPage] takes the one a swipe settled on. Only the screens in view are composed (and, with `warm`, the ones
 * beside them), but each keeps what it
 * remembers, its scroll above all, while it is out of view: coming back finds it where it was left (J9). A swipe that a
 * screen takes itself (a row's swipe) does not move the pages.
 */
@Composable
fun <S> SidePages(
    target: S,
    pages: List<S>,
    onPage: (S) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (S) -> Unit,
) {
    val index = pages.indexOf(target).coerceAtLeast(0)
    val state = rememberSidePages(index, pages.size, { onPage(pages[it]) })
    SidePages(state, pages, modifier, content = content)
}

/**
 * [SidePages] on a [state] the screen holds, so its header and tabs can follow and move the same pages. With [warm],
 * the pages beside the one at rest are built too, off screen, once the screen has drawn its first frame: a swipe then
 * starts on a page that is already there. Built in the drag's first frame instead, a heavy page (the Conversation)
 * held the page still and then jumped it to the finger ("from summary to conversation ... there is a bit of
 * resistance at the start ... it pops a bit": recording 16 Oct 09:50, 03:29 to 03:53; M8).
 */
@Composable
fun <S> SidePages(
    state: SidePagesState,
    pages: List<S>,
    modifier: Modifier = Modifier,
    warm: Boolean = false,
    content: @Composable (S) -> Unit,
) {
    // Each page's remembered state (its list's scroll, a field) outlives its leaving the screen.
    val saved = rememberSaveableStateHolder()
    var drawn by remember { mutableStateOf(false) }
    if (warm) LaunchedEffect(Unit) { withFrameNanos { }; drawn = true }
    val shown by remember(pages) { derivedStateOf { pages.indices.filter { abs(it - state.at).let { d -> d < 1f || (drawn && d <= 1f) } } } }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { state.width = it.width }
            .sideSwipe(state),
    ) {
        for (page in shown) {
            key(pages[page]) {
                // A page built ahead, wholly off screen, is not read out.
                val away by remember(page) { derivedStateOf { abs(page - state.at) >= 1f } }
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = (page - state.at) * (size.width + PullsMotion.PageGap.toPx()) }.then(if (away) Modifier.clearAndSetSemantics { } else Modifier)) {
                    saved.SaveableStateProvider(pages[page].toString()) { content(pages[page]) }
                }
            }
        }
    }
}
