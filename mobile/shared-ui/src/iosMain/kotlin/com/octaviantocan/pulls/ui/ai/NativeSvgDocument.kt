@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.ui.ai

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSXMLParser
import platform.Foundation.NSXMLParserDelegateProtocol
import platform.Foundation.create
import platform.darwin.NSObject

private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
private const val MAX_SVG_BYTES = 4 * 1024 * 1024
internal const val MAX_NATIVE_VECTOR_CHARACTERS = 32 * 1024 * 1024

internal sealed interface NativeSvgPart
internal class NativeSvgText(var value: String) : NativeSvgPart
internal class NativeSvgElement(
    val name: String,
    val attributes: Map<String, String>,
    val children: MutableList<NativeSvgPart> = mutableListOf(),
) : NativeSvgPart

/** Skia m150's SVG DOM has neither a font-manager port in Skiko nor marker nodes. */
internal fun nativeDiagramVectors(source: String): String {
    val root = readNativeSvg(source)
    return NativeSvgVectors(root).render()
}

private fun readNativeSvg(source: String): NativeSvgElement {
    require(source.length <= MAX_SVG_BYTES && !source.contains("<!")) { "Unsupported SVG document." }
    val bytes = source.encodeToByteArray()
    require(bytes.isNotEmpty() && bytes.size <= MAX_SVG_BYTES) { "Unsupported SVG document." }
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val reader = NativeSvgReader()
    val parser = NSXMLParser(data).apply {
        delegate = reader
        shouldProcessNamespaces = true
        shouldResolveExternalEntities = false
    }
    require(parser.parse() && !reader.failed && reader.stack.isEmpty()) { "Invalid SVG document." }
    return requireNotNull(reader.root) { "Invalid SVG document." }
}

/** Errors abort the Objective-C callback; no Kotlin exception crosses Foundation. */
private class NativeSvgReader : NSObject(), NSXMLParserDelegateProtocol {
    val stack = mutableListOf<NativeSvgElement>()
    var root: NativeSvgElement? = null
    var failed = false
    private var count = 0
    private var textSize = 0
    private var textElements = 0

    override fun parser(
        parser: NSXMLParser,
        didStartElement: String,
        namespaceURI: String?,
        qualifiedName: String?,
        attributes: Map<Any?, *>,
    ) {
        if (failed) return
        if (namespaceURI != SVG_NAMESPACE || didStartElement !in svgElements ||
            stack.size >= 64 || ++count > 100_000 ||
            (stack.isEmpty() && (root != null || didStartElement != "svg"))) return abort(parser)
        if ((didStartElement == "text" || didStartElement == "tspan") && ++textElements > 16_384) return abort(parser)
        val values = linkedMapOf<String, String>()
        for ((rawKey, rawValue) in attributes) {
            val key = rawKey as? String ?: return abort(parser)
            val value = rawValue as? String ?: return abort(parser)
            if (key == "xmlns" && value == SVG_NAMESPACE) continue
            if (key !in svgAttributes || value.length > MAX_SVG_BYTES ||
                (key.startsWith("marker-") && value != "none" && !localMarker.matches(value)) ||
                (!key.startsWith("marker-") &&
                    (value.contains("url(", ignoreCase = true) || value.contains('@') ||
                        value.contains("var(", ignoreCase = true)))) return abort(parser)
            values[key] = value
        }
        val element = NativeSvgElement(didStartElement, values)
        if (stack.isEmpty()) root = element else stack.last().children += element
        stack += element
    }

