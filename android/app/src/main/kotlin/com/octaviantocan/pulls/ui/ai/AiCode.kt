package com.octaviantocan.pulls.ui.ai

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.core.PSEUDO_PROBE
import com.octaviantocan.pulls.core.PSEUDO_PROBE_AT
import com.octaviantocan.pulls.core.PseudoKind
import com.octaviantocan.pulls.core.SketchRow
import com.octaviantocan.pulls.core.isFolderRow
import com.octaviantocan.pulls.core.pseudoTokens
import com.octaviantocan.pulls.core.sketchRows
import com.octaviantocan.pulls.core.softBreaks
import com.octaviantocan.pulls.core.treeGuides
import com.octaviantocan.pulls.core.treePath
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.FileIcon
import com.octaviantocan.pulls.ui.design.LocalMentions
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.Tok
import com.octaviantocan.pulls.ui.review.fileIconRes
import com.octaviantocan.pulls.ui.review.hexColor
import com.octaviantocan.pulls.ui.review.pinchToZoomCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Code and sketches in what the AI writes (rule 95). Code is the diff's code view: the diff's
 * highlighting, its size setting, a sideways scroll and the same pinch to zoom; pseudocode too,
 * coloured word by word. A flow is drawn: each part a box, each hop an arrow with what moves
 * beside it. A call tree and a file tree are rows on guide lines.
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

/** Fence languages that mean pseudocode: coloured here, word by word, since no highlighter has a grammar for it. */
private val PSEUDO_LANGS = setOf("pseudo", "pseudocode")

/**
 * Code the AI wrote, as the diff shows code: coloured by the diff's highlighter when [lang] is one
 * it knows, at the diff's code size, scrolling sideways and zoomed with two fingers like the diff.
 * Pseudocode is coloured word by word in the diff's own colours (see [PSEUDO_PROBE]).
 */
@Composable
internal fun AiCodeView(session: ReviewSession, code: String, lang: String, modifier: Modifier = Modifier) {
    if (lang.lowercase() in PSEUDO_LANGS) {
        val colors = rememberPseudoColors(session)
        CodeWell(session, remember(code, colors) { pseudoText(code, colors) }, modifier)
        return
    }
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
        codeTokens[key] = lines.indices.map { i -> spansOf(colored.getOrNull(i)) }
    }
    val tokens = codeTokens[key]
    CodeWell(session, remember(code, tokens) { highlighted(code, tokens) }, modifier)
}

/** One highlighted line from the server: its spans of text and colour. */
private fun spansOf(line: Any?): List<Tok> = (line as? JsonArray).orEmpty().mapNotNull { span ->
    val o = span as? JsonObject ?: return@mapNotNull null
    Tok((o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["color"] as? JsonPrimitive)?.contentOrNull?.let(::hexColor))
}

