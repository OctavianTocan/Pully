package com.octaviantocan.pulls.ui.ai

import org.jetbrains.skia.Path
import org.jetbrains.skia.PathSegment
import org.jetbrains.skia.PathVerb
import org.jetbrains.skia.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class NativeSvgMarkerDefinition(val element: NativeSvgElement, val style: NativeSvgStyle, val body: String)

internal fun nativeSvgMarkers(
    element: NativeSvgElement,
    style: NativeSvgStyle,
    width: Float,
    height: Float,
    clipId: () -> String,
    definition: (String) -> NativeSvgMarkerDefinition,
): String {
    fun coordinate(key: String, reference: Float): String = svgNumber(
        svgLength(element.attributes[key] ?: "0", style.fontSize, reference),
    )
    val data = when (element.name) {
        "path" -> element.attributes["d"] ?: ""
        "line" -> "M${coordinate("x1", width)} ${coordinate("y1", height)} " +
            "L${coordinate("x2", width)} ${coordinate("y2", height)}"
        "polyline", "polygon" -> {
            val numbers = svgNumbers(element.attributes["points"] ?: "")
            require(numbers.size >= 2 && numbers.size % 2 == 0) { "Invalid SVG marker path." }
            numbers.chunked(2).mapIndexed { index, pair -> "${if (index == 0) 'M' else 'L'}${pair[0]} ${pair[1]}" }
                .joinToString(" ") + if (element.name == "polygon") " Z" else ""
        }
        else -> error("Unsupported SVG marker path.")
    }
    return Path.makeFromSVGString(data).use { path ->
        val contour = markerContour(path)
        buildString {
            fun marker(key: String, point: Point, angle: Float) {
                val reference = style[key]?.takeUnless { it == "none" } ?: return
                val id = reference.removePrefix("url(#").removeSuffix(")")
                append(placeMarker(definition(id), style, width, height, point, angle, clipId()))
                require(length <= MAX_NATIVE_VECTOR_CHARACTERS) { "The SVG markers are too complex." }
            }
            marker("marker-start", contour.start, contour.forward(0))
            for (i in 0 until contour.edges.lastIndex) {
                var incoming = contour.backward(i)
                var outgoing = contour.forward(i + 1)
                if (abs(incoming - outgoing) > 180f) {
                    if (incoming < outgoing) incoming += 360f else outgoing += 360f
                }
                marker("marker-mid", contour.edges[i].end, (incoming + outgoing) / 2)
            }
            marker("marker-end", contour.edges.lastOrNull()?.end ?: contour.start,
                contour.backward(contour.edges.lastIndex))
        }
    }
}

private data class MarkerEdge(val end: Point, val outgoing: Point, val incoming: Point)
private data class MarkerContour(val start: Point, val edges: List<MarkerEdge>) {
    private val forwardAngles = FloatArray(edges.size)
    private val backwardAngles = FloatArray(edges.size)
    init {
        var angle = 0f
        for (i in edges.indices.reversed()) {
            if (!edges[i].outgoing.zero) angle = edges[i].outgoing.angle
            forwardAngles[i] = angle
        }
        angle = 0f
        for (i in edges.indices) {
            if (!edges[i].incoming.zero) angle = edges[i].incoming.angle
            backwardAngles[i] = angle
        }
    }
    fun forward(index: Int): Float = forwardAngles.getOrNull(index) ?: 0f
    fun backward(index: Int): Float = backwardAngles.getOrNull(index) ?: 0f
}

private fun markerContour(path: Path): MarkerContour {
    var start: Point? = null
    val edges = mutableListOf<MarkerEdge>()
    path.iterator().use { segments ->
        while (segments.hasNext()) {
            val segment = requireNotNull(segments.next())
            when (segment.verb) {
                PathVerb.MOVE -> {
                    // The server emits one subpath per marked edge. Reject rather than
                    // invent start/end ownership for a different document dialect.
                    require(start == null) { "Unsupported multi-subpath SVG marker." }
                    start = requireNotNull(segment.p0)
                }
                PathVerb.LINE, PathVerb.QUAD, PathVerb.CONIC, PathVerb.CUBIC -> edges += markerEdge(segment)
                PathVerb.CLOSE, PathVerb.DONE -> Unit
            }
            require(edges.size <= 100_000) { "The SVG marker path is too complex." }
        }
    }
    return MarkerContour(requireNotNull(start) { "Invalid SVG marker path." }, edges)
}

private fun markerEdge(segment: PathSegment): MarkerEdge {
    val start = requireNotNull(segment.p0)
    val controls = listOfNotNull(segment.p1, segment.p2, segment.p3)
    val end = controls.last()
    val forward = controls.firstOrNull { it != start }?.minus(start) ?: Point(0f, 0f)
    val backward = (listOf(start) + controls.dropLast(1)).lastOrNull { it != end }?.let { end - it } ?: Point(0f, 0f)
    return MarkerEdge(end, forward, backward)
}

