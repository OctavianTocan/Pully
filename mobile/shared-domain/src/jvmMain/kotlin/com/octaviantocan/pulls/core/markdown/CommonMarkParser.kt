package com.octaviantocan.pulls.core.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser

private val parser = Parser.builder().extensions(listOf(
    TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create(), TaskListItemsExtension.create(),
)).build()

actual fun parseMarkdown(source: String): Document = copyNode(parser.parse(source)) as Document

private fun copyNode(source: org.commonmark.node.Node): Node {
    val node: Node = when (source) {
        is org.commonmark.node.Document -> Document()
        is org.commonmark.node.Paragraph -> Paragraph()
        is org.commonmark.node.Heading -> Heading(source.level)
        is org.commonmark.node.BulletList -> BulletList()
        is org.commonmark.node.OrderedList -> OrderedList(source.markerStartNumber)
        is org.commonmark.node.ListItem -> ListItem()
        is org.commonmark.node.BlockQuote -> BlockQuote()
        is org.commonmark.node.ThematicBreak -> ThematicBreak()
        is org.commonmark.node.Text -> Text(source.literal)
        is org.commonmark.node.Code -> Code(source.literal)
        is org.commonmark.node.FencedCodeBlock -> FencedCodeBlock(source.literal, source.info.orEmpty())
        is org.commonmark.node.IndentedCodeBlock -> IndentedCodeBlock(source.literal)
        is org.commonmark.node.Emphasis -> Emphasis()
        is org.commonmark.node.StrongEmphasis -> StrongEmphasis()
        is org.commonmark.ext.gfm.strikethrough.Strikethrough -> Strikethrough()
        is org.commonmark.node.Link -> Link(source.destination, source.title)
        is org.commonmark.node.Image -> Image(source.destination, source.title)
        is org.commonmark.node.SoftLineBreak -> SoftLineBreak()
        is org.commonmark.node.HardLineBreak -> HardLineBreak()
        is org.commonmark.node.HtmlInline -> HtmlInline(source.literal)
        is org.commonmark.node.HtmlBlock -> HtmlBlock(source.literal)
        is org.commonmark.ext.task.list.items.TaskListItemMarker -> TaskListItemMarker(source.isChecked)
        is org.commonmark.ext.gfm.tables.TableBlock -> TableBlock()
        is org.commonmark.ext.gfm.tables.TableHead -> TableHead()
        is org.commonmark.ext.gfm.tables.TableBody -> TableBody()
        is org.commonmark.ext.gfm.tables.TableRow -> TableRow()
        is org.commonmark.ext.gfm.tables.TableCell -> TableCell(source.alignment?.let { TableCell.Alignment.valueOf(it.name) })
        is org.commonmark.node.LinkReferenceDefinition -> return Document()
        else -> error("Unsupported CommonMark node: ${source::class.simpleName}")
    }
    var child = source.firstChild
    while (child != null) {
        if (child !is org.commonmark.node.LinkReferenceDefinition) node.append(copyNode(child))
        child = child.next
    }
    return node
}
