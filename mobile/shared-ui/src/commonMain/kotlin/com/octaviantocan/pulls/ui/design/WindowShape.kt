package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the window Pully draws in is shaped, which decides how its pages sit in it (rules 206 to 209). A phone held
 * upright is the shape everything was designed at (rule 25); the other two are the same pages, placed differently.
 */
enum class WindowShape {
    /** Narrower than [WIDE_FROM]: one page at a time, the whole width, as on a phone held upright (or split screen). */
    Phone,

    /**
     * Wide but not tall, a phone held sideways: one page at a time in a column no wider than [ReadableWidth], centred,
     * with the diff's code taking the whole width, a header that sits close under the status bar, and sheets that
     * rest at their tall height because half the window is too little to read (rules 207 and 208).
     */
    Sideways,

    /**
     * Wide and tall, an unfolded foldable or a tablet: the list on the left and the pull request it opened beside it,
     * each a phone-wide page with its own header and foot bar (rule 206).
     */
    Unfolded,
}

/** From this width a window is wide: [WindowShape.Sideways] or [WindowShape.Unfolded]. */
val WIDE_FROM: Dp = 600.dp

/** A wide window this tall or taller holds two pages side by side. */
val TALL_FROM: Dp = 600.dp

/**
 * The widest a column of text and rows grows to. Wider windows centre it, so a line of a description or a row of the
 * Inbox never stretches across a sideways phone or a tablet (rule 207).
 */
val ReadableWidth: Dp = 600.dp

/** The shape of a window [width] by [height]. */
fun windowShape(width: Dp, height: Dp): WindowShape = when {
    width < WIDE_FROM -> WindowShape.Phone
    height < TALL_FROM -> WindowShape.Sideways
    else -> WindowShape.Unfolded
}

/**
 * How wide the list is beside the pull request in a [WindowShape.Unfolded] window [width] wide: a little under half of it,
 * never narrower than 340 nor wider than 420; the pull request takes the rest, about a phone's width on a foldable.
 */
fun listPaneWidth(width: Dp): Dp = (width * 0.46f).coerceIn(340.dp, 420.dp)

/** The shape of the window this composable draws in (a sheet's window is the whole screen, like the app's). */
@Composable
internal fun rememberWindowShape(): WindowShape {
    val size = LocalWindowInfo.current.containerSize
    if (size.width <= 0 || size.height <= 0) return WindowShape.Phone
    val density = LocalDensity.current
    return with(density) { windowShape(size.width.toDp(), size.height.toDp()) }
}

/**
 * The width of the page a composable is on: the whole window on a phone, one pane of an unfolded window, a sheet's
 * panel. The diff sizes its columns from it, so split columns fit the pane they are in. Null outside the app's pages.
 */
internal val LocalPageWidth = staticCompositionLocalOf<Dp?> { null }

/** Where a pane of an unfolded window sits in it, in px: its left edge and its width. */
internal class PaneSpan(val left: Float, val width: Float)

/** The pane a page is in, so a sheet it opens rises over that pane rather than across both (rule 208). */
internal val LocalPaneSpan = compositionLocalOf<PaneSpan?> { null }

/**
 * True on the page that opened beside the list in an unfolded window, the first in its pane. Its header leaves Back out:
 * with the list in sight there is nowhere for it to go that a tap on the list does not reach (rule 210). A page opened
 * from inside it, a stack layer or a link, stacks in the pane and keeps its Back.
 */
val LocalFirstBeside = compositionLocalOf { false }

/** Fills the width up to [ReadableWidth] and centres itself in anything wider (rule 207). */
fun Modifier.readableWidth(): Modifier = fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ReadableWidth).fillMaxWidth()
