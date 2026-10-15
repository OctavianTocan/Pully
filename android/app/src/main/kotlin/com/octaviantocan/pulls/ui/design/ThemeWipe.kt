package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.hypot

/**
 * Light to dark and back as a wipe (H5): the new look spreads in a circle from a top corner of the screen over the old
 * one, which is a still of every window taken just before the change (the page and any sheet over it). Each window
 * that takes part wears [themeWipe]; [run] takes the stills, makes the change and plays the wipe.
 */
object ThemeWipe {
    private val surfaces = mutableListOf<WipeSurface>()

    /** 0 to 1: how far the new look has spread. */
    internal var progress by mutableFloatStateOf(1f)
        private set

    /** The wipe starts from the top right corner, else the top left. */
    internal var fromRight by mutableStateOf(false)
        private set

    internal fun add(surface: WipeSurface) = synchronized(surfaces) { surfaces += surface }
    internal fun remove(surface: WipeSurface) = synchronized(surfaces) { surfaces -= surface }

    /**
     * Makes the change with [apply] and wipes it in from the top corner on the right when [fromRight], else on the left.
     * Without [motion] the change simply happens, and so it does when it [changes] nothing on screen: Light to System on
     * a phone that is light already keeps the same look, and a wipe of a look over itself only flickers (J2).
     */
    suspend fun run(fromRight: Boolean, motion: Boolean, changes: Boolean, apply: () -> Unit) {
        if (!motion || !changes) {
            apply()
            return
        }
        val stills = synchronized(surfaces) { surfaces.toList() }.map { surface ->
            surface to try { surface.layer.toImageBitmap() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
        this.fromRight = fromRight
        progress = 0f
        stills.forEach { (surface, still) -> surface.old = still }
        apply()
        try {
            animate(0f, 1f, animationSpec = tween(WIPE_MS, easing = PullsMotion.InOut)) { value, _ -> progress = value }
        } finally {
            progress = 1f
            stills.forEach { (surface, _) -> surface.old = null }
        }
    }
}

/** How long the new look takes to cover the screen. */
private const val WIPE_MS = 650

internal class WipeSurface(val layer: GraphicsLayer) {
    var old: ImageBitmap? by mutableStateOf(null)
}

/**
 * A window's part in [ThemeWipe]: its content is recorded every frame so a still can be taken, and while a wipe runs
 * the still of the old look is drawn over the new one with a growing hole cut from the corner.
 */
@Composable
fun Modifier.themeWipe(): Modifier {
    val layer = rememberGraphicsLayer()
    val surface = remember(layer) { WipeSurface(layer) }
    DisposableEffect(surface) {
        ThemeWipe.add(surface)
        onDispose { ThemeWipe.remove(surface) }
    }
    return drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
        val old = surface.old ?: return@drawWithContent
        val corner = Offset(if (ThemeWipe.fromRight) size.width else 0f, 0f)
        val radius = hypot(size.width, size.height) * ThemeWipe.progress
        val hole = Path().apply { addOval(Rect(corner, radius)) }
        clipPath(hole, ClipOp.Difference) { drawImage(old) }
    }
}
