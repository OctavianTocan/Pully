package com.octaviantocan.pulls.core.diagram

import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * The colours a Pele diagram is drawn in, as `#rrggbb`: [bg] behind edge labels, [fg] for text, [line] for edges,
 * [surface] and [surfaceAlt] for nodes and groups, eight [series] colours for charts. Pele's own `--pele-*` theme.
 */
data class DiagramPalette(
    val bg: String,
    val fg: String,
    val muted: String,
    val line: String,
    val surface: String,
    val surfaceAlt: String,
    val border: String,
    val accent: String,
    val critical: String,
    val series: List<String>,
)

/**
 * The script that renders [source] with the bundled Pele (`pele.global.js`, already evaluated in the same engine) at
 * most [maxWidth] pixels wide. It evaluates to a JSON object: `{"svg": …}`, or `{"error": …}` when Pele can't draw it.
 * The diagram text goes in as a JSON string literal, never as code, and links and images stay off.
 */
fun peleRenderScript(source: String, maxWidth: Int): String {
    val text = JsonPrimitive(source).toString()
    return "(function(){try{var r=pele.render($text,{maxWidth:$maxWidth,links:false,images:false,idPrefix:'pully'});" +
        "return JSON.stringify({svg:r.svg})}catch(e){return JSON.stringify({error:String(e&&e.message||e)})}})()"
}

/** The longest diagram text handed to Pele, as on GitHub; a longer one stays code. */
const val MAX_DIAGRAM_SOURCE = 50_000

private const val MAX_SVG = 4 * 1024 * 1024

private val DRAWN = setOf("svg", "g", "path", "rect", "circle", "ellipse", "polygon", "polyline", "line", "text", "tspan")
private val TRANSPARENT = setOf("a")
// The attributes both hosts read (the iOS adapter refuses any other).
private val KEPT = setOf(
    "fill", "stroke", "fill-opacity", "stroke-opacity", "stroke-width", "stroke-dasharray",
    "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "fill-rule", "font-family", "font-size", "font-weight",
    "font-style", "text-anchor", "text-decoration", "width", "height", "viewBox", "x", "y", "x1", "x2", "y1", "y2",
    "cx", "cy", "r", "rx", "ry", "dx", "dy", "d", "points", "transform", "opacity",
)
private val LENGTHS = setOf("rx", "ry", "stroke-width", "font-size", "width", "height", "x", "y", "r")

/**
 * [svg] from Pele as inert vector data the app's diagram view draws on both hosts: shapes, text and their paint, with
 * every CSS variable, `color-mix()` and `currentColor` resolved against [palette], styles written out as attributes,
 * and nothing else. Links become plain groups; pictures, gradients, embedded HTML, ids, classes and accessibility
 * attributes drop away. Fails on a document that is not Pele's well-formed SVG or is too large.
 */
fun inertPeleSvg(svg: String, palette: DiagramPalette): String {
    require(svg.length <= MAX_SVG) { "The diagram is too large." }
    val root = readSvg(svg)
    require(root.name == "svg") { "Not an SVG document." }
    val vars = buildMap {
        put("--pele-bg", palette.bg); put("--pele-fg", palette.fg); put("--pele-muted", palette.muted)
        put("--pele-line", palette.line); put("--pele-surface", palette.surface); put("--pele-surface-alt", palette.surfaceAlt)
        put("--pele-border", palette.border); put("--pele-accent", palette.accent); put("--pele-critical", palette.critical)
        put("--pele-fg-on-light", "#1f1f1f"); put("--pele-fg-on-dark", "#ffffff")
        palette.series.forEachIndexed { i, color -> put("--pele-series-${i + 1}", color) }
    }
    val out = StringBuilder()
    write(root, Scope(vars, palette.fg, 16f), out, root = true)
    require(out.length <= MAX_SVG) { "The diagram is too large." }
    return out.toString()
}

private class Scope(val vars: Map<String, String>, val color: String, val fontSize: Float)

