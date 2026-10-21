package com.octaviantocan.pulls.ui.design

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * Draws [level] behind this element. Put it before `clip` and `background`, so the shadow lands
 * outside the shape. The drop is a Paint shadow layer, which draws on a hardware canvas from
 * API 28; older phones get a plain elevation, which is close but has no offset.
 */
@Composable
actual fun Modifier.floatShadow(shape: Shape, level: PullsShadow): Modifier {
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
