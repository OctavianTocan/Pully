package com.octaviantocan.pulls.ui.design

import kotlin.math.roundToInt
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import com.octaviantocan.pulls.sound.cueClickable
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import androidx.compose.foundation.layout.Spacer
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.core.PULL_MENTION
import com.octaviantocan.pulls.core.pullRefOfUrl

private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create(), TaskListItemsExtension.create()))
    .build()

/** A place in the code a mention points at: a path from the repository root and a line in the new version (0 for the whole file). */
data class CodePlace(val path: String, val line: Int)

/**
 * Where the mentions in AI text go (rule 96). [resolve] gives the path of a changed file a mention
 * names (the whole path, or its end), or null; [open] scrolls to a place. A Markdown link to a path
 * in the repository is always a mention; a bare `file.ext:12` is one only when it names a changed file.
 */
class Mentions(val resolve: (String) -> String?, val open: (CodePlace) -> Unit, val repo: String? = null)

/** Set by AI screens: their Markdown turns mentions into links. Elsewhere Markdown links stay links. */
val LocalMentions = staticCompositionLocalOf<Mentions?> { null }

/**
 * Where an answer about the inbox sends the pull requests it names: [resolve] says whether `owner/repo#12` (or a link
 * to it on GitHub) is one the inbox lists, and [open] opens it in the app. Unlisted names stay plain text.
 */
class PullMentions(val resolve: (owner: String, repo: String, number: Int) -> Boolean, val open: (owner: String, repo: String, number: Int) -> Unit)

/** Set by the inbox's Ask: its Markdown turns the pull requests it names into links that open them. */
val LocalPullMentions = staticCompositionLocalOf<PullMentions?> { null }

/** Set by AI screens to draw fenced code their own way (the diff's code view, rule 95); the code and its fence language. */
val LocalCodeBlock = staticCompositionLocalOf<(@Composable (code: String, lang: String) -> Unit)?> { null }

/**
 * The place a link points at when its target is a path in the repository (`src/a.ts#L12`, `src/a.ts:12`, `./a.ts`,
 * `.github/ci.yml`), or null for a web link. The path is read from the root by [FileLinks.repoPath], which keeps a
 * dot-folder's dot: reading `.agents/…` as `./agents/…` sent a tour's link to a page GitHub did not have (J36).
 */
fun codePlaceOf(destination: String): CodePlace? {
    val target = destination.trim()
    if (target.isEmpty() || target.startsWith("#") || target.startsWith("//") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(target) && !Regex("^[^/]+:\\d").containsMatchIn(target)) return null
    val match = Regex("^([^#?\\s:]+?)(?::(\\d+)|#L(\\d+)(?:-L?\\d+)?)?$").find(target) ?: return null
    val path = FileLinks.repoPath(match.groupValues[1]) ?: return null
    val line = (match.groupValues[2].ifEmpty { match.groupValues[3] }).toIntOrNull() ?: 0
    return CodePlace(path, line)
}

/**
 * The place a link to a file on GitHub points at (`https://github.com/o/r/blob/<sha>/src/a.ts#L12`), or null. An
 * analysis shared on the pull request has its mentions written this way, so they still work on GitHub. Given [repo]
 * (`owner/name`), a link into any other repository is null, so it stays a web link.
 */
