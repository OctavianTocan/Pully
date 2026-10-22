@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.ui.ai

import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMetrics
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.FontWidth
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathVerb
import org.jetbrains.skia.Point
import org.jetbrains.skia.TextBlob
import org.jetbrains.skia.shaper.Shaper
import org.jetbrains.skia.shaper.ShapingOptions
import platform.UIKit.UIFont

internal fun outlineNativeSvgText(element: NativeSvgElement, style: NativeSvgStyle, width: Float, height: Float): String =
    NativeTextLayout(width, height).render(element, style)

private sealed interface TextVector {
    fun markup(): String
}
private class TextVectorGroup(val attributes: Map<String, String>, val children: MutableList<TextVector> = mutableListOf()) : TextVector {
    override fun markup(): String = svgElement("g", attributes, children.joinToString("") { it.markup() })
}
private class TextVectorPath(val d: String, val x: Float, val y: Float) : TextVector {
    var anchorOffset = 0f
    override fun markup(): String = svgElement("path", mapOf(
        "d" to d, "transform" to "translate(${svgNumber(x + anchorOffset)} ${svgNumber(y)})",
    ))
}

private class NativeTextLayout(private val width: Float, private val height: Float) {
    private var x = 0f
    private var y = 0f
    private var chunkStart = 0f
    private var anchor = "start"
    private val chunk = mutableListOf<TextVectorPath>()
    private var previousSpace = true
    private var glyphCount = 0
    private var vectorCharacters = 0

    fun render(element: NativeSvgElement, style: NativeSvgStyle): String {
        trimTrailingSpace(element)
        val group = visit(element, style, alreadyStyled = true)
        finishChunk()
        return group.markup()
    }

    private fun trimTrailingSpace(element: NativeSvgElement): Boolean {
        for (child in element.children.asReversed()) when (child) {
            is NativeSvgText -> {
                child.value = child.value.trimEnd(' ', '\t', '\r', '\n')
                if (child.value.isNotEmpty()) return true
            }
            is NativeSvgElement -> if (trimTrailingSpace(child)) return true
        }
        return false
    }

    private fun visit(element: NativeSvgElement, inherited: NativeSvgStyle, alreadyStyled: Boolean = false): TextVectorGroup {
        require(element.name == "text" || element.name == "tspan") { "Unsupported SVG text content." }
        val style = if (alreadyStyled) inherited else inherited.child(element)
        require(element.attributes["textLength"] == null &&
            (style["text-decoration"] == null || style["text-decoration"] == "none")) { "Unsupported SVG text layout." }
        val newAnchor = style["text-anchor"] ?: "start"
        require(newAnchor in setOf("start", "middle", "end")) { "Unsupported SVG text anchor." }
        val absolute = "x" in element.attributes || "y" in element.attributes
        if (absolute || newAnchor != anchor) finishChunk()
        element.attributes["x"]?.let { x = svgLength(it, style.fontSize, width) }
        element.attributes["y"]?.let { y = svgLength(it, style.fontSize, height) }
        element.attributes["dx"]?.let { x += svgLength(it, style.fontSize, width) }
        element.attributes["dy"]?.let { y += svgLength(it, style.fontSize, height) }
        if (chunk.isEmpty()) { chunkStart = x; anchor = newAnchor }
        val group = TextVectorGroup(element.attributes.filterKeys { it !in textPositionAttributes })
        for (child in element.children) when (child) {
            is NativeSvgElement -> group.children += visit(child, style)
            is NativeSvgText -> {
                val text = buildString {
                    for (character in child.value) {
                        val space = character in " \t\r\n"
                        if (!space || !previousSpace) append(if (space) ' ' else character)
                        previousSpace = space
                    }
                }
                if (text.isEmpty()) continue
                val shaped = shapeNativeText(text, style)
                glyphCount += shaped.glyphs
                vectorCharacters += shaped.outline.length
                require(glyphCount <= 100_000 && vectorCharacters <= MAX_NATIVE_VECTOR_CHARACTERS) { "The SVG text is too complex." }
                val path = TextVectorPath(shaped.outline, x, y + baselineOffset(style, shaped.metrics))
                group.children += path
                chunk += path
                x += shaped.width
            }
        }
        return group
    }

    private fun finishChunk() {
        val advance = x - chunkStart
        val offset = when (anchor) { "middle" -> -advance / 2; "end" -> -advance; else -> 0f }
        chunk.forEach { it.anchorOffset = offset }
        chunk.clear()
        chunkStart = x
    }
}

private data class ShapedNativeText(val outline: String, val width: Float, val glyphs: Int, val metrics: FontMetrics)

