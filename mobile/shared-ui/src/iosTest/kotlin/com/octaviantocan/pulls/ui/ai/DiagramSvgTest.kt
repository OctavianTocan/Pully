package com.octaviantocan.pulls.ui.ai

import androidx.compose.ui.graphics.asComposeCanvas
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real native SVG engine and the same Compose canvas bridge used by the diagram. */
class DiagramSvgTest {
    @Test
    fun viewBoxAndTranslatedPathsRenderAtTheirOriginalCoordinates() {
        val drawing = parseDiagramSvg("""<svg xmlns="http://www.w3.org/2000/svg" width="80" height="40" viewBox="10 20 80 40"><rect x="10" y="20" width="80" height="40" fill="#2468ac"/><g transform="translate(20 30)"><rect width="10" height="10" fill="#e23b47"/></g></svg>""")
        assertEquals(80f, drawing.viewWidth)
        assertEquals(40f, drawing.viewHeight)
        raster(drawing) { image ->
            assertEquals(0xFF2468AC.toInt(), image.getColor(5, 5))
            assertEquals(0xFFE23B47.toInt(), image.getColor(15, 15))
            assertEquals(0xFF2468AC.toInt(), image.getColor(79, 39))
        }
    }

    @Test
    fun explicitDocumentSizeDoesNotChangeTheViewBoxSizedPictureViewport() {
        val drawing = parseDiagramSvg("""<svg xmlns="http://www.w3.org/2000/svg" width="800" height="400" viewBox="0 0 80 40"><rect width="80" height="40" fill="#2468ac"/><rect x="60" y="20" width="20" height="20" fill="#e23b47"/></svg>""")
        raster(drawing) { image ->
            assertEquals(0xFF2468AC.toInt(), image.getColor(5, 5))
            assertEquals(0xFFE23B47.toInt(), image.getColor(79, 39))
        }
    }

    @Test
    fun serverDiagramLabelsArePaintedByTheNativeFontEngine() {
        val drawing = parseDiagramSvg("""<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40" viewBox="0 0 120 40"><text x="8" y="28" font-size="22" fill="#2468ac">Review</text></svg>""")
        raster(drawing) { image ->
            val labelPixels = (2 until 33).sumOf { y -> (8 until 100).count { x -> image.getColor(x, y) ushr 24 > 0 } }
            assertTrue(labelPixels > 20, "Mermaid labels must remain visible through the actual native SVG font engine")
            assertEquals(0, image.getColor(115, 35))
        }
    }

    @Test
    fun diagramStrokeAndMarkerArePaintedAsVectors() {
        val drawing = parseDiagramSvg("""<svg xmlns="http://www.w3.org/2000/svg" width="80" height="40" viewBox="0 0 80 40"><defs><marker id="end" viewBox="0 0 6 6" refX="5" refY="3" markerWidth="6" markerHeight="6" markerUnits="userSpaceOnUse" orient="auto-start-reverse"><path d="M0 0 L6 3 L0 6 Z" fill="#000000"/></marker></defs><path d="M10 30 H60" fill="none" stroke="#000000" stroke-width="2" marker-end="url(#end)"/></svg>""")
        raster(drawing) { image ->
            assertEquals(0xFF000000.toInt(), image.getColor(30, 30))
            assertTrue(image.getColor(56, 28) ushr 24 > 0, "The SVG marker must paint above the line's stroke")
            assertEquals(0, image.getColor(5, 5))
        }
    }

    private fun raster(drawing: DiagramSvg, assertion: (Bitmap) -> Unit) {
        val surface = Surface.makeRasterN32Premul(drawing.pictureWidth, drawing.pictureHeight)
        val bitmap = Bitmap()
        try {
            surface.canvas.clear(0)
            drawing.draw(surface.canvas.asComposeCanvas())
            assertTrue(bitmap.allocN32Pixels(drawing.pictureWidth, drawing.pictureHeight))
            assertTrue(surface.readPixels(bitmap, 0, 0))
            assertion(bitmap)
        } finally {
            bitmap.close()
            surface.close()
        }
    }
}
