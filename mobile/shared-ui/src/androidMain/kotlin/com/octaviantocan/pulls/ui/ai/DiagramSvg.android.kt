package com.octaviantocan.pulls.ui.ai

import android.graphics.Picture
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import com.caverock.androidsvg.SVG

/** Original AndroidSVG parser and renderToPicture path, retaining its existing compatibility normalization. */
actual fun parseDiagramSvg(source: String): DiagramSvg = AndroidDiagramSvg(SVG.getFromString(androidSvgSafe(source)))

private class AndroidDiagramSvg(svg: SVG) : DiagramSvg {
    override val viewWidth = svg.documentViewBox?.width() ?: 400f
    override val viewHeight = svg.documentViewBox?.height() ?: 250f
    private val picture: Picture = svg.renderToPicture(viewWidth.toInt().coerceAtLeast(1), viewHeight.toInt().coerceAtLeast(1))
    override val pictureWidth = picture.width
    override val pictureHeight = picture.height
    override fun draw(canvas: Canvas) { canvas.nativeCanvas.drawPicture(picture) }
}