private fun shapeNativeText(text: String, style: NativeSvgStyle): ShapedNativeText {
    require(text.length <= 100_000) { "The SVG text is too complex." }
    val weight = when (val value = style["font-weight"]) {
        null, "normal" -> 400
        "bold", "bolder" -> 700
        "lighter" -> 300
        else -> value.toIntOrNull()?.takeIf { it in 1..1000 } ?: error("Unsupported SVG font weight.")
    }
    val slant = when (style["font-style"]) {
        null, "normal" -> FontSlant.UPRIGHT
        "italic" -> FontSlant.ITALIC
        "oblique" -> FontSlant.OBLIQUE
        else -> error("Unsupported SVG font style.")
    }
    val nativeFamily = UIFont.systemFontOfSize(style.fontSize.toDouble()).familyName
    val families = (style["font-family"] ?: "sans-serif").split(',').map { raw ->
        when (val family = raw.trim().trim('\'', '"')) {
            "sans-serif", "system-ui" -> nativeFamily
            "serif" -> "Times New Roman"
            "monospace" -> "Menlo"
            else -> family
        }
    }.toTypedArray<String?>()
    val face = FontMgr.default.matchFamiliesStyle(families, FontStyle(weight, FontWidth.NORMAL, slant))
        ?: FontMgr.default.matchFamilyStyle(nativeFamily, FontStyle(weight, FontWidth.NORMAL, slant))
        ?: error("The SVG font is unavailable.")
    return face.use {
        Font(face, style.fontSize).use { font ->
            Shaper.make(FontMgr.default).use { shaper ->
                shaper.shapeLine(text, font, ShapingOptions.DEFAULT.withApproximateSpaces(false)
                    .withApproximatePunctuation(false)).use { line ->
                    val blob = line.textBlob
                    val outline = if (blob == null) "" else blob.use { outlineShapedBlob(it, style.fontSize) }
                    require(blob != null || text.all(Char::isWhitespace)) { "The SVG text could not be shaped." }
                    ShapedNativeText(outline, line.width, line.glyphs.size, font.metrics)
                }
            }
        }
    }
}

/** Run typefaces and positioned glyphs come from the native shaper, including fallback fonts. */
private fun outlineShapedBlob(blob: TextBlob, size: Float): String = buildString {
    val positions = blob.positions
    var index = 0
    TextBlob.TextBlobIter(blob).use { runs ->
        while (runs.hasNext()) {
            val run = runs.next()
            run.typeface.use { face ->
                Font(face, size).use { font ->
                    val bounds = font.getBounds(run.glyphs)
                    for ((inRun, glyph) in run.glyphs.withIndex()) {
                        require(index * 2 + 1 < positions.size) { "Invalid SVG glyph positions." }
                        val dx = positions[index * 2]
                        val dy = positions[index * 2 + 1]
                        val path = font.getPath(glyph)
                        if (path == null) {
                            require(bounds[inRun].isEmpty) { "The SVG label needs an unsupported bitmap glyph." }
                        } else path.use { appendGlyphPath(it, dx, dy) }
                        index++
                    }
                }
            }
        }
    }
    require(index * 2 == positions.size) { "Invalid SVG glyph positions." }
}

private fun StringBuilder.appendGlyphPath(path: Path, dx: Float, dy: Float) {
    fun point(p: Point) { append(svgNumber(p.x + dx)).append(' ').append(svgNumber(p.y + dy)).append(' ') }
    path.iterator().use { segments ->
        while (segments.hasNext()) {
            val segment = requireNotNull(segments.next())
            when (segment.verb) {
                PathVerb.MOVE -> { append('M'); point(requireNotNull(segment.p0)) }
                PathVerb.LINE -> { append('L'); point(requireNotNull(segment.p1)) }
                PathVerb.QUAD -> { append('Q'); point(requireNotNull(segment.p1)); point(requireNotNull(segment.p2)) }
                PathVerb.CUBIC -> { append('C'); point(requireNotNull(segment.p1)); point(requireNotNull(segment.p2)); point(requireNotNull(segment.p3)) }
                PathVerb.CONIC -> {
                    val quads = Path.convertConicToQuads(requireNotNull(segment.p0), requireNotNull(segment.p1),
                        requireNotNull(segment.p2), segment.conicWeight, 5)
                    for (i in 1 until quads.size step 2) { append('Q'); point(quads[i]); point(quads[i + 1]) }
                }
                PathVerb.CLOSE -> append('Z')
                PathVerb.DONE -> Unit
            }
            require(length <= MAX_NATIVE_VECTOR_CHARACTERS) { "The SVG text is too complex." }
        }
    }
}

private fun baselineOffset(style: NativeSvgStyle, metrics: FontMetrics): Float {
    val alignment = style["alignment-baseline"].takeUnless { it == "auto" || it == "baseline" }
        ?: style["dominant-baseline"]
    return when (alignment) {
        null, "auto", "alphabetic", "baseline" -> 0f
        "middle", "mathematical" -> kotlin.math.abs(metrics.xHeight) / 2
        "central" -> -(metrics.ascent + metrics.descent) / 2
        "hanging" -> -metrics.ascent * 0.8f
        "text-before-edge", "before-edge" -> -metrics.ascent
        "text-after-edge", "after-edge", "ideographic" -> -metrics.descent
        else -> error("Unsupported SVG text baseline.")
    }
}

private val textPositionAttributes = setOf("x", "y", "dx", "dy", "textLength", "lengthAdjust")