private operator fun Point.minus(other: Point): Point = Point(x - other.x, y - other.y)
private val Point.zero: Boolean get() = x == 0f && y == 0f
private val Point.angle: Float get() = (atan2(y.toDouble(), x.toDouble()) * 180 / PI).toFloat()

private fun placeMarker(
    definition: NativeSvgMarkerDefinition,
    style: NativeSvgStyle,
    viewportWidth: Float,
    viewportHeight: Float,
    point: Point,
    pathAngle: Float,
    clipId: String,
): String {
    val attrs = definition.element.attributes
    val fontSize = definition.style.fontSize
    val width = svgLength(attrs["markerWidth"] ?: "3", fontSize, viewportWidth)
    val height = svgLength(attrs["markerHeight"] ?: "3", fontSize, viewportHeight)
    require(width >= 0 && height >= 0) { "Invalid SVG marker size." }
    if (width == 0f || height == 0f) return ""
    val scale = when (attrs["markerUnits"]) {
        null, "strokeWidth" -> svgLength(style["stroke-width"] ?: "1", style.fontSize,
            sqrt((viewportWidth * viewportWidth + viewportHeight * viewportHeight) / 2))
        "userSpaceOnUse" -> 1f
        else -> error("Unsupported SVG marker units.")
    }
    require(scale >= 0) { "Invalid SVG marker scale." }
    if (scale == 0f) return ""
    val angle = when (val orient = attrs["orient"] ?: "0") {
        // The Android compatibility boundary treats auto-start-reverse as auto;
        // the server already reverses its start marker's own geometry.
        "auto", "auto-start-reverse" -> pathAngle
        else -> svgAngle(orient)
    }
    val box = attrs["viewBox"]?.let(::svgNumbers)
    require(box == null || (box.size == 4 && box[2] > 0 && box[3] > 0)) { "Invalid SVG marker viewBox." }
    val refX = svgLength(attrs["refX"] ?: "0", fontSize, box?.get(2) ?: width)
    val refY = svgLength(attrs["refY"] ?: "0", fontSize, box?.get(3) ?: height)
    val fit = box?.let { viewBoxFit(it, width, height, attrs["preserveAspectRatio"] ?: "xMidYMid meet") }
        ?: floatArrayOf(1f, 1f, 0f, 0f)
    val viewportRefX = (refX - (box?.get(0) ?: 0f)) * fit[0] + fit[2]
    val viewportRefY = (refY - (box?.get(1) ?: 0f)) * fit[1] + fit[3]
    val clip = svgElement("defs", emptyMap(), svgElement("clipPath", mapOf("id" to clipId), svgElement("rect", mapOf(
        "x" to svgNumber(-viewportRefX), "y" to svgNumber(-viewportRefY),
        "width" to svgNumber(width), "height" to svgNumber(height),
    ))))
    val content = svgElement("g", mapOf("transform" to
        "translate(${svgNumber(-refX * fit[0])} ${svgNumber(-refY * fit[1])}) scale(${svgNumber(fit[0])} ${svgNumber(fit[1])})"), definition.body)
    val viewport = clip + svgElement("g", mapOf("clip-path" to "url(#$clipId)"), content)
    return svgElement("g", mapOf("transform" to
        "translate(${svgNumber(point.x)} ${svgNumber(point.y)}) rotate(${svgNumber(angle)}) scale(${svgNumber(scale)})"), viewport)
}

private fun svgAngle(value: String): Float {
    val (number, factor) = when {
        value.endsWith("deg") -> value.dropLast(3) to 1f
        value.endsWith("grad") -> value.dropLast(4) to 0.9f
        value.endsWith("rad") -> value.dropLast(3) to (180 / PI).toFloat()
        else -> value to 1f
    }
    return ((number.toFloatOrNull() ?: error("Unsupported SVG marker orientation.")) * factor).also {
        require(it.isFinite()) { "Invalid SVG marker orientation." }
    }
}

private fun viewBoxFit(box: List<Float>, width: Float, height: Float, aspect: String): FloatArray {
    val parts = aspect.trim().split(Regex("\\s+"))
    val alignment = parts.first()
    val sx = width / box[2]
    val sy = height / box[3]
    if (alignment == "none") return floatArrayOf(sx, sy, 0f, 0f)
    require(alignment in setOf("xMinYMin", "xMidYMin", "xMaxYMin", "xMinYMid", "xMidYMid", "xMaxYMid",
        "xMinYMax", "xMidYMax", "xMaxYMax") && parts.size <= 2) { "Unsupported SVG marker aspect ratio." }
    val fit = parts.getOrNull(1) ?: "meet"
    require(fit == "meet" || fit == "slice") { "Unsupported SVG marker aspect ratio." }
    val scale = if (fit == "slice") max(sx, sy) else min(sx, sy)
    val x = (width - box[2] * scale) * when { alignment.startsWith("xMax") -> 1f; alignment.startsWith("xMid") -> 0.5f; else -> 0f }
    val y = (height - box[3] * scale) * when { alignment.endsWith("YMax") -> 1f; alignment.endsWith("YMid") -> 0.5f; else -> 0f }
    return floatArrayOf(scale, scale, x, y)
}