private sealed interface Part
private class Text(val value: String) : Part
private class Element(val name: String, val attrs: Map<String, String>, val children: MutableList<Part> = mutableListOf()) : Part

private fun write(element: Element, parent: Scope, out: StringBuilder, root: Boolean = false) {
    val name = when (element.name) {
        in DRAWN -> element.name
        in TRANSPARENT -> "g"
        else -> return
    }
    // Presentation attributes first, then the style attribute over them, as CSS orders them.
    val declared = LinkedHashMap<String, String>()
    for ((key, value) in element.attrs) if (key != "style") declared[key] = value
    element.attrs["style"]?.let { style ->
        for (part in style.split(';')) {
            val at = part.indexOf(':')
            if (at <= 0) continue
            val key = part.substring(0, at).trim().lowercase()
            val value = part.substring(at + 1).replace("!important", "").trim()
            // `max-width:100%;height:auto` lets a page shrink the picture; the attributes keep its own size.
            if (key in LENGTHS && (value == "auto" || value.endsWith("%"))) continue
            declared[key] = value
        }
    }
    val vars = parent.vars.toMutableMap()
    for ((key, value) in declared) if (key.startsWith("--")) vars[key] = resolve(value, vars, parent.color)
    val color = declared["color"]?.let { resolve(it, vars, parent.color) }?.takeIf { it.isNotEmpty() } ?: parent.color
    val font = declared["font"]?.let { FONT.find(resolve(it, vars, color)) }
    if (font != null) {
        if ("font-size" !in declared) declared["font-size"] = font.groupValues[1]
        if ("font-family" !in declared) declared["font-family"] = font.groupValues[2]
    }
    val attrs = LinkedHashMap<String, String>()
    var fontSize = parent.fontSize
    for ((key, raw) in declared) {
        if (key !in KEPT) continue
        var value = resolve(raw, vars, color)
        if (value.isEmpty()) continue
        if (key == "fill" || key == "stroke") value = paint(value)
        if (key == "font-family") value = family(value)
        if (key == "font-size") {
            fontSize = when (value) {
                "smaller" -> parent.fontSize / 1.2f
                "larger" -> parent.fontSize * 1.2f
                else -> length(value, parent.fontSize) ?: continue
            }
            value = number(fontSize)
        } else if (key in LENGTHS && !value.contains(' ')) {
            value = length(value, fontSize)?.let(::number) ?: continue
        }
        attrs[key] = value
    }
    out.append('<').append(name)
    if (root) out.append(" xmlns=\"http://www.w3.org/2000/svg\"")
    for ((key, value) in attrs) out.append(' ').append(key).append("=\"").append(escape(value)).append('"')
    out.append('>')
    val scope = Scope(vars, color, fontSize)
    for (child in element.children) when (child) {
        is Text -> if (name == "text" || name == "tspan") out.append(escape(child.value))
        is Element -> write(child, scope, out)
    }
    out.append("</").append(name).append('>')
}

private val FONT = Regex("""([\d.]+)px\s+(.+)""")
private val VAR = Regex("""var\(\s*(--[\w-]+)\s*(?:,([^()]*(?:\([^()]*\)[^()]*)*))?\)""")
private val MIX = Regex("""color-mix\(\s*in\s+srgb\s*,\s*(#[0-9a-fA-F]{3,8})\s+([\d.]+)%\s*,\s*(#[0-9a-fA-F]{3,8})\s*\)""")

/** [value] with its `var()` references, `color-mix()` and `currentColor` worked out. */
private fun resolve(value: String, vars: Map<String, String>, color: String): String {
    var text = value
    repeat(8) {
        if (!text.contains("var(")) return@repeat
        text = VAR.replace(text) { m -> vars[m.groupValues[1]] ?: m.groupValues[2].trim() }
    }
    text = MIX.replace(text) { m -> mix(m.groupValues[1], m.groupValues[2].toFloat() / 100f, m.groupValues[3]) }
    text = text.replace("currentColor", color, ignoreCase = true)
    return if (text.contains("var(") || text.contains("color-mix(")) "" else text.trim()
}

