package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
        is FencedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'))
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
                val task = item.firstChild?.firstChild as? TaskListItemMarker
                val marker = when {
                    task != null -> if (task.isChecked) "☑" else "☐"
                    list is OrderedList -> "${number++}."
                    else -> "•"
                }
                Row {
                    Text(marker, Modifier.width(20.dp), style = style.copy(color = c.fade))
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

@Composable
private fun TableView(table: TableBlock, style: TextStyle) {
    val c = LocalPullsColors.current
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, c.border)) {
        var section = table.firstChild
        while (section != null) {
            val head = section is TableHead
            if (section is TableHead || section is TableBody) {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) Row {
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) Box(Modifier.width(140.dp).border(1.dp, c.border).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Inline(cell, PullsType.xs.copy(color = c.text, fontWeight = if (head) FontWeight.SemiBold else null))
                            }
                            cell = cell.next
                        }
                    }
                    row = row.next
                }
            }
            section = section.next
        }
    }
}

@Composable
private fun Inline(node: Node, style: TextStyle) {
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
        when (node) {
            is MdText -> append(node.literal)
            is Code -> withStyle(SpanStyle(fontFamily = PullsType.mono, background = c.code)) { append(" ${node.literal} ") }
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
        node = node.next
    }
}

/** Text content of an HTML fragment: tags dropped, `<br>`/block ends as newlines, entities decoded. */
fun htmlText(html: String): String = html
    .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h\\d|tr)>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
