package com.octaviantocan.pulls.core.markdown

/** Semantic nodes consumed by Pully's existing renderer, with no parser or platform objects escaping. */
sealed class Node {
    private val nodes = mutableListOf<Node>()
    val children: List<Node> get() = nodes
    val firstChild: Node? get() = nodes.firstOrNull()
    var next: Node? = null
        private set

    internal fun append(child: Node) {
        val last = nodes.lastOrNull()
        if (last is Text && child is Text) {
            last.literal += child.literal
        } else {
            last?.next = child
            nodes += child
        }
    }

    internal fun prepend(child: Node) {
        child.next = nodes.firstOrNull()
        nodes.add(0, child)
    }

    internal fun linkifyAutolinks() {
        val original = nodes.toList()
        nodes.clear()
        for (node in original) {
            node.next = null
            if (node is Text) autolinkText(node.literal).forEach(::append) else append(node)
        }
    }
}

class Document : Node()
class Paragraph : Node()
class Heading(val level: Int) : Node()
class BulletList : Node()
class OrderedList(val markerStartNumber: Int) : Node()
class ListItem : Node()
class BlockQuote : Node()
class ThematicBreak : Node()
class Text(literal: String) : Node() {
    var literal: String = literal
        internal set
}
class Code(val literal: String) : Node()
class FencedCodeBlock(val literal: String, val info: String) : Node()
class IndentedCodeBlock(val literal: String) : Node()
class Emphasis : Node()
class StrongEmphasis : Node()
class Strikethrough : Node()
class Link(val destination: String, val title: String? = null) : Node()
class Image(val destination: String, val title: String? = null) : Node()
class SoftLineBreak : Node()
class HardLineBreak : Node()
class HtmlInline(val literal: String) : Node()
class HtmlBlock(val literal: String) : Node()
class TaskListItemMarker(val isChecked: Boolean) : Node()
class TableBlock : Node()
class TableHead : Node()
class TableBody : Node()
class TableRow : Node()
class TableCell(val alignment: Alignment? = null) : Node() {
    enum class Alignment { LEFT, CENTER, RIGHT }
}

/** Android retains its current CommonMark parser; Apple uses the portable GFM adapter. */
expect fun parseMarkdown(source: String): Document
