package com.octaviantocan.pulls.ui.ai

import androidx.compose.ui.graphics.asComposeCanvas
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class NativeDiagramCompatibilityTest {
    @Test
    fun serverFontAttributesAndMultilineSpansKeepInheritedInkAndTheirAnchor() {
        // The native Rust renderer sets the family itself and positions each line
        // with tspan x/dy; adding a second font-family would invalidate this XML.
        val source = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 90"><g transform="translate(20 10)" fill="#2468ac" font-size="20" font-weight="600"><text x="100" y="25" font-family="sans-serif" text-anchor="middle"><tspan x="100" dy="0em">Review λ</tspan><tspan x="100" dy="1.3em">Saved</tspan></text></g></svg>"""
        raster(source) { image ->
            val first = ink(image, 60, 160, 10, 38)
            val second = ink(image, 60, 160, 40, 65)
            assertTrue(first.size > 30 && second.size > 30, "Both inherited-font lines must paint")
            assertTrue(abs((first.minOf { it.first } + first.maxOf { it.first }) / 2f - 120f) < 5)
            assertTrue(abs((second.minOf { it.first } + second.maxOf { it.first }) / 2f - 120f) < 5)
            assertEquals(0, image.getColor(10, 10))
            assertEquals(0, image.getColor(230, 80))
            assertTrue((first + second).all { (x, y) ->
                val color = image.getColor(x, y)
                (color and 0xFF) > ((color ushr 16) and 0xFF) * 2 && ((color ushr 8) and 0xFF) > ((color ushr 16) and 0xFF)
            })
        }
    }

    @Test
    fun inlineSpanFontChangesDoNotRestartTheMiddleAnchoredChunk() {
        raster("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 75"><text x="100" y="28" font-size="22" text-anchor="middle" fill="#2468ac">MM<tspan font-weight="bold">MM</tspan></text><text x="100" y="62" font-size="22" text-anchor="end" fill="#e23b47">MM<tspan font-weight="bold">MM</tspan></text></svg>""") { image ->
            val middle = ink(image, 0, 200, 2, 31)
            val end = ink(image, 0, 200, 35, 66)
            assertTrue(middle.size > 30 && end.size > 30)
            assertTrue(abs((middle.minOf { it.first } + middle.maxOf { it.first }) / 2f - 100f) < 5)
            assertTrue(end.maxOf { it.first } in 94..101, "End anchoring must cover the complete inline chunk")
            assertTrue(end.minOf { it.first } < middle.minOf { it.first } - 20)
        }
    }

    @Test
    fun curveArrowUsesItsEndTangentAndSharesThePathsTransformAndOpacity() {
        raster("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 90"><defs><marker id="end" viewBox="0 0 6 6" refX="5" refY="3" markerWidth="6" markerHeight="6" markerUnits="userSpaceOnUse" orient="auto"><path d="M0 0 L6 3 L0 6 Z" fill="#2468ac"/></marker></defs><path d="M20 60 C20 20 60 20 60 40" fill="none" stroke="#000000" stroke-width="1" marker-end="url(#end)" transform="translate(10 5)" opacity="0.5"/></svg>""") { image ->
            val arrow = image.getColor(71, 41)
            assertTrue(arrow ushr 24 in 20..140, "A downward tangent must turn and fade the arrow with its path")
            assertTrue(arrow and 0xFF > 0x60, "The marker must keep its own blue fill")
            assertEquals(0, image.getColor(5, 5))
        }
    }

    @Test
    fun markerReferenceUsesTheFittedNonzeroViewBox() {
        raster("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 110 70"><defs><marker id="box" viewBox="10 20 10 5" refX="15" refY="22.5" markerWidth="20" markerHeight="20" markerUnits="userSpaceOnUse"><rect x="10" y="20" width="10" height="5" fill="#2468ac"/></marker></defs><path d="M20 40 H80" fill="none" stroke="#000000" marker-end="url(#box)"/></svg>""") { image ->
            assertEquals(0xFF2468AC.toInt(), image.getColor(71, 36))
            assertEquals(0xFF2468AC.toInt(), image.getColor(89, 44))
            assertEquals(0, image.getColor(80, 30))
        }
    }

    @Test
    fun defaultMarkerUnitsScaleWithTheCurrentInheritedStrokeWidth() {
        raster("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 70"><defs><marker id="end" viewBox="0 0 6 6" refX="5" refY="3" markerWidth="6" markerHeight="6" orient="auto"><path d="M0 0 L6 3 L0 6 Z" fill="#2468ac"/></marker></defs><g stroke-width="2"><path d="M20 40 H70" fill="none" stroke="#000000" marker-end="url(#end)"/></g></svg>""") { image ->
            assertEquals(0xFF2468AC.toInt(), image.getColor(61, 36))
            assertEquals(0, image.getColor(58, 36))
        }
    }

    @Test
    fun markerPaintIsClippedToItsOwnViewport() {
        raster("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 70"><defs><marker id="box" viewBox="0 0 6 6" refX="3" refY="3" markerWidth="6" markerHeight="6" markerUnits="userSpaceOnUse"><rect x="-10" y="-10" width="30" height="30" fill="#2468ac"/></marker></defs><path d="M20 40 H70" fill="none" stroke="#000000" marker-end="url(#box)"/></svg>""") { image ->
            assertEquals(0xFF2468AC.toInt(), image.getColor(70, 38))
            assertEquals(0, image.getColor(70, 36))
            assertEquals(0, image.getColor(74, 40))
        }
    }

    @Test
    fun middleMarkerBisectsTheIncomingAndOutgoingDirections() {
        raster("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 80 80"><defs><marker id="middle" viewBox="0 0 6 6" refX="5" refY="3" markerWidth="6" markerHeight="6" markerUnits="userSpaceOnUse" orient="auto"><path d="M0 0 L6 3 L0 6 Z" fill="#2468ac"/></marker></defs><path d="M20 40 H40 V60" fill="none" stroke="#000000" stroke-width="1" marker-mid="url(#middle)"/></svg>""") { image ->
            assertTrue(image.getColor(38, 36) ushr 24 > 0, "The corner marker must turn through the bisector")
            assertEquals(0, image.getColor(38, 33))
        }
    }

    @Test
    fun unsupportedMarkupAndMissingMarkersFailRatherThanPartiallyDrawing() {
        for (content in listOf(
            "<image href=\"https://example.com/private.svg\"/>",
            "<script>alert(1)</script>",
            "<path d=\"M0 0L10 10\" marker-end=\"url(https://example.com/arrow.svg)\"/>",
            "<path d=\"M0 0L10 10\" marker-end=\"url(#missing)\"/>",
            "<text x=\"10\" y=\"20\" textLength=\"30\">Unsupported layout</text>",
        )) assertFails { parseDiagramSvg("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 40 40\">$content</svg>") }
        assertFails { parseDiagramSvg("<!DOCTYPE svg [<!ENTITY data SYSTEM 'file:///private/data'>]><svg xmlns=\"http://www.w3.org/2000/svg\"><text>&data;</text></svg>") }
        assertFails { parseDiagramSvg("<svg xmlns=\"http://www.w3.org/2000/svg\"><g></svg>") }
    }

    private fun ink(image: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): List<Pair<Int, Int>> = buildList {
        for (y in y0 until y1) for (x in x0 until x1) if (image.getColor(x, y) ushr 24 > 128) add(x to y)
    }

    private fun raster(source: String, assertion: (Bitmap) -> Unit) {
        val drawing = parseDiagramSvg(source)
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
