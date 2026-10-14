package com.octaviantocan.pulls.ui.design

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create(), TaskListItemsExtension.create()))
    .build()

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

@Composable
private fun Block(node: Node, style: TextStyle) {
    val c = LocalPullsColors.current
    when (node) {
        is Paragraph -> Inline(node, style)
        is Heading -> Inline(node, style.copy(fontWeight = FontWeight.SemiBold, fontSize = if (node.level <= 2) PullsType.base.fontSize else style.fontSize))
        is BulletList, is OrderedList -> ListBlock(node, style)
        is FencedCodeBlock ->
            if (node.info.orEmpty().startsWith(DETAILS_INFO)) Details(node.info.removePrefix(DETAILS_INFO).trim(), node.literal, style)
            else CodeBlock(node.literal.trimEnd('\n'))
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'))
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
        is ThematicBreak -> HorizontalDivider(color = c.border)
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
                    if (task != null) {
                        // `[&_.task-list-item]:list-none` with GitHub's disabled checkbox before the text.
                        Box(Modifier.padding(top = 4.dp, end = 6.dp).size(14.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (task.isChecked) c.primary else c.raised).border(1.dp, if (task.isChecked) c.primary else c.border, RoundedCornerShape(3.dp)),
                            contentAlignment = androidx.compose.ui.Alignment.Center) {
                            if (task.isChecked) PhIcon("check-bold", tint = androidx.compose.ui.graphics.Color.White, size = 10.dp)
                        }
                    } else Text(marker!!, Modifier.width(20.dp), style = style.copy(color = style.color.takeOrElse(c.text)))
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
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.code).border(1.dp, c.border, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Text(code, style = PullsType.xs.copy(fontFamily = PullsType.mono, color = c.text), softWrap = false)
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
                    InlinePicture(src, piece.alt, piece.href?.let { href -> { runCatching { uri.openUri(href) } } })
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
 * An image at its own CSS size (`[&_img]:h-auto [&_img]:max-w-full`): loaded at its original
 * size, where one image pixel is one dp as one CSS px is, and shrunk only to fit the width.
 */
@Composable
private fun InlinePicture(src: String, alt: String, onClick: (() -> Unit)?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(src) { coil3.request.ImageRequest.Builder(context).data(src).size(coil3.size.Size.ORIGINAL).build() }
    val painter = coil3.compose.rememberAsyncImagePainter(request)
    val size = painter.intrinsicSize
    val known = size.isSpecified && size.width > 0f && size.height > 0f
    androidx.compose.foundation.layout.BoxWithConstraints {
        val maxW = maxWidth.value
        val w = if (known) minOf(size.width, maxW) else 0f
        val h = if (known) w * size.height / size.width else 0f
        androidx.compose.foundation.Image(
            painter, alt,
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
    val text = remember(node, c) {
        buildAnnotatedString { appendInline(node, c) }
    }
    if (text.isEmpty()) return
    Text(text, style = style.copy(color = style.color.takeOrElse(c.text)))
}

private fun androidx.compose.ui.graphics.Color.takeOrElse(other: androidx.compose.ui.graphics.Color) =
    if (this == androidx.compose.ui.graphics.Color.Unspecified) other else this

private fun AnnotatedString.Builder.appendInline(parent: Node, c: PullsColors) {
    var node = parent.firstChild
    while (node != null) {
        appendInlineOne(node, c)
        node = node.next
    }
}

private fun AnnotatedString.Builder.appendInlineOne(node: Node, c: PullsColors) {
    run {
        when (node) {
            is MdText -> append(node.literal)
            // `text-[max(0.85em,var(--pr-code-min))] px-1 py-0.5 rounded bg-code` (13px floor on phones).
            is Code -> withStyle(SpanStyle(fontFamily = PullsType.mono, background = c.code, fontSize = if (PullsType.wide) 0.85.em else 13.sp)) { append("\u2009${node.literal}\u2009") }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(node, c) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(node, c) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(node, c) }
            is Link -> withLink(LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) { appendInline(node, c) }
            is Image -> withStyle(SpanStyle(color = c.fade)) { append(node.title?.takeIf { it.isNotBlank() } ?: "[image]") }
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            is HtmlInline -> {
                val tag = node.literal
                if (tag.startsWith("<br", ignoreCase = true)) append('\n')
            }
            is TaskListItemMarker -> Unit
            else -> appendInline(node, c)
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