fun githubPlaceOf(destination: String, repo: String? = null): CodePlace? {
    val match = GITHUB_BLOB.find(destination.trim()) ?: return null
    if (repo != null && !"${match.groupValues[1]}/${match.groupValues[2]}".equals(repo, ignoreCase = true)) return null
    val path = runCatching { java.net.URLDecoder.decode(match.groupValues[3].replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
    return CodePlace(path, match.groupValues[4].toIntOrNull() ?: 0)
}

private val GITHUB_BLOB = Regex("""^https://github\.com/([^/\s]+)/([^/\s]+)/blob/[^/\s]+/([^#?\s]+?)(?:#L(\d+)(?:-L?\d+)?)?$""")

/**
 * Where a link in AI text goes in the code: any path in the repository, or a GitHub link to a file this change touches
 * in this [repo] (a GitHub link to any other file stays a web link). Null for a web link.
 */
fun linkPlaceOf(destination: String, resolve: (String) -> String?, repo: String? = null): CodePlace? =
    codePlaceOf(destination)?.let { it.copy(path = resolve(it.path) ?: it.path) }
        ?: githubPlaceOf(destination, repo)?.let { place -> resolve(place.path)?.let { place.copy(path = it) } }

/**
 * The place backticked code names, so `key.ts`, `src/cache/key.ts:42` and `src/a.ts#L12` are mentions too: a file this
 * change touches (by its whole path or its end, with or without a line), or a path with a folder, an extension and a
 * line. Anything else (`cacheKey`, `localhost:8080`, `foo.bar()`) is code. Null when it names no place.
 */
fun codeSpanPlaceOf(literal: String, resolve: (String) -> String?): CodePlace? {
    val place = codePlaceOf(literal.trim()) ?: return null
    resolve(place.path)?.let { return place.copy(path = it) }
    return place.takeIf { it.line > 0 && '/' in it.path && PATH_WITH_EXTENSION.matches(it.path) }
}

private val PATH_WITH_EXTENSION = Regex("""[\w./-]+\.[A-Za-z0-9]+""")

/** Every place [markdown] mentions, in order and each once: its links into [repo], and the backticked paths and named files that [resolve] knows. */
fun mentionedPlaces(markdown: String, resolve: (String) -> String?, repo: String? = null): List<CodePlace> {
    val places = LinkedHashSet<CodePlace>()
    fun walk(node: Node) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Link -> linkPlaceOf(child.destination, resolve, repo)?.let { places += it }
                is MdText -> bareCites(child.literal).forEach { (_, name, line) -> resolve(name)?.let { places += CodePlace(it, line) } }
                is Code -> codeSpanPlaceOf(child.literal, resolve)?.let { places += it }
                is FencedCodeBlock, is IndentedCodeBlock -> Unit
                else -> walk(child)
            }
            child = child.next
        }
    }
    walk(parser.parse(markdown))
    return places.toList()
}

/** `[file.ext:12]`, `file.ext:12` or a bare `file.ext` in running text, as the analysis notes write it. */
private val BARE_CITE = Regex("""\[([\w./-]+\.[A-Za-z0-9]+):(\d+)]|(?<![\w./-])([\w./-]+\.[A-Za-z0-9]+)(?::(\d+))?(?![\w/-])""")

/** Where running text names a file: the range of the words, the name as written and its line (0 for none). */
fun bareCites(text: String): Sequence<Triple<IntRange, String, Int>> = BARE_CITE.findAll(text).map { m ->
    Triple(m.range, m.groupValues[1].ifEmpty { m.groupValues[3] }, m.groupValues[2].ifEmpty { m.groupValues[4] }.toIntOrNull() ?: 0)
}

/**
 * GitHub-flavored Markdown as the web renders it with the `chat-markdown` shortcut
 * (`uno.config.ts`): block gap 10px, `pl-5` lists, `bg-code` inline code, bordered
 * `pre` blocks that scroll sideways, tables that scroll, underlined links. Raw HTML
 * (GitHub bot comments) is reduced to its text, as `PrHtml` would show it.
 */
@Composable
fun Markdown(source: String, modifier: Modifier = Modifier, style: TextStyle = PullsType.sm) {
    val document = remember(source) { parser.parse(source) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var child = document.firstChild
        while (child != null) {
            Block(child, style)
            child = child.next
        }
    }
}

/**
 * The start of [source] as one text of at most [maxLines] lines, for a row that previews it (a file's note on the
 * Overview): its blocks run together and its mentions are links as in [Markdown] (rule 96).
 */
