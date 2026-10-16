package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.design.sideScrollFade
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.namedPlaceOf
import com.octaviantocan.pulls.ui.review.copyText
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
 * highlighting, its size setting, a sideways scroll and the same pinch to zoom, with a copy button;
 * pseudocode is the same well, its lines on indent guides. A flow is drawn: each part a numbered
 * card on a rail, each hop an arrow with what moves beside it. A call tree and a file tree are rows
 * on guide lines.
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
    val scroll = rememberScrollState()
    CodeFrame(session, text.text, modifier) {
        Box(Modifier.fillMaxWidth().sideScrollFade(scroll, color = c.paper).horizontalScroll(scroll).padding(12.dp)) {
            Text(text, style = codeTextStyle(zoom).copy(color = c.text), softWrap = false)
        }
    }
}

/**
 * The frame of a code well: the paper of the diff's code with an outline, which keeps it apart from a sheet of the same
 * paper, the diff's pinch to zoom, and a copy button in its top corner, as a code block has on the web ("pseudocode
 * should have a copy button like code", recording of 16 Oct 12:10, 09:51 to 09:55, N18). The button sits on the paper,
 * so the code that scrolls under it stays out of its way; copying shows the toast (kit-copy-shows-a-toast).
 */
@Composable
private fun CodeFrame(session: ReviewSession, copy: String, modifier: Modifier, content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val shape = RoundedCornerShape(PullsRadius.xl)
    Box(modifier.fillMaxWidth().clip(shape).background(c.paper).border(1.dp, c.border, shape).pinchToZoomCode(session.settings)) {
        content()
        FloatIcon(
            Lu.copy, "Copy the code", { copyText(context, copy); toaster.show("Code copied", cue = Cue.Success) },
            Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(c.paper),
            flat = true, size = COPY_SIZE, iconSize = 16.dp, tint = c.muted,
        )
    }
}

/** The copy button of a code well; a wrapped well keeps this much room at its right for it. */
private val COPY_SIZE = 36.dp

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
        "pseudo", "pseudocode" -> PseudoView(session, code)
        // An answer in Ask draws with the tour's forms, a diagram too (L34).
        "mermaid" -> Diagram(session, com.octaviantocan.pulls.data.TourBlock("diagram", "", body = code))
        "calls" -> SketchTree(session, code, files = false)
        "tree" -> SketchTree(session, code, files = true)
        else -> AiCodeView(session, code, lang)
    }
}

/**
 * A ```pseudo sketch as code: the diff's code face and size in the code well, with its copy button, the words that steer
 * it (if, for, return...) in medium weight, comments muted and the rest in the text colour, and a guide line down each
 * level of indent, so the blocks read at a glance. Lines wrap under their own indent, so nothing is cut at either edge.
 * As plain grey steps it read as prose ("I still don't like the pseudo code", recording of 16 Oct 12:10, 08:16 to 08:20,
 * N13; it was coloured code cut at both edges before that, L27).
 */
@Composable
private fun PseudoView(session: ReviewSession, code: String) {
    val c = LocalPullsColors.current
    val zoom = session.settings.codeZoom
    val lines = remember(code) { code.trimEnd().lines().dropWhile { it.isBlank() }.filter { it.isNotBlank() } }
    val unit = remember(lines) { lines.map { it.length - it.trimStart().length }.filter { it > 0 }.minOrNull() ?: 2 }
    val style = codeTextStyle(zoom)
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    CodeFrame(session, code.trimEnd(), modifier = Modifier) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = COPY_SIZE)) {
            lines.forEach { line ->
                val depth = ((line.length - line.trimStart().length) / unit).coerceAtMost(PSEUDO_DEPTH_MAX)
                val text = remember(line, c) { pseudoLine(line.trim(), c.text, c.muted) }
                // The rows touch, so the guides of a level join into one line down the block.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    if (depth > 0) Canvas(Modifier.width(PSEUDO_INDENT * zoom * depth).fillMaxHeight()) {
                        val stroke = 1.dp.toPx()
                        for (k in 0 until depth) {
                            val x = (PSEUDO_INDENT * zoom * k).toPx() + PSEUDO_GUIDE_IN.toPx()
                            drawLine(c.faint, Offset(x, 0f), Offset(x, size.height), stroke)
                        }
                    }
                    Text(text, Modifier.weight(1f).heightIn(min = lineHeight), style = style.copy(color = c.text))
                }
            }
        }
    }
}

/** One line of pseudocode: its steering words in medium weight, its comment muted, the rest in [ink]. */
private fun pseudoLine(line: String, ink: Color, muted: Color): AnnotatedString = buildAnnotatedString {
    pseudoTokens(line).forEach { tok ->
        when {
            tok.kind == PseudoKind.Keyword -> withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = ink)) { append(tok.text) }
            tok.kind == PseudoKind.Comment -> withStyle(SpanStyle(color = muted)) { append(tok.text) }
            else -> append(tok.text)
        }
    }
}

