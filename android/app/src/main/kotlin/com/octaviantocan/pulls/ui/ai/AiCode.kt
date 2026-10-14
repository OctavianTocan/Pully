package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.Tok
import com.octaviantocan.pulls.ui.review.hexColor
import com.octaviantocan.pulls.ui.review.pinchToZoomCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Code and flows in what the AI writes (rule 95). Code is the diff's code view: the diff's
 * highlighting, its size setting, a sideways scroll and the same pinch to zoom. A flow is
 * drawn: each part a box, each hop an arrow with what moves beside it.
 */

/** The file extension the highlighter knows a fence language by, or null for a language it does not colour. */
internal fun fenceExtension(lang: String): String? = when (lang.lowercase()) {
    "ts", "typescript" -> "ts"
    "tsx" -> "tsx"
    "js", "javascript", "mjs", "cjs" -> "js"
    "jsx" -> "jsx"
    "json", "jsonc" -> "json"
    "css" -> "css"
    "scss" -> "scss"
    "html" -> "html"
    "vue" -> "vue"
    "md", "markdown" -> "md"
    "py", "python" -> "py"
    "kt", "kts", "kotlin" -> "kt"
    "go", "golang" -> "go"
    "rs", "rust" -> "rs"
    "sh", "bash", "shell", "zsh", "console" -> "sh"
    "yml", "yaml" -> "yml"
    "java" -> "java"
    "xml" -> "xml"
    else -> null
}

/** Highlighted lines per theme, language and code, shared by every screen that shows AI code. */
private val codeTokens = mutableStateMapOf<String, List<List<Tok>>>()
private const val CODE_TOKENS_KEPT = 64