/** The well code sits in: the diff's code size, a sideways scroll and the diff's pinch to zoom. */
@Composable
private fun CodeWell(session: ReviewSession, text: AnnotatedString, modifier: Modifier) {
    val c = LocalPullsColors.current
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

/** The diff's colour for each kind of pseudocode, per theme (dark or not), once the highlighter has coloured the probe. */
private val pseudoColors = mutableStateMapOf<Boolean, Map<PseudoKind, Color?>>()
/** Themes whose probe is on its way, so a page of pseudocode blocks asks once. */
private val pseudoAsked = mutableSetOf<Boolean>()

/**
 * The colours the diff gives each kind of pseudocode: [PSEUDO_PROBE] coloured by the diff's highlighter, read at each
 * kind's offset. Null until it answers; the code then shows in the text colour, as code the highlighter does not know.
 */
@Composable
private fun rememberPseudoColors(session: ReviewSession): Map<PseudoKind, Color?>? {
    val dark = LocalPullsColors.current.dark
    LaunchedEffect(dark) {
        if (pseudoColors.containsKey(dark) || !pseudoAsked.add(dark)) return@LaunchedEffect
        val result = runCatching { session.repo.highlight("probe.py", listOf(PSEUDO_PROBE), dark) }.getOrNull()
        val spans = spansOf((result?.get("lines") as? JsonArray)?.firstOrNull())
        if (spans.isEmpty()) { pseudoAsked.remove(dark); return@LaunchedEffect }
        // Where each span starts, so a kind's colour is that of the span its offset falls in.
        var at = 0
        val ranges = spans.map { tok -> (at until at + tok.text.length).also { at += tok.text.length } to tok.color }
        pseudoColors[dark] = PSEUDO_PROBE_AT.mapValues { (_, offset) -> ranges.firstOrNull { offset in it.first }?.second }
    }
    return pseudoColors[dark]
}

private fun pseudoText(code: String, colors: Map<PseudoKind, Color?>?): AnnotatedString = buildAnnotatedString {
    val lines = code.lines()
    lines.forEachIndexed { i, line ->
        pseudoTokens(line).forEach { tok ->
            val color = colors?.get(tok.kind)
            if (color == null) append(tok.text) else withStyle(SpanStyle(color = color)) { append(tok.text) }
        }
        if (i < lines.lastIndex) append('\n')
    }
}

/**
 * A fenced block of AI text, by its language (rule 95): a ```flow is drawn as boxes and arrows, a ```calls as a call
 * tree and a ```tree as a file tree, both on guide lines, and anything else, pseudocode included, is the diff's code view.
 */
@Composable
internal fun AiFence(session: ReviewSession, code: String, lang: String) {
    when (lang.lowercase()) {
        "flow" -> {
            val items = remember(code) { parseFlow(code) }
            if (items != null) FlowView(items) else AiCodeView(session, code, "")
        }
        "calls" -> SketchTree(session, code, files = false)
        "tree" -> SketchTree(session, code, files = true)
        else -> AiCodeView(session, code, lang)
    }
}

/** One level of a drawn tree: its guide runs down the middle of this width. */
private val TREE_INDENT = 18.dp
/** Above and below each tree row's text; the rows touch, so their guides join. */
private val TREE_ROW_PAD = 3.dp

/**
 * A ```calls or ```tree sketch drawn as a tree (rule 95): each row at its depth, hung from its parent by a guide line,
 * its name in the code face and the comment beside it in the muted colour. A file tree puts the file's icon in front, as
 * the Files list does, and a row that names a changed file opens it. A sketch with no rows is shown as its text.
 */
@Composable
private fun SketchTree(session: ReviewSession, code: String, files: Boolean) {
    val rows = remember(code) { sketchRows(code) }
    if (rows.isEmpty()) {
        AiCodeView(session, code, "")
        return
    }
    val guides = remember(rows) { treeGuides(rows.map { it.depth }) }
    val mentions = LocalMentions.current
    val targets = remember(rows, files, mentions) {
        rows.indices.map { i -> if (files && mentions != null && !isFolderRow(rows, i)) changedFileOf(treePath(rows, i), mentions.resolve) else null }
    }
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, row ->
            val icon = when {
                !files || row.name.isEmpty() -> null
                isFolderRow(rows, i) -> R.drawable.ic_cat_folder_open
                else -> fileIconRes(row.name)
            }
            val open: (() -> Unit)? = targets[i]?.let { path -> { mentions?.open?.invoke(CodePlace(path, 0)) } }
            TreeRow(row, guides[i], icon, open)
        }
    }
}

/** The changed file a tree row's path names: the whole path, or the end of it when the tree starts above the repository. */
private fun changedFileOf(path: String, resolve: (String) -> String?): String? {
    val parts = path.split('/')
    for (drop in parts.indices) resolve(parts.drop(drop).joinToString("/"))?.let { return it }
    return null
}