/** How far one level of a pseudocode line sits in at the normal code size: two characters of the code face. */
private val PSEUDO_INDENT = 15.dp
/** Where a level's guide runs: just inside the first letter of the line it hangs from. */
private val PSEUDO_GUIDE_IN = 3.dp
/** The deepest level drawn as its own step in; deeper lines sit at this one. */
private const val PSEUDO_DEPTH_MAX = 6

/** One level of a drawn tree: its guide runs down the middle of this width. */
private val TREE_INDENT = 18.dp
/** Above and below each tree row's text; the rows touch, so their guides join. */
private val TREE_ROW_PAD = 3.dp
/** The column a call chain's arrows stand in, under the name they lead on from. */
private val CHAIN_ARROW = 20.dp

/**
 * A ```calls or ```tree sketch drawn as a tree (rule 95) in a well of its own, like code: each row at its depth, hung
 * from its parent by a guide line that ends in a dot at the row, its name in the code face and the comment beside it in
 * the muted colour. A call written as a chain (`AskPill → openAsk → AskSheet`) puts each step of it on a line of its own,
 * its arrow in one column under the name, so the steps line up and never wrap into each other ("really easy and nice to
 * look at", recording of 16 Oct 12:10, 09:17 to 09:24, N16). A file tree puts the file's icon in front, as the Files list
 * does; a row that names a changed file opens it, and so does a call named after one (N17). A sketch with no rows is
 * shown as its text.
 */
@Composable
private fun SketchTree(session: ReviewSession, code: String, files: Boolean) {
    val c = LocalPullsColors.current
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
    val shape = RoundedCornerShape(PullsRadius.xl)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.paper).border(1.dp, c.border, shape).padding(horizontal = 12.dp, vertical = 10.dp)) {
        rows.forEachIndexed { i, row ->
            val icon = when {
                !files || row.name.isEmpty() -> null
                isFolderRow(rows, i) -> R.drawable.ic_cat_folder_open
                else -> fileIconRes(row.name)
            }
            val open: (() -> Unit)? = targets[i]?.let { path -> { mentions?.open?.invoke(CodePlace(path, 0)) } }
            val steps = remember(row.name, files) { if (files) listOf(row.name) else callChain(row.name) }
            TreeRow(row, steps, guides[i], icon, open) { name -> if (files || mentions == null) null else namedPlaceOf(name, mentions.names) }
        }
    }
}

/** The steps of a call written as a chain (`a → b`, `a -> b`, `a => b`), or the call alone. */
internal fun callChain(name: String): List<String> = name.split(CHAIN).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(name) }

/** An arrow between two steps of a call chain, with a space on each side, so `a->b` in code stays one name. */
private val CHAIN = Regex("""\s+(?:->|→|=>|⟶|⇒)\s+""")

/** The changed file a tree row's path names: the whole path, or the end of it when the tree starts above the repository. */
private fun changedFileOf(path: String, resolve: (String) -> String?): String? {
    val parts = path.split('/')
    for (drop in parts.indices) resolve(parts.drop(drop).joinToString("/"))?.let { return it }
    return null
}

/**
 * One row of a drawn tree: the guides of the levels above it, its branch ending in a dot, the icon of a file tree, and
 * the name, each further step of a call chain on its own line under it, with the comment after the last. [place] says
 * where a step of a call opens, or null.
 */