@Composable
fun MarkdownPreview(source: String, modifier: Modifier = Modifier, style: TextStyle = PullsType.sm, maxLines: Int = 2) {
    val c = LocalPullsColors.current
    val mentions = LocalMentions.current
    val text = remember(source, c, mentions) {
        buildAnnotatedString {
            var block = parser.parse(source).firstChild
            while (block != null) {
                if (length > 0) append(' ')
                appendInline(block, c, mentions)
                block = block.next
            }
        }
    }
    Text(text, modifier, style = style.copy(color = style.color.takeOrElse(c.text)), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Block(node: Node, style: TextStyle) {
    val c = LocalPullsColors.current
    when (node) {
        is Paragraph -> Inline(node, style)
        is Heading -> Inline(node, style.copy(fontWeight = FontWeight.SemiBold, fontSize = if (node.level <= 2) PullsType.base.fontSize else style.fontSize))
        is BulletList, is OrderedList -> ListBlock(node, style)
        is FencedCodeBlock -> {
            val custom = LocalCodeBlock.current
            when {
                node.info.orEmpty().startsWith(DETAILS_INFO) -> Details(node.info.removePrefix(DETAILS_INFO).trim(), node.literal, style)
                custom != null -> custom(node.literal.trimEnd('\n'), node.info.orEmpty().trim().substringBefore(' ').lowercase())
                else -> CodeBlock(node.literal.trimEnd('\n'))
            }
        }
        is IndentedCodeBlock -> LocalCodeBlock.current?.invoke(node.literal.trimEnd('\n'), "") ?: CodeBlock(node.literal.trimEnd('\n'))
        is BlockQuote -> Row {
            Box(Modifier.width(2.dp).background(c.border).padding(vertical = 2.dp))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var child = node.firstChild
                while (child != null) {
                    Block(child, style.copy(color = c.fade))
                    child = child.next
                }
            }
        }
        is ThematicBreak -> Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        is TableBlock -> TableView(node, style)
        is HtmlBlock -> {
            val text = htmlText(node.literal)
            if (text.isNotBlank()) Text(text, style = style.copy(color = c.text))
        }
        else -> Inline(node, style)
    }
}

@Composable
private fun ListBlock(list: Node, style: TextStyle) {
    val c = LocalPullsColors.current
    var number = (list as? OrderedList)?.markerStartNumber ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val task = (item.firstChild as? TaskListItemMarker) ?: (item.firstChild?.firstChild as? TaskListItemMarker)
                val marker = when {
                    task != null -> null
                    list is OrderedList -> "${number++}."
                    else -> "•"
                }
                Row {
                    // A task item has the kit's bare check before the text, read-only like GitHub's (rule 41).
                    if (task != null) PullsCheck(task.isChecked, Modifier.padding(top = 1.dp, end = 8.dp), size = 18.dp)
                    else Text(marker!!, Modifier.width(20.dp), style = style.copy(color = style.color.takeOrElse(c.text)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        var child = item.firstChild
                        while (child != null) {
                            Block(child, style)
                            child = child.next
                        }
                    }
                }
            }
            item = item.next
        }
    }
}

/** Fence info string marking a `<details>` block from GitHub HTML (see `htmlToMarkdown`). */
const val DETAILS_INFO = "pulls-details"

/** `<details>`: a disclosure row (`▶ summary`), closed by default; the body renders as Markdown when opened. */
@Composable
private fun Details(summary: String, body: String, style: TextStyle) {
    val c = LocalPullsColors.current
    var open by androidx.compose.runtime.saveable.rememberSaveable(summary, body) { androidx.compose.runtime.mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.cueClickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (open) "▼" else "▶", style = style.copy(fontSize = style.fontSize * 0.7f, color = c.text))
            Text(summary, style = style.copy(color = c.text))
        }
        if (open) Markdown(body, style = style)
    }
}