    override fun parser(parser: NSXMLParser, didEndElement: String, namespaceURI: String?, qualifiedName: String?) {
        if (!failed && stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    override fun parser(parser: NSXMLParser, foundCharacters: String) {
        if (failed || stack.isEmpty()) return
        textSize += foundCharacters.length
        if (textSize > 100_000) return abort(parser)
        val element = stack.last()
        if (element.name !in setOf("text", "tspan", "title", "desc", "style")) {
            if (foundCharacters.any { !it.isWhitespace() }) abort(parser)
            return
        }
        val previous = element.children.lastOrNull() as? NativeSvgText
        if (previous == null) element.children += NativeSvgText(foundCharacters)
        else previous.value += foundCharacters
    }

    private fun abort(parser: NSXMLParser) {
        failed = true
        parser.abortParsing()
    }
}

internal class NativeSvgStyle private constructor(val values: Map<String, String>, val fontSize: Float) {
    fun child(element: NativeSvgElement): NativeSvgStyle {
        val next = values + element.attributes.filterKeys { it in inheritedSvgAttributes && element.attributes[it] != "inherit" }
        val size = element.attributes["font-size"]?.takeUnless { it == "inherit" }
            ?.let { svgLength(it, fontSize, fontSize) } ?: fontSize
        require(size > 0 && size <= 4096) { "Unsupported SVG font size." }
        return NativeSvgStyle(next + ("font-size" to svgNumber(size)), size)
    }
    operator fun get(key: String): String? = values[key]

    companion object {
        val Default = NativeSvgStyle(mapOf(
            "fill" to "black", "stroke" to "none", "fill-opacity" to "1", "stroke-opacity" to "1",
            "stroke-width" to "1", "stroke-dasharray" to "none", "stroke-linecap" to "butt",
            "stroke-linejoin" to "miter", "stroke-miterlimit" to "4", "fill-rule" to "nonzero",
            "clip-rule" to "nonzero", "font-family" to "sans-serif", "font-size" to "16",
            "font-weight" to "normal", "font-style" to "normal", "text-anchor" to "start",
            "dominant-baseline" to "auto", "alignment-baseline" to "auto", "text-decoration" to "none",
            "marker-start" to "none", "marker-mid" to "none", "marker-end" to "none",
        ), 16f)
    }
}

private class NativeSvgVectors(private val root: NativeSvgElement) {
    private data class Marker(val element: NativeSvgElement, val parent: NativeSvgStyle)
    private val markers = mutableMapOf<String, Marker>()
    private val markerContent = mutableMapOf<String, String>()
    private val activeMarkers = mutableSetOf<String>()
    private val ids = mutableSetOf<String>()
    private var clipSequence = 0
    private val viewport = root.attributes["viewBox"]?.let(::svgNumbers)?.also {
        require(it.size == 4 && it[2] > 0 && it[3] > 0 && it[2] <= 1_000_000 && it[3] <= 1_000_000) { "Invalid SVG viewBox." }
    }
    private val width = viewport?.get(2) ?: 400f
    private val height = viewport?.get(3) ?: 250f

    fun render(): String {
        collectMarkers(root, NativeSvgStyle.Default)
        return renderElement(root, NativeSvgStyle.Default).also {
            require(it.length <= MAX_NATIVE_VECTOR_CHARACTERS) { "The SVG is too complex." }
        }
    }

    private fun collectMarkers(element: NativeSvgElement, parent: NativeSvgStyle) {
        element.attributes["id"]?.let { require(ids.add(it)) { "Duplicate SVG identifier." } }
        if (element.name == "marker") {
            val id = requireNotNull(element.attributes["id"]) { "Invalid SVG marker." }
            require(markers.put(id, Marker(element, parent)) == null) { "Duplicate SVG marker." }
        }
        val style = parent.child(element)
        element.children.filterIsInstance<NativeSvgElement>().forEach { collectMarkers(it, style) }
    }

    private fun renderElement(element: NativeSvgElement, parent: NativeSvgStyle): String {
        if (element.name == "marker" || element.name == "style") return ""
        val style = parent.child(element)
        if (element.name == "text") return outlineNativeSvgText(element, style, width, height)
        require(element.name != "tspan") { "An SVG span needs a text owner." }
        val content = buildString {
            for (child in element.children) {
                append(when (child) {
                    is NativeSvgText -> svgEscape(child.value)
                    is NativeSvgElement -> renderElement(child, style)
                })
                require(length <= MAX_NATIVE_VECTOR_CHARACTERS) { "The SVG is too complex." }
            }
        }
        val attributes = element.attributes.filterKeys { !it.startsWith("marker-") }
        val hasMarkers = element.name in markerShapes && markerAttributes.any { style[it] != "none" && style[it] != null }
        if (!hasMarkers) return svgElement(element.name, attributes, content)
        require(activeMarkers.isEmpty()) { "Unsupported nested SVG markers." }
        val groupKeys = setOf("transform", "opacity", "id")
        val shape = svgElement(element.name, attributes.filterKeys { it !in groupKeys }, content)
        val expanded = nativeSvgMarkers(element, style, width, height, ::nextClipId) { id ->
            val marker = requireNotNull(markers[id]) { "Missing SVG marker." }
            val body = markerContent[id] ?: run {
                require(activeMarkers.add(id)) { "Recursive SVG marker." }
                try {
                    val markerStyle = marker.parent.child(marker.element)
                    val children = marker.element.children.filterIsInstance<NativeSvgElement>()
                        .joinToString("") { renderElement(it, markerStyle) }
                    svgElement("g", markerStyle.values.filterKeys { it !in markerAttributes } +
                        marker.element.attributes.filterKeys { it in setOf("transform", "opacity") }, children)
                        .also { markerContent[id] = it }
                } finally { activeMarkers.remove(id) }
            }
            NativeSvgMarkerDefinition(marker.element, marker.parent.child(marker.element), body)
        }
        return svgElement("g", attributes.filterKeys { it in groupKeys }, shape + expanded)
    }

    private fun nextClipId(): String {
        while (true) {
            val id = "__pully_marker_clip_${clipSequence++}"
            if (ids.add(id)) return id
        }
    }
}

internal fun svgElement(name: String, attributes: Map<String, String>, children: String = ""): String = buildString {
    append('<').append(name)
    if (name == "svg") append(" xmlns=\"").append(SVG_NAMESPACE).append('"')
    for ((key, value) in attributes) append(' ').append(key).append("=\"").append(svgEscape(value)).append('"')
    append('>').append(children).append("</").append(name).append('>')
}

internal fun svgEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

internal fun svgNumber(value: Float): String {
    require(value.isFinite()) { "Invalid SVG number." }
    return value.toString()
}

internal fun svgNumbers(value: String): List<Float> = value.trim().split(Regex("[\\s,]+"))
    .filter(String::isNotEmpty).map { it.toFloatOrNull()?.takeIf(Float::isFinite) ?: error("Invalid SVG number.") }

internal fun svgLength(value: String, fontSize: Float, percentage: Float): Float {
    val text = value.trim()
    val (number, scale) = when {
        text.endsWith("em") -> text.dropLast(2) to fontSize
        text.endsWith("ex") -> text.dropLast(2) to fontSize * 0.5f
        text.endsWith('%') -> text.dropLast(1) to percentage / 100f
        text.endsWith("px") -> text.dropLast(2) to 1f
        else -> text to 1f
    }
    return ((number.toFloatOrNull() ?: error("Unsupported SVG length.")) * scale).also {
        require(it.isFinite() && kotlin.math.abs(it) <= 1_000_000f) { "Invalid SVG length." }
    }
}

internal val markerAttributes = setOf("marker-start", "marker-mid", "marker-end")
private val markerShapes = setOf("path", "line", "polyline", "polygon")
private val localMarker = Regex("url\\(#[A-Za-z0-9_-]+\\)")
private val inheritedSvgAttributes = setOf(
    "fill", "stroke", "fill-opacity", "stroke-opacity", "stroke-width", "stroke-dasharray", "stroke-linecap",
    "stroke-linejoin", "stroke-miterlimit", "fill-rule", "clip-rule", "font-family", "font-size", "font-weight",
    "font-style", "text-anchor", "dominant-baseline", "alignment-baseline", "text-decoration",
) + markerAttributes
private val svgElements = setOf("svg", "g", "defs", "marker", "path", "rect", "circle", "ellipse", "polygon",
    "polyline", "line", "text", "tspan", "title", "desc", "style")
private val svgAttributes = inheritedSvgAttributes + setOf(
    "id", "width", "height", "viewBox", "preserveAspectRatio", "x", "y", "x1", "x2", "y1", "y2",
    "cx", "cy", "r", "rx", "ry", "dx", "dy", "d", "points", "transform", "opacity", "textLength",
    "lengthAdjust", "markerWidth", "markerHeight", "markerUnits", "refX", "refY", "orient",
)