@Composable
private fun TreeRow(row: SketchRow, steps: List<String>, guides: List<Boolean>, @DrawableRes icon: Int?, onOpen: (() -> Unit)?, place: (String) -> CodePlace?) {
    val c = LocalPullsColors.current
    val mentions = LocalMentions.current
    val style = PullsType.sm
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    val guide = c.muted.copy(alpha = 0.45f)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (onOpen != null) Modifier.clip(RoundedCornerShape(PullsRadius.sm)).cueClickable(role = Role.Button, onClickLabel = "Open ${row.name}", onClick = onOpen) else Modifier),
    ) {
        if (guides.isNotEmpty()) Canvas(Modifier.width(TREE_INDENT * guides.size).fillMaxHeight()) {
            val step = TREE_INDENT.toPx()
            val stroke = 1.dp.toPx()
            // The branch meets the row at the middle of its first line, and ends in a dot just before the name.
            val mid = (TREE_ROW_PAD + lineHeight / 2).toPx()
            guides.forEachIndexed { k, down ->
                val x = k * step + step / 2
                if (k < guides.lastIndex) {
                    if (down) drawLine(guide, Offset(x, 0f), Offset(x, size.height), stroke)
                } else {
                    drawLine(guide, Offset(x, 0f), Offset(x, if (down) size.height else mid), stroke)
                    drawLine(guide, Offset(x, mid), Offset(size.width - 6.dp.toPx(), mid), stroke)
                    drawCircle(guide, 2.dp.toPx(), Offset(size.width - 4.dp.toPx(), mid))
                }
            }
        }
        if (icon != null) Box(Modifier.padding(top = TREE_ROW_PAD, end = 6.dp).height(lineHeight), contentAlignment = Alignment.Center) {
            FileIcon(icon, Modifier.alpha(0.65f))
        }
        Column(Modifier.weight(1f).padding(vertical = TREE_ROW_PAD)) {
            steps.forEachIndexed { k, name ->
                val target = if (onOpen == null) place(name) else null
                val link = onOpen != null || target != null
                val text = buildAnnotatedString {
                    // A long name wraps between its words, never cut (rule 2).
                    withStyle(SpanStyle(fontFamily = PullsType.mono, color = if (link) c.activeText else c.text)) { append(softBreaks(name)) }
                    if (k == steps.lastIndex && row.note.isNotEmpty()) {
                        if (name.isNotEmpty()) append("  ")
                        withStyle(SpanStyle(color = c.muted)) { append(row.note) }
                    }
                }
                val tap = if (target != null && mentions != null) Modifier.clip(RoundedCornerShape(PullsRadius.sm)).cueClickable(role = Role.Button, onClickLabel = "Open $name") { mentions.open(target) } else Modifier
                if (k == 0) Text(text, tap, style = style, color = c.text)
                else Row {
                    Text("→", Modifier.width(CHAIN_ARROW), style = style.copy(fontFamily = PullsType.mono), color = c.muted)
                    Text(text, Modifier.weight(1f).then(tap), style = style, color = c.text)
                }
            }
        }
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

/**
 * A flow drawn from what it parsed to, as steps on a rail: each part a card the width of the page with its number in a
 * dot on the rail, each hop the rail running down to the next dot with an arrowhead and what moves beside it, and a line
 * that was not a hop as quiet words where it stood. Boxes of every width under a bare arrow read as a list of code, not
 * a flow ("the Flow block needs visual work", recording of 16 Oct 12:10, 09:56 to 09:58, N19).
 */
@Composable
internal fun FlowView(items: List<FlowItem>, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { item ->
            when (item) {
                is FlowItem.Run -> Column(Modifier.fillMaxWidth()) {
                    FlowNode(1, item.hops.first().from, last = false)
                    item.hops.forEachIndexed { i, hop ->
                        FlowArrow(hop.label)
                        FlowNode(i + 2, hop.to, last = i == item.hops.lastIndex)
                    }
                }
                is FlowItem.Note -> Text(item.text, style = PullsType.sm, color = c.muted)
            }
        }
    }
}

/** The rail a flow's steps hang on: its dots are this wide, and it runs down their middle. */
private val FLOW_RAIL = 24.dp
/** Between the rail and a step's card. */
private val FLOW_GAP = 10.dp

/** One part of a flow: its number in a dot on the rail, the rail running on from it to the next unless [last], and its name in a card. */
@Composable
private fun FlowNode(number: Int, name: String, last: Boolean) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(PullsRadius.lg)
    val rail = c.muted.copy(alpha = 0.45f)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(FLOW_RAIL).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val x = size.width / 2
                val stroke = 1.5.dp.toPx()
                if (!last) drawLine(rail, Offset(x, size.height / 2), Offset(x, size.height), stroke)
            }
            Box(Modifier.size(FLOW_RAIL).clip(CircleShape).background(c.accentWash), contentAlignment = Alignment.Center) {
                Text("$number", style = PullsType.xs.copy(fontWeight = FontWeight.SemiBold), color = c.accentWashInk)
            }
        }
        Box(
            Modifier.padding(start = FLOW_GAP).weight(1f).clip(shape).background(c.raised).border(1.dp, c.border, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // A long name wraps between its words, never cut (rule 2).
            Text(softBreaks(name), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text)
        }
    }
}

/** The way down from one part to the next: the rail with an arrowhead into the next dot, and what moves beside it. */
@Composable
private fun FlowArrow(label: String) {
    val c = LocalPullsColors.current
    val rail = c.muted.copy(alpha = 0.45f)
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(FLOW_RAIL).fillMaxHeight()) {
            val x = size.width / 2
            val head = 4.dp.toPx()
            val stroke = 1.5.dp.toPx()
            val end = size.height - 2.dp.toPx()
            drawLine(rail, Offset(x, 0f), Offset(x, end), stroke, StrokeCap.Round)
            val tip = Path().apply {
                moveTo(x - head, end - head)
                lineTo(x, end)
                lineTo(x + head, end - head)
            }
            drawPath(tip, rail, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (label.isNotBlank()) Text(label, Modifier.padding(start = FLOW_GAP, top = 4.dp, bottom = 4.dp), style = PullsType.sm, color = c.muted)
    }
}