@Composable
private fun CodeBlock(code: String) {
    val c = LocalPullsColors.current
    val scroll = rememberScrollState()
    // On the code paper with no border, at body size (rules 14 and 49); AI text swaps in the diff's code view instead.
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.xl)).background(c.code)
            .sideScrollFade(scroll, color = c.code).horizontalScroll(scroll).padding(12.dp),
    ) {
        Text(code, style = PullsType.sm.copy(fontFamily = PullsType.mono, color = c.text), softWrap = false)
    }
}

/**
 * A GitHub table that stays readable on a phone (rule 95): each column is as wide as its longest
 * line up to [TABLE_COLUMN_MAX], so a description wraps at about 240dp instead of being squeezed to
 * a few characters, and a table wider than the screen scrolls sideways under a fade at the edge it
 * can still scroll toward. A narrow table spreads its columns to the full width. The header row
 * sits on the code paper, rows are split by hairlines, and the whole table is one rounded card.
 */
@Composable
private fun TableView(table: TableBlock, style: TextStyle) {
    val c = LocalPullsColors.current
    val rows = remember(table) { tableRows(table) }
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.cells.size }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val body = PullsType.sm.copy(color = c.text)
    val head = body.copy(fontWeight = FontWeight.SemiBold)
    val natural = remember(rows, density) {
        List(columns) { n ->
            val widest = rows.maxOf { row ->
                row.cells.getOrNull(n)?.let { cell -> measurer.measure(cellText(cell), if (row.head) head else body, softWrap = false).size.width } ?: 0
            }
            with(density) { widest.toDp() } + TABLE_CELL_PAD * 2
        }.map { it.coerceIn(TABLE_COLUMN_MIN, TABLE_COLUMN_MAX) }
    }
    val shape = RoundedCornerShape(PullsRadius.lg)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).background(c.raised).border(1.dp, c.border, shape)) {
        val total = natural.fold(0.dp) { sum, w -> sum + w }
        val room = maxWidth - 2.dp
        val widths = if (total < room) natural.map { it * (room / total) } else natural
        val width = widths.fold(0.dp) { sum, w -> sum + w }
        val scroll = rememberScrollState()
        Column(Modifier.sideFade(scroll).horizontalScroll(scroll)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.width(width).height(1.dp).background(if (rows[i - 1].head) c.border else c.borderMute))
                Row(if (row.head) Modifier.background(c.code) else Modifier) {
                    widths.forEachIndexed { n, w ->
                        val cell = row.cells.getOrNull(n)
                        val align = when (cell?.alignment) {
                            TableCell.Alignment.CENTER -> Alignment.TopCenter
                            TableCell.Alignment.RIGHT -> Alignment.TopEnd
                            else -> Alignment.TopStart
                        }
                        Box(Modifier.width(w).padding(horizontal = TABLE_CELL_PAD, vertical = 10.dp), contentAlignment = align) {
                            if (cell != null) Inline(cell, if (row.head) head else body)
                        }
                    }
                }
            }
        }
    }
}

/** The widest a table column grows before its text wraps: a description reads at this width (rule 95). */
private val TABLE_COLUMN_MAX = 240.dp
private val TABLE_COLUMN_MIN = 56.dp
private val TABLE_CELL_PAD = 12.dp
/** How wide the fade is at an edge the table can still scroll toward. */
private val TABLE_FADE = 40.dp

private class TableLine(val head: Boolean, val cells: List<TableCell>)

private fun tableRows(table: TableBlock): List<TableLine> = buildList {
    var section = table.firstChild
    while (section != null) {
        if (section is TableHead || section is TableBody) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = mutableListOf<TableCell>()
                    var cell = row.firstChild
                    while (cell != null) { if (cell is TableCell) cells += cell; cell = cell.next }
                    add(TableLine(section is TableHead, cells))
                }
                row = row.next
            }
        }
        section = section.next
    }
}

/** A cell's words on one line, as they are measured for its column's width. */
private fun cellText(node: Node): String = buildString {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MdText -> append(child.literal)
            is Code -> append("\u2009").append(child.literal).append("\u2009")
            is SoftLineBreak, is HardLineBreak -> append(' ')
            else -> append(cellText(child))
        }
        child = child.next
    }
}

