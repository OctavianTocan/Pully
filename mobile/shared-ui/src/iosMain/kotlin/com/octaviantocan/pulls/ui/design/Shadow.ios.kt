package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.ImageFilter

/** Same canvas drop and tight edge as Android; Skia takes Gaussian sigma directly. */
@Composable
actual fun Modifier.floatShadow(shape: Shape, level: PullsShadow): Modifier {
    val c = LocalPullsColors.current
    val tint = c.shadow.copy(alpha = if (c.dark) (level.alpha * 2.7f).coerceAtMost(0.9f) else level.alpha)
    val fill = c.raised
    return drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val edge = Paint().apply { color = fill; isAntiAlias = true }
        val wide = Paint().apply { color = fill; isAntiAlias = true }
        val edgeSigma = 0.57735f * EDGE_RADIUS + 0.5f
        val radius = ((level.blur.toPx() / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
        val sigma = 0.57735f * radius + 0.5f
        val edgeFilter = ImageFilter.makeDropShadow(0f, EDGE_DROP.toPx(), edgeSigma, edgeSigma, tint.copy(alpha = tint.alpha * EDGE_SHARE).toArgb())
        val wideFilter = ImageFilter.makeDropShadow(0f, level.dy.toPx(), sigma, sigma, tint.toArgb())
        try {
            edge.skiaPaint.imageFilter = edgeFilter
            wide.skiaPaint.imageFilter = wideFilter
            drawIntoCanvas {
                it.drawOutline(outline, edge)
                it.drawOutline(outline, wide)
            }
        } finally {
            edgeFilter.close()
            wideFilter.close()
            edge.skiaPaint.close()
            wide.skiaPaint.close()
        }
    }
}