/** One row of a drawn tree: the guides of the levels above it, its branch, the icon of a file tree and the name with its comment. */
@Composable
private fun TreeRow(row: SketchRow, guides: List<Boolean>, @DrawableRes icon: Int?, onOpen: (() -> Unit)?) {
    val c = LocalPullsColors.current
    val style = PullsType.sm
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (onOpen != null) Modifier.clip(RoundedCornerShape(PullsRadius.sm)).cueClickable(role = Role.Button, onClickLabel = "Open ${row.name}", onClick = onOpen) else Modifier),
    ) {
        if (guides.isNotEmpty()) Canvas(Modifier.width(TREE_INDENT * guides.size).fillMaxHeight()) {
            val step = TREE_INDENT.toPx()
            val stroke = 1.dp.toPx()
            // The branch meets the row at the middle of its first line.
            val mid = (TREE_ROW_PAD + lineHeight / 2).toPx()
            guides.forEachIndexed { k, down ->
                val x = k * step + step / 2
                if (k < guides.lastIndex) {
                    if (down) drawLine(c.faint, Offset(x, 0f), Offset(x, size.height), stroke)
                } else {
                    drawLine(c.faint, Offset(x, 0f), Offset(x, if (down) size.height else mid), stroke)
                    drawLine(c.faint, Offset(x, mid), Offset(size.width - 4.dp.toPx(), mid), stroke)
                }
            }
        }
        if (icon != null) Box(Modifier.padding(top = TREE_ROW_PAD, end = 6.dp).height(lineHeight), contentAlignment = Alignment.Center) {
            FileIcon(icon, Modifier.alpha(0.65f))
        }
        Text(
            buildAnnotatedString {
                // A long name wraps between its words, never cut (rule 2).
                withStyle(SpanStyle(fontFamily = PullsType.mono, color = if (onOpen != null) c.activeText else c.text)) { append(softBreaks(row.name)) }
                if (row.note.isNotEmpty()) {
                    if (row.name.isNotEmpty()) append("  ")
                    withStyle(SpanStyle(color = c.muted)) { append(row.note) }
                }
            },
            Modifier.weight(1f).padding(vertical = TREE_ROW_PAD),
            style = style, color = c.text,
        )
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

/** What a ```flow sketch draws, in reading order: runs of hops, and any line that is not a hop kept as its words. */
internal sealed interface FlowItem {
    /** Hops a reader follows down the page: each starts where the last one ended. */
    data class Run(val hops: List<FlowHop>) : FlowItem
    data class Note(val text: String) : FlowItem
}

/** `->`, `-->`, `=>`, `→`, `⟶`, `─▶` and the like, with the spaces around them. */
private val ARROW = Regex("""\s*(?:-{1,3}>|={1,2}>|→|⟶|➜|─+▶|─+>|-+▶)\s*""")
/** `A --what moves--> B`: a label written on the arrow. */
private val LABELLED_ARROW = Regex("""\s*-{2,}\s*([^->][^>]*?)\s*-{2,}>\s*""")
/** The longest name a box holds; a longer one is a sentence, not a part. */
private const val FLOW_NAME_MAX = 60

/** The parts one line names in order and the label on the arrow into each; the first part is empty when the line opens with an arrow. */
private class FlowChain(val names: MutableList<String>, val labels: MutableList<String>)

/** A line's parts and labels (`A -> B: what moves`, `A -> B -> C`, `A --what--> B`, `→ B`), or null when it has no arrow. */
private fun flowChain(line: String): FlowChain? {
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
    // `B: what moves` on the last part labels the hop into it; a colon inside brackets (`connect({ a: 1 })`) is code.
    val last = names.last()
    val colon = topLevelColon(last)
    if (colon > 0) { labels[names.lastIndex] = last.substring(colon + 2).trim(); names[names.lastIndex] = last.substring(0, colon).trim() }
    return FlowChain(names, labels)
}

/** Where `: ` stands outside any brackets in [text], or -1. */
private fun topLevelColon(text: String): Int {
    var depth = 0
    for (i in text.indices) {
        when (text[i]) {
            '(', '[', '{', '<' -> depth++
            ')', ']', '}', '>' -> depth = maxOf(0, depth - 1)
            ':' -> if (depth == 0 && text.getOrNull(i + 1) == ' ') return i
        }
    }
    return -1
}

/**
 * A ```flow sketch, one or more hops per line (`A -> B: what moves`, `A -> B -> C`, `A --what--> B`). A line that opens
 * with an arrow leads on from the part before it, so a chain written down the page (`load()` then `→ mount` then
 * `→ connect`) is one run. A line that is not a hop stays where it was as its words, so the rest is still drawn.
 * Null when no line is a hop: then the sketch is shown as the text it is.
 */
internal fun parseFlow(text: String): List<FlowItem>? {
    val items = mutableListOf<FlowItem>()
    // The part the next line's opening arrow leads on from, and a line with no arrow that may be the start of a chain.
    var last: String? = null
    var lone: String? = null
    fun note(words: String) { items += FlowItem.Note(words) }
    fun hop(hop: FlowHop) {
        val run = items.lastOrNull() as? FlowItem.Run
        if (run != null && run.hops.last().to == hop.from) items[items.lastIndex] = FlowItem.Run(run.hops + hop) else items += FlowItem.Run(listOf(hop))
    }
    for (raw in text.lines()) {
        val line = raw.trim().removePrefix("- ").trim()
        if (line.isEmpty()) continue
        val chain = flowChain(line)
        if (chain == null) {
            lone?.let(::note)
            lone = line.takeIf { it.length <= FLOW_NAME_MAX }
            if (lone == null) note(line)
            continue
        }
        if (chain.names[0].isEmpty()) {
            val from = lone ?: last
            if (from == null) { note(line); continue }
            chain.names[0] = from
        } else {
            lone?.let(::note)
        }
        lone = null
        if (chain.names.any { it.isEmpty() || it.length > FLOW_NAME_MAX }) { note(line); continue }
        for (i in 0 until chain.names.lastIndex) hop(FlowHop(chain.names[i], chain.names[i + 1], chain.labels[i + 1]))
        last = chain.names.last()
    }
    lone?.let(::note)
    return items.takeIf { list -> list.any { it is FlowItem.Run } }
}

/** The hops of a flow, in order, whatever lines sat between them. */
internal fun flowHops(items: List<FlowItem>): List<FlowHop> = items.filterIsInstance<FlowItem.Run>().flatMap { it.hops }

/** A flow drawn from what it parsed to: each part a box, each hop an arrow down with what moves beside it, and a line that was not a hop as quiet words where it stood. */
@Composable
internal fun FlowView(items: List<FlowItem>, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { item ->
            when (item) {
                is FlowItem.Run -> Column(Modifier.fillMaxWidth()) {
                    FlowNode(item.hops.first().from)
                    item.hops.forEach { hop ->
                        FlowArrow(hop.label)
                        FlowNode(hop.to)
                    }
                }
                is FlowItem.Note -> Text(item.text, style = PullsType.sm, color = c.muted)
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