/**
 * Sideways content fades out at the edge it can still scroll toward, over [TABLE_FADE], and the
 * fade shrinks as the last of it comes into view. It is read in the draw phase, so scrolling
 * redraws without recomposing.
 */
private fun Modifier.sideFade(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val band = TABLE_FADE.toPx()
        val before = (scroll.value / band).coerceIn(0f, 1f)
        val after = ((scroll.maxValue - scroll.value) / band).coerceIn(0f, 1f)
        // raw-value-ok: an alpha mask, never drawn as a colour
        if (before > 0f) drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 1f - before), 1f to Color.Black, startX = 0f, endX = band), blendMode = BlendMode.DstIn)
        // raw-value-ok: an alpha mask, never drawn as a colour
        if (after > 0f) drawRect(Brush.horizontalGradient(0f to Color.Black, 1f to Color.Black.copy(alpha = 1f - after), startX = size.width - band, endX = size.width), blendMode = BlendMode.DstIn)
    }

/** True when [node] holds an image somewhere in its inline content. */
private fun hasImage(node: Node): Boolean {
    var child = node.firstChild
    while (child != null) {
        if (child is Image || hasImage(child)) return true
        child = child.next
    }
    return false
}

/** Inline content split into text runs and images (an image inside a link keeps the link). */
private sealed interface Piece {
    data class Run(val node: Node) : Piece
    /** [title] may carry `dark:<url>`, the `<picture>` source for dark mode. */
    data class Picture(val src: String, val title: String, val href: String?, val alt: String = "") : Piece
}

private fun pieces(parent: Node, href: String? = null, out: MutableList<Piece> = mutableListOf()): MutableList<Piece> {
    var child = parent.firstChild
    while (child != null) {
        when {
            child is Image -> out += Piece.Picture(child.destination, child.title.orEmpty(), href, altText(child))
            child is Link && hasImage(child) -> pieces(child, child.destination, out)
            else -> out += Piece.Run(child)
        }
        child = child.next
    }
    return out
}

/** `[&_img]:inline-block [&_img]:h-auto [&_img]:max-w-full`: images in the text flow, links kept. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun InlineWithImages(node: Node, style: TextStyle) {
    val c = LocalPullsColors.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        val runs = mutableListOf<Node>()
        @Composable
        fun flush() {
            if (runs.isEmpty()) return
            val text = buildAnnotatedString { runs.forEach { n -> appendNode(n, c) } }
            runs.clear()
            if (text.isNotBlank()) Text(text, style = style.copy(color = style.color.takeOrElse(c.text)))
        }
        pieces(node).forEach { piece ->
            when (piece) {
                is Piece.Run -> runs += piece.node
                is Piece.Picture -> {
                    flush()
                    val src = piece.title.takeIf { c.dark && it.startsWith("dark:") }?.removePrefix("dark:") ?: piece.src
                    // A picture always opens something, its link or else itself, so a tap on a badge never falls through
                    // to what is under the text, such as the description's editor ("I clicked on Devin review and it
                    // opened the editing", recording 15 Oct 15:37, 09:10 to 09:15; L14).
                    val target = piece.href ?: piece.src
                    InlinePicture(src, piece.alt) { runCatching { uri.openUri(target) } }
                }
            }
        }
        flush()
    }
}

private fun altText(image: Image): String = buildString {
    var n = image.firstChild
    while (n != null) { if (n is MdText) append(n.literal); n = n.next }
}

/**
 * An image at its own CSS size (`[&_img]:h-auto [&_img]:max-w-full`): one image pixel is one dp as one CSS px is, and
 * it is shrunk only to fit the width. The first load at the image's own size gives that size; the image is then drawn
 * from a second load at the screen's density, so a vector badge is as sharp as on GitHub instead of a small bitmap
 * stretched three times ("it is rendering in pretty low quality", recording 15 Oct 15:37, 09:17 to 09:28; L15).
 */
