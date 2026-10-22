package com.octaviantocan.pulls.ui.ai

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength

/** Native shaping and marker expansion fill the two gaps in Skia's inert SVG DOM. */
actual fun parseDiagramSvg(source: String): DiagramSvg {
    val data = Data.makeFromBytes(nativeDiagramVectors(source).encodeToByteArray())
    val document = try { SVGDOM(data) } finally { data.close() }
    val root = document.root
    if (root == null) {
        document.close()
        error("The diagram is not a valid SVG document.")
    }
    val drawing = NativeDiagramSvg(document, root.viewBox)
    // Android's renderToPicture explicitly uses the viewBox-sized viewport, even when
    // the document has an unrelated width/height. Give Skia the same viewport.
    root.width = SVGLength(drawing.pictureWidth.toFloat())
    root.height = SVGLength(drawing.pictureHeight.toFloat())
    root.close()
    return drawing
}

private class NativeDiagramSvg(private val document: SVGDOM, box: Rect?) : DiagramSvg {
    override val viewWidth = box?.width ?: 400f
    override val viewHeight = box?.height ?: 250f
    override val pictureWidth = viewWidth.toInt().coerceAtLeast(1)
    override val pictureHeight = viewHeight.toInt().coerceAtLeast(1)
    init { document.setContainerSize(pictureWidth.toFloat(), pictureHeight.toFloat()) }
    override fun draw(canvas: Canvas) { document.render(canvas.nativeCanvas) }
}