/** A paint as a colour alone: a gradient's `url(#…)` gives way to the colour written after it. */
private fun paint(value: String): String =
    if (value.startsWith("url(")) value.substringAfter(')').trim().ifEmpty { "none" } else value

/** The first font named, unquoted; both hosts draw the generic families in the system's own fonts. */
private fun family(value: String): String {
    val first = value.split(',').first().trim().trim('"', '\'')
    return if (first.contains("mono", ignoreCase = true)) "monospace" else "sans-serif"
}

private fun length(value: String, fontSize: Float): Float? {
    val text = value.trim()
    val number = when {
        text.endsWith("px") -> text.dropLast(2).toFloatOrNull()
        text.endsWith("em") -> text.dropLast(2).toFloatOrNull()?.times(fontSize)
        else -> text.toFloatOrNull()
    }
    return number?.takeIf { it.isFinite() }
}

private fun number(value: Float): String {
    val hundredths = (value * 100).roundToInt()
    return if (hundredths % 100 == 0) (hundredths / 100).toString() else (hundredths / 100.0).toString()
}

/** `color-mix(in srgb, a p%, b)` for two hex colours. */
private fun mix(a: String, share: Float, b: String): String {
    val x = rgb(a) ?: return a
    val y = rgb(b) ?: return a
    return "#" + (0 until 3).joinToString("") { i ->
        (x[i] * share + y[i] * (1 - share)).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    }
}

private fun rgb(hex: String): IntArray? {
    val digits = hex.removePrefix("#")
    val full = when (digits.length) {
        3, 4 -> digits.take(3).map { "$it$it" }.joinToString("")
        6, 8 -> digits.take(6)
        else -> return null
    }
    return IntArray(3) { full.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: return null }
}

private fun escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private val TAG = Regex("""<(/?)([a-zA-Z][\w:-]*)((?:\s+[^\s=/>]+\s*=\s*"[^"]*")*)\s*(/?)>""")
private val ATTR = Regex("""([^\s=/>]+)\s*=\s*"([^"]*)"""")
private val ENTITY = Regex("""&(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);""")

/** Pele's SVG as a tree; anything but its own well-formed markup fails. */
private fun readSvg(svg: String): Element {
    val top = Element("#document", emptyMap())
    val stack = ArrayDeque<Element>().apply { addLast(top) }
    var at = 0
    for (match in TAG.findAll(svg)) {
        if (match.range.first > at) text(svg.substring(at, match.range.first), stack.last())
        at = match.range.last + 1
        val name = match.groupValues[2]
        if (match.groupValues[1] == "/") {
            require(stack.size > 1 && stack.last().name == name) { "Invalid SVG document." }
            stack.removeLast()
            continue
        }
        val attrs = ATTR.findAll(match.groupValues[3]).associate { it.groupValues[1] to decode(it.groupValues[2]) }
        val element = Element(name, attrs)
        stack.last().children += element
        if (match.groupValues[4] != "/") stack.addLast(element)
    }
    if (at < svg.length) text(svg.substring(at), stack.last())
    require(stack.size == 1) { "Invalid SVG document." }
    return top.children.filterIsInstance<Element>().singleOrNull() ?: error("Invalid SVG document.")
}

private fun text(raw: String, parent: Element) {
    require('<' !in raw) { "Invalid SVG document." }
    if (parent.name == "#document") { require(raw.isBlank()) { "Invalid SVG document." }; return }
    parent.children += Text(decode(raw))
}

private fun decode(text: String): String = if ('&' !in text) text else ENTITY.replace(text) { m ->
    when (val name = m.groupValues[1]) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
        else -> {
            val code = if (name.startsWith("#x")) name.drop(2).toInt(16) else name.drop(1).toInt()
            if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) codePoint(code) else ""
        }
    }
}

private fun codePoint(code: Int): String = if (code < 0x10000) code.toChar().toString() else {
    val v = code - 0x10000
    charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
}