@Composable
private fun InlinePicture(src: String, alt: String, onClick: (() -> Unit)?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val request = remember(src) { coil3.request.ImageRequest.Builder(context).data(src).size(coil3.size.Size.ORIGINAL).build() }
    val probe = coil3.compose.rememberAsyncImagePainter(request)
    val size = probe.intrinsicSize
    val known = size.isSpecified && size.width > 0f && size.height > 0f
    androidx.compose.foundation.layout.BoxWithConstraints {
        val maxW = maxWidth.value
        val w = if (known) minOf(size.width, maxW) else 0f
        val h = if (known) w * size.height / size.width else 0f
        val sharpRequest = remember(src, w, h, density) {
            if (!known) request
            else coil3.request.ImageRequest.Builder(context).data(src)
                .size((w * density).roundToInt().coerceAtLeast(1), (h * density).roundToInt().coerceAtLeast(1))
                .precision(coil3.size.Precision.EXACT)
                .build()
        }
        val sharp = coil3.compose.rememberAsyncImagePainter(sharpRequest)
        val ready by sharp.state.collectAsState()
        androidx.compose.foundation.Image(
            if (ready is coil3.compose.AsyncImagePainter.State.Success) sharp else probe, alt,
            Modifier.width(w.dp).height(h.dp).let { m -> if (onClick != null) m.cueClickable(onClick = onClick) else m },
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    }
}

/** One inline node into an annotated string. */
private fun AnnotatedString.Builder.appendNode(node: Node, c: PullsColors) = appendInlineOne(node, c)

@Composable
private fun Inline(node: Node, style: TextStyle) {
    if (hasImage(node)) { InlineWithImages(node, style); return }
    val c = LocalPullsColors.current
    val mentions = LocalMentions.current
    val pulls = LocalPullMentions.current
    val text = remember(node, c, mentions, pulls) {
        buildAnnotatedString { if (pulls != null) appendWithPulls(node, c, pulls) else appendInline(node, c, mentions) }
    }
    if (text.isEmpty()) return
    Text(text, style = style.copy(color = style.color.takeOrElse(c.text)))
}

private fun androidx.compose.ui.graphics.Color.takeOrElse(other: androidx.compose.ui.graphics.Color) =
    if (this == androidx.compose.ui.graphics.Color.Unspecified) other else this

private fun AnnotatedString.Builder.appendInline(parent: Node, c: PullsColors, mentions: Mentions? = null) {
    var node = parent.firstChild
    while (node != null) {
        appendInlineOne(node, c, mentions)
        node = node.next
    }
}

/** A pull request an inbox answer names: drawn like a mention (rule 96), and a tap opens it. */
private fun AnnotatedString.Builder.withPull(owner: String, repo: String, number: Int, c: PullsColors, pulls: PullMentions, content: AnnotatedString.Builder.() -> Unit) {
    val style = TextLinkStyles(SpanStyle(color = c.activeText, fontFamily = PullsType.display, fontWeight = FontWeight.Medium))
    withLink(LinkAnnotation.Clickable("pull", style) { pulls.open(owner, repo, number) }) { content() }
}

/** Inline Markdown of an inbox answer: `owner/repo#12` and links to listed pull requests open them, shown as `repo#12`. */
private fun AnnotatedString.Builder.appendWithPulls(parent: Node, c: PullsColors, pulls: PullMentions) {
    var node = parent.firstChild
    while (node != null) {
        val current = node
        when {
            current is MdText -> {
                var at = 0
                for (match in PULL_MENTION.findAll(current.literal)) {
                    val (owner, repo, digits) = match.destructured
                    val number = digits.toIntOrNull() ?: continue
                    if (!pulls.resolve(owner, repo, number)) continue
                    append(current.literal.substring(at, match.range.first))
                    withPull(owner, repo, number, c, pulls) { append("$repo#$number") }
                    at = match.range.last + 1
                }
                append(current.literal.substring(at))
            }
            current is Link && pullLink(current.destination, pulls) != null -> {
                val (owner, repo, number) = pullLink(current.destination, pulls)!!
                withPull(owner, repo, number, c, pulls) { appendWithPulls(current, c, pulls) }
            }
            current is Code || current is HardLineBreak || current is SoftLineBreak || current is Image || current is HtmlInline || current is TaskListItemMarker -> appendInlineOne(current, c)
            current is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendWithPulls(current, c, pulls) }
            current is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendWithPulls(current, c, pulls) }
            current is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendWithPulls(current, c, pulls) }
            current is Link -> withLink(LinkAnnotation.Url(current.destination, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) { appendWithPulls(current, c, pulls) }
            else -> appendWithPulls(current, c, pulls)
        }
        node = node.next
    }
}