/** The diff's code style at [zoom]: 12.5 on 20, scaled. */
private fun codeTextStyle(zoom: Float): TextStyle = TextStyle(
    fontFamily = PullsType.mono, fontSize = (12.5f * zoom).sp, lineHeight = (20f * zoom).sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/**
 * Code the AI wrote, as the diff shows code: coloured by the diff's highlighter when [lang] is one
 * it knows, at the diff's code size, scrolling sideways and zoomed with two fingers like the diff.
 */
@Composable
internal fun AiCodeView(session: ReviewSession, code: String, lang: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val ext = fenceExtension(lang)
    val key = "${c.dark}|$ext|$code"
    LaunchedEffect(key) {
        if (ext == null || codeTokens.containsKey(key)) return@LaunchedEffect
        val lines = code.lines()
        val result = runCatching { session.repo.highlight("snippet.$ext", lines, c.dark) }.getOrNull() ?: return@LaunchedEffect
        val colored = (result["lines"] as? JsonArray) ?: return@LaunchedEffect
        // A bound on what a long session keeps: past it the cache starts again, and what is on screen is coloured anew.
        if (codeTokens.size >= CODE_TOKENS_KEPT) codeTokens.clear()
        codeTokens[key] = lines.indices.map { i ->
            (colored.getOrNull(i) as? JsonArray).orEmpty().mapNotNull { span ->
                val o = span as? JsonObject ?: return@mapNotNull null
                Tok((o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["color"] as? JsonPrimitive)?.contentOrNull?.let(::hexColor))
            }
        }
    }
    val tokens = codeTokens[key]
    val text = remember(code, tokens) { highlighted(code, tokens) }
    val zoom = session.settings.codeZoom
    val shape = RoundedCornerShape(PullsRadius.xl)
    // The outline keeps the well apart from a sheet, which is the same paper as the well.
    Box(
        modifier.fillMaxWidth().clip(shape).background(c.paper).border(1.dp, c.border, shape)
            .pinchToZoomCode(session.settings).horizontalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Text(text, style = codeTextStyle(zoom).copy(color = c.text), softWrap = false)
    }
}

private fun highlighted(code: String, tokens: List<List<Tok>>?): AnnotatedString = buildAnnotatedString {
    val lines = code.lines()
    lines.forEachIndexed { i, line ->
        val toks = tokens?.getOrNull(i)
        if (toks.isNullOrEmpty()) append(line)
        else toks.forEach { tok -> if (tok.color == null) append(tok.text) else withStyle(SpanStyle(color = tok.color)) { append(tok.text) } }
        if (i < lines.lastIndex) append('\n')
    }
}

/** One hop of a flow: [from] hands [label] (may be empty) to [to]. */
internal data class FlowHop(val from: String, val to: String, val label: String)

/** `->`, `-->`, `=>`, `→`, `⟶`, `─▶` and the like, with the spaces around them. */
private val ARROW = Regex("""\s*(?:-{1,3}>|={1,2}>|→|⟶|➜|─+▶|─+>|-+▶)\s*""")
/** `A --what moves--> B`: a label written on the arrow. */
private val LABELLED_ARROW = Regex("""\s*-{2,}\s*([^->][^>]*?)\s*-{2,}>\s*""")

/**
 * The hops of a ```flow sketch, one or more per line (`A -> B: what moves`, `A -> B -> C`, `A --what--> B`),
 * or null when a line is not one, so the sketch is shown as text instead of a drawing that loses part of it.
 */
internal fun parseFlow(text: String): List<FlowHop>? {
    val hops = mutableListOf<FlowHop>()
    for (raw in text.lines()) {
        val line = raw.trim().removePrefix("- ").trim()
        if (line.isEmpty()) continue
        val marked = LABELLED_ARROW.replace(line) { m -> " ->[${m.groupValues[1]}] " }
        val parts = marked.split(ARROW)
        if (parts.size < 2) return null
        // Each part after the first may open with the label written on the arrow into it.
        val labels = MutableList(parts.size) { "" }
        val names = parts.mapIndexed { i, part ->
            var name = part.trim()
            if (i > 0 && name.startsWith("[")) {
                val end = name.indexOf(']')
                if (end > 0) { labels[i] = name.substring(1, end).trim(); name = name.substring(end + 1).trim() }
            }
            name
        }.toMutableList()
        // `B: what moves` on the last part labels the hop into it.
        val last = names.last()
        val colon = last.indexOf(": ")
        if (colon > 0) { labels[names.lastIndex] = last.substring(colon + 2).trim(); names[names.lastIndex] = last.substring(0, colon).trim() }
        if (names.any { it.isEmpty() || it.length > 60 }) return null
        for (i in 0 until names.lastIndex) hops += FlowHop(names[i], names[i + 1], labels[i + 1])
    }
    return hops.takeIf { it.isNotEmpty() }
}

/** The hops as runs a reader follows down the page: a run goes on while each hop starts where the last one ended. */
internal fun flowRuns(hops: List<FlowHop>): List<List<FlowHop>> {
    val runs = mutableListOf<MutableList<FlowHop>>()
    for (hop in hops) {
        val run = runs.lastOrNull()
        if (run != null && run.last().to == hop.from) run += hop else runs += mutableListOf(hop)
    }
    return runs
}

/** A flow drawn from its hops: each part a box, each hop an arrow down with what moves beside it. */
@Composable
internal fun FlowView(hops: List<FlowHop>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        flowRuns(hops).forEach { run ->
            Column(Modifier.fillMaxWidth()) {
                FlowNode(run.first().from)
                run.forEach { hop ->
                    FlowArrow(hop.label)
                    FlowNode(hop.to)
                }
            }
        }
    }
}

@Composable
private fun FlowNode(name: String) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(PullsRadius.lg)
    Box(Modifier.clip(shape).background(c.raised).border(1.dp, c.border, shape).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(name, style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text)
    }
}

/** The way down from one part to the next: a line ending in an arrowhead, 20dp in, and the label to its right. */
@Composable
private fun FlowArrow(label: String) {
    val c = LocalPullsColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(40.dp).fillMaxHeight().padding(vertical = 4.dp)) {
            val x = 20.dp.toPx()
            val head = 5.dp.toPx()
            val stroke = 1.5.dp.toPx()
            drawLine(c.muted, Offset(x, 0f), Offset(x, size.height - stroke), stroke, StrokeCap.Round)
            val tip = Path().apply {
                moveTo(x - head, size.height - head - stroke)
                lineTo(x, size.height - stroke)
                lineTo(x + head, size.height - head - stroke)
            }
            drawPath(tip, c.muted, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (label.isNotBlank()) Text(label, Modifier.padding(vertical = 4.dp), style = PullsType.sm, color = c.muted)
    }
}
