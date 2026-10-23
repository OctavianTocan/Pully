package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/** The same server-rendered SVG, with its document size and native vector drawing. */
interface DiagramSvg {
    val viewWidth: Float
    val viewHeight: Float
    val pictureWidth: Int
    val pictureHeight: Int
    fun draw(canvas: ComposeCanvas)
}

/** Parses inert vector data; neither platform executes SVG scripts or opens a WebView. */
expect fun parseDiagramSvg(source: String): DiagramSvg

/** What AndroidSVG 1.4 cannot read in beautiful-mermaid's output. */
fun androidSvgSafe(svg: String): String = svg
    .replace("orient=\"auto-start-reverse\"", "orient=\"auto\"")
    .replace(Regex("<style>[\\s\\S]*?</style>"), "")
    // The removed style set the font; without it AndroidSVG falls back to serif.
    .replace("<text ", "<text font-family=\"sans-serif\" ")

fun diagramRatio(svg: DiagramSvg): Float =
    if (svg.viewHeight > 0) (svg.viewWidth / svg.viewHeight).coerceIn(0.2f, 5f) else 1.6f

/** Original diagram fit: vector paths remain sharp at either the inline or zoomed size. */
@Composable
fun SvgView(svg: DiagramSvg, modifier: Modifier) {
    Canvas(modifier) {
        val scale = minOf(size.width / svg.pictureWidth, size.height / svg.pictureHeight)
        val x = (size.width - svg.pictureWidth * scale) / 2f
        val y = (size.height - svg.pictureHeight * scale) / 2f
        drawIntoCanvas { canvas ->
            canvas.save()
            try {
                canvas.translate(x, y)
                canvas.scale(scale, scale)
                svg.draw(canvas)
            } finally {
                canvas.restore()
            }
        }
    }
}