/** The listed pull request a link points at on GitHub, or null. */
private fun pullLink(destination: String, pulls: PullMentions): Triple<String, String, Int>? =
    pullRefOfUrl(destination)?.takeIf { pulls.resolve(it.owner, it.repo, it.number) }?.let { Triple(it.owner, it.repo, it.number) }

/** A mention: Mona Sans medium in the link colour with no underline, as the analysis cites (rule 96). */
private fun AnnotatedString.Builder.withMention(place: CodePlace, c: PullsColors, mentions: Mentions, content: AnnotatedString.Builder.() -> Unit) {
    val style = TextLinkStyles(SpanStyle(color = c.activeText, fontFamily = PullsType.display, fontWeight = FontWeight.Medium))
    withLink(LinkAnnotation.Clickable("mention", style) { mentions.open(place) }) { content() }
}

private fun AnnotatedString.Builder.appendInlineOne(node: Node, c: PullsColors, mentions: Mentions? = null) {
    run {
        when (node) {
            is MdText -> if (mentions == null) append(node.literal) else {
                var at = 0
                // A changed file named in the text is a mention, with its line or at its top (rule 96).
                for ((range, name, line) in bareCites(node.literal)) {
                    val path = mentions.resolve(name) ?: continue
                    append(node.literal.substring(at, range.first))
                    withMention(CodePlace(path, line), c, mentions) { append(if (line > 0) "${name.substringAfterLast('/')}:$line" else name) }
                    at = range.last + 1
                }
                append(node.literal.substring(at))
            }
            is Link -> {
                val place = mentions?.let { linkPlaceOf(node.destination, it.resolve, it.repo) }
                if (place != null && mentions != null) withMention(place, c, mentions) { appendInline(node, c) }
                // A web link's words are its own: no mention nests inside it.
                else withLink(LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) { appendInline(node, c) }
            }
            // `text-[max(0.85em,var(--pr-code-min))] px-1 py-0.5 rounded bg-code` (13px floor on phones).
            is Code -> {
                val code = SpanStyle(fontFamily = PullsType.mono, background = c.code, fontSize = if (PullsType.wide) 0.85.em else 13.sp)
                val place = mentions?.let { codeSpanPlaceOf(node.literal, it.resolve) }
                // A path in backticks is a mention too, and reads like every other link: one face, not code for some and
                // sans for the rest (K16).
                if (place != null && mentions != null) withMention(place, c, mentions) { append(node.literal) }
                else withStyle(code) { append("\u2009${node.literal}\u2009") }
            }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(node, c, mentions) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(node, c, mentions) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(node, c, mentions) }
            is Image -> withStyle(SpanStyle(color = c.fade)) { append(node.title?.takeIf { it.isNotBlank() } ?: "[image]") }
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            is HtmlInline -> {
                val tag = node.literal
                if (tag.startsWith("<br", ignoreCase = true)) append('\n')
            }
            is TaskListItemMarker -> Unit
            else -> appendInline(node, c, mentions)
        }
    }
}

/** Text content of an HTML fragment: tags dropped, `<br>`/block ends as newlines, entities decoded. */
fun htmlText(html: String): String = html
    .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h\\d|tr)>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
