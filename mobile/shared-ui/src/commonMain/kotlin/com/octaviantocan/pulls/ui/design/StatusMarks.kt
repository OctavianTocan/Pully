package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    val moving = rememberMotionEnabled()
    var settled by remember { mutableStateOf(rest) }
    LaunchedEffect(rest) { if (!rest) settled = false }
    Crossfade(rest && settled, modifier.size(size), tween(if (moving) PullsMotion.QUICK else 0), label = "orb-settle") { still ->
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
    val breath = if (moving) {
        val transition = rememberInfiniteTransition(label = "orb")
        val value by transition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "orb-breath")
        value
    } else 1f
    val head = c.orbHead
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

/** The tour ring: a grey track and an accent arc for [n] of [of] stops, moving to a new count over 400ms. */
@Composable
fun ProgressRing(n: Int, of: Int, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val moving = rememberMotionEnabled()
    val fraction by animateFloatAsState(if (of <= 0) 0f else n.coerceIn(0, of).toFloat() / of, tween(if (moving) 400 else 0), label = "ring")
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

/** `.okd`: a 14dp green dot with a white tick, for a finished step. */
@Composable
fun OkDot(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Box(modifier.size(size).clip(CircleShape).background(LocalPullsColors.current.success), contentAlignment = Alignment.Center) {
        LuIcon(Lu.tick, tint = LocalPullsColors.current.onSuccess, size = size * 0.72f, strokeWidth = 3.2f)
    }
}

/** `.dot`: the live marker, a 10dp accent dot that breathes. [delayMs] staggers a row of them (typing dots start 150ms apart). */
@Composable
fun BreathingDot(modifier: Modifier = Modifier, size: Dp = 10.dp, delayMs: Int = 0) {
    val moving = rememberMotionEnabled()
    val phase = if (moving) {
        val transition = rememberInfiniteTransition(label = "dot")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse, StartOffset(delayMs)), label = "dot-phase")
        value
    } else 1f
    Box(
        modifier.size(size).graphicsLayer { alpha = 0.45f + 0.55f * phase; scaleX = 0.82f + 0.18f * phase; scaleY = 0.82f + 0.18f * phase }
            .clip(CircleShape).background(LocalPullsColors.current.accentTeal),
    )
}
