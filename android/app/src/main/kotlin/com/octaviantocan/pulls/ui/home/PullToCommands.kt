package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.sound.Haptics
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsType
import kotlin.math.max

/**
 * Pulling a list down past the top does not refresh it (that happens quietly, on its own). It opens
 * Commands: the list follows the finger a little, a hint says what releasing will do, and letting
 * go past the threshold calls [onTrigger].
 */
@Composable
internal fun PullToCommands(onTrigger: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val threshold = with(density) { 84.dp.toPx() }
    val limit = threshold * 1.4f
    var pull by remember { mutableFloatStateOf(0f) }
    val trigger by rememberUpdatedState(onTrigger)
    val connection = remember(threshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull > 0f && available.y < 0f) {
                    val used = max(available.y, -pull)
                    val armed = pull >= threshold
                    pull += used
                    if (armed && pull < threshold) Haptics.threshold()
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    // Resistance: the further it goes, the less each pixel of finger moves it.
                    val resisted = available.y * (1f - pull / limit).coerceIn(0.15f, 1f)
                    val armed = pull >= threshold
                    pull = (pull + resisted).coerceAtMost(limit)
                    // Felt when letting go would open Commands (H18).
                    if (!armed && pull >= threshold) Haptics.threshold()
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val start = pull
                if (start <= 0f) return Velocity.Zero
                if (start >= threshold) trigger()
                animate(start, 0f, animationSpec = tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { value, _ -> pull = value }
                return available
            }
        }
    }
    val c = LocalPullsColors.current
    Box(modifier.nestedScroll(connection)) {
        Box(Modifier.graphicsLayer { translationY = pull * 0.6f }) { content() }
        val progress = (pull / threshold).coerceIn(0f, 1f)
        if (progress > 0.05f) {
            Text(
                if (progress >= 1f) "Release for commands" else "Pull for commands",
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 2.dp).graphicsLayer { alpha = progress; translationY = pull * 0.3f - 12.dp.toPx() },
                style = PullsType.sm,
                color = c.muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
