package com.octaviantocan.pulls.core.markdown

import org.intellij.markdown.MarkdownElementTypes as E
import org.intellij.markdown.MarkdownTokenTypes as T
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes as G
import org.intellij.markdown.flavours.gfm.GFMTokenTypes as GT
import org.intellij.markdown.parser.MarkdownParser

/** Adapts the inspected JetBrains GFM syntax tree, never reparsing Markdown with text patterns. */
fun parsePortableMarkdown(markdown: String): Document {
    val source = markdown.replace("\r\n", "\n").replace('\r', '\n').replace('\u0000', '\uFFFD')
    val root = MarkdownParser(PullyGfmFlavour()).buildMarkdownTreeFromString(source)
    val links = mutableMapOf<String, ASTNode>()
    fun collect(node: ASTNode) {
        if (node.type == E.LINK_DEFINITION) {
            node.children.firstOrNull { it.type == E.LINK_LABEL }?.let {
                val label = source.substring(it.startOffset, it.endOffset).removeSurrounding("[", "]")
                val normalized = normalizeLabel(label)
                if (normalized !in links) links[normalized] = node
            }
        } else node.children.forEach(::collect)
    }
    collect(root)
    return PortableTree(source, links).document(root)
}

private class PortableTree(private val source: String, private val links: Map<String, ASTNode>) {
    private fun ASTNode.text(): String = source.substring(startOffset, endOffset)
    fun document(root: ASTNode) = Document().also { blocks(it, root.children) }

    private fun blocks(parent: Node, nodes: List<ASTNode>) {
        for (node in nodes) {
            val result: Node? = when (node.type) {
                E.PARAGRAPH -> Paragraph().also { inline(it, node.children, trim = true) }
                E.ATX_1, E.ATX_2, E.ATX_3, E.ATX_4, E.ATX_5, E.ATX_6, E.SETEXT_1, E.SETEXT_2 -> {
                    val level = when (node.type) {
                        E.ATX_1, E.SETEXT_1 -> 1
                        E.ATX_2, E.SETEXT_2 -> 2
                        E.ATX_3 -> 3
                        E.ATX_4 -> 4
                        E.ATX_5 -> 5
                        else -> 6
                    }
                    Heading(level).also { heading ->
                        node.children.firstOrNull { it.type == T.ATX_CONTENT || it.type == T.SETEXT_CONTENT }
                            ?.let { inline(heading, it.children, trim = true) }
                    }
                }
                E.UNORDERED_LIST -> BulletList().also { blocks(it, node.children) }
                E.ORDERED_LIST -> OrderedList(node.children.firstOrNull { it.type == E.LIST_ITEM }?.children
                    ?.firstOrNull { it.type == T.LIST_NUMBER }?.text()?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 1)
                    .also { blocks(it, node.children) }
                E.LIST_ITEM -> ListItem().also { item ->
                    val marker = node.children.firstOrNull { it.type == GT.CHECK_BOX }
                    blocks(item, node.children)
                    if (marker != null) item.prepend(TaskListItemMarker(marker.text().getOrNull(1)?.lowercaseChar() == 'x'))
                }
                E.BLOCK_QUOTE -> BlockQuote().also { blocks(it, node.children) }
                T.HORIZONTAL_RULE -> ThematicBreak()
                E.CODE_FENCE -> FencedCodeBlock(fencedBody(node), node.children.firstOrNull { it.type == T.FENCE_LANG }?.text()?.let(::unescapeMarkdown).orEmpty())
                E.CODE_BLOCK -> IndentedCodeBlock(buildString {
                    node.children.forEach { child ->
                        when (child.type) {
                            T.CODE_LINE -> append(removeIndent(child.text(), 4))
                            T.EOL -> append('\n')
                        }
                    }
                }.withFinalNewline())
                E.HTML_BLOCK -> HtmlBlock(buildString {
                    node.children.filter { it.type == T.HTML_BLOCK_CONTENT || it.type == T.EOL }.forEach { append(it.text()) }
                })
                G.TABLE -> table(node)
                G.BLOCK_MATH -> Paragraph().also { it.append(Text(node.text())) }
                else -> null // Definitions and structural whitespace are not rendered by CommonMark.
            }
            if (result != null) parent.append(result)
        }
    }

    private fun fencedBody(node: ASTNode): String {
        val indent = node.text().takeWhile { it == ' ' }.length
        var body = false
        return buildString {
            for (child in node.children) {
                if (!body) {
                    if (child.type == T.EOL) body = true
                    continue
                }
                when (child.type) {
                    T.CODE_FENCE_CONTENT -> append(removeIndent(child.text(), indent))
                    T.EOL -> append('\n')
                }
            }
        }.withFinalNewline()
    }

    private fun table(node: ASTNode): TableBlock {
        val separator = node.children.first { it.type == GT.TABLE_SEPARATOR }.text().trim()
            .removePrefix("|").removeSuffix("|")
        val alignment = separator.split('|').map { cell ->
            val value = cell.trim()
            when {
                value.startsWith(':') && value.endsWith(':') -> TableCell.Alignment.CENTER
                value.startsWith(':') -> TableCell.Alignment.LEFT
                value.endsWith(':') -> TableCell.Alignment.RIGHT
                else -> null
            }
        }
        val table = TableBlock()
        val head = TableHead()
        val body = TableBody()
        node.children.filter { it.type == G.HEADER || it.type == G.ROW }.forEach { row ->
            val converted = TableRow()
            val cells = row.children.filter { it.type == GT.CELL }
            alignment.forEachIndexed { index, align ->
                converted.append(TableCell(align).also { cell -> cells.getOrNull(index)?.let { inline(cell, it.children, trim = true, tableCell = true) } })
            }
            if (row.type == G.HEADER) head.append(converted) else body.append(converted)
        }
        table.append(head)
        if (body.firstChild != null) table.append(body)
        return table
    }

    private fun inline(parent: Node, input: List<ASTNode>, trim: Boolean = false, tableCell: Boolean = false, autolinks: Boolean = true) {
        val trimmed = if (trim) input.dropWhile { it.type == T.WHITE_SPACE }.dropLastWhile { it.type == T.WHITE_SPACE } else input
        // The syntax tree groups potential reference pairs before definitions are known.
        // Resolve their parsed labels in order so an undefined pair leaves the next label usable.
        val collapsedEnds = mutableMapOf<ASTNode, Int>()
        val nodes = trimmed.flatMap {
            if (it.type == E.SHORT_REFERENCE_LINK && it.children.size > 1) {
                it.children.firstOrNull { child -> child.type == E.LINK_LABEL }?.let { label -> collapsedEnds[label] = it.endOffset }
            }
            if (it.type == E.FULL_REFERENCE_LINK || it.type == E.SHORT_REFERENCE_LINK) it.children else listOf(it)
        }
        var index = 0
        while (index < nodes.size) {
            val node = nodes[index]
            if (node.type == E.LINK_TEXT || node.type == E.LINK_LABEL) {
                var next = index + 1
                while (nodes.getOrNull(next)?.type == T.WHITE_SPACE) next++
                val target = nodes.getOrNull(next)?.takeIf { it.type == E.LINK_TEXT || it.type == E.LINK_LABEL }
                val paired = target?.let { definition(it) ?: if (it.text() == "[]") definition(node) else null }
                val own = if (paired == null && node.type == E.LINK_LABEL) definition(node) else null
                if (paired != null || own != null) {
                    parent.append(resolvedLink(node, checkNotNull(paired ?: own), image = false))
                    index = if (paired != null) next + 1 else index + 1
                    collapsedEnds[node]?.let { end -> while (nodes.getOrNull(index)?.endOffset?.let { it <= end } == true) index++ }
                } else {
                    parent.append(Text("["))
                    inline(parent, node.children.drop(1).dropLast(1), tableCell = tableCell, autolinks = false)
                    parent.append(Text("]"))
                    index++
                }
                continue
            }
            if (node.type == E.IMAGE) {
                val candidate = node.children.firstOrNull { it.type == E.INLINE_LINK || it.type == E.FULL_REFERENCE_LINK || it.type == E.SHORT_REFERENCE_LINK }
                val image = candidate?.let { link(it, image = true) } as? Image
                if (image != null) parent.append(image)
                else inline(parent, node.children, tableCell = tableCell, autolinks = false)
                index++
                continue
            }
            if (node.isLiteral()) {
                var end = index + 1
                while (nodes.getOrNull(end)?.isLiteral() == true) end++
                // GFM may split a URL immediately before an entity's final semicolon.
                // Decode the complete text run once, before the existing autolink scanners.
                parent.append(Text(unescapeMarkdown(nodes.subList(index, end).joinToString("") { it.text() })))
                index = end
                continue
            }
            val converted: Node? = when (node.type) {
                E.EMPH -> Emphasis().also { inline(it, node.children.dropWhile { it.type == T.EMPH }.dropLastWhile { it.type == T.EMPH }, tableCell = tableCell, autolinks = autolinks) }
                E.STRONG -> StrongEmphasis().also { inline(it, node.children.dropWhile { it.type == T.EMPH }.dropLastWhile { it.type == T.EMPH }, tableCell = tableCell, autolinks = autolinks) }
                G.STRIKETHROUGH -> Strikethrough().also { inline(it, node.children.dropWhile { it.type == GT.TILDE }.dropLastWhile { it.type == GT.TILDE }, tableCell = tableCell, autolinks = autolinks) }
                E.CODE_SPAN -> {
                    var content = node.children.drop(1).dropLast(1).joinToString("") { it.text() }.replace('\n', ' ')
                    if (tableCell) content = content.replace("\\|", "|")
                    if (content.startsWith(' ') && content.endsWith(' ') && content.any { it != ' ' }) content = content.drop(1).dropLast(1)
                    Code(content)
                }
                E.INLINE_LINK, E.FULL_REFERENCE_LINK, E.SHORT_REFERENCE_LINK -> link(node, image = false)
                E.AUTOLINK -> {
                    val text = node.text().removeSurrounding("<", ">")
                    Link(if ('@' in text && !text.contains(':')) "mailto:$text" else text).also { it.append(Text(text)) }
                }
                T.HARD_LINE_BREAK -> HardLineBreak()
                T.EOL -> if (nodes.getOrNull(index - 1)?.type == T.HARD_LINE_BREAK) null else SoftLineBreak()
                T.HTML_TAG -> HtmlInline(node.text())
                T.BLOCK_QUOTE -> null
                T.WHITE_SPACE -> if (nodes.getOrNull(index - 1)?.type in setOf(T.EOL, T.BLOCK_QUOTE) || nodes.getOrNull(index + 1)?.type == T.EOL) null else Text(node.text())
                G.INLINE_MATH -> Text(node.text()) // Existing Android Markdown does not opt into math syntax.
                else -> if (node.children.isNotEmpty()) null else Text(unescapeMarkdown(node.text()))
            }
            if (converted != null) parent.append(converted)
            index++
        }
        if (autolinks) parent.linkifyAutolinks()
    }

    private fun link(node: ASTNode, image: Boolean): Node {
        val reference = node.type != E.INLINE_LINK
        val label = node.children.firstOrNull { it.type == E.LINK_TEXT } ?: node.children.firstOrNull { it.type == E.LINK_LABEL }
        val definition = if (reference) node.children.firstOrNull { it.type == E.LINK_LABEL }?.let { definition(it) ?: if (it.text() == "[]" && label != null) definition(label) else null } else node
        if (definition == null || label == null) return Text(unescapeMarkdown(node.text()))
        return resolvedLink(label, definition, image)
    }

    private fun definition(label: ASTNode): ASTNode? = links[normalizeLabel(label.text().removeSurrounding("[", "]"))]

    private fun ASTNode.isLiteral(): Boolean = type == GT.GFM_AUTOLINK || children.isEmpty() && when (type) {
        T.WHITE_SPACE, T.EOL, T.HARD_LINE_BREAK, T.HTML_TAG, T.BLOCK_QUOTE, E.AUTOLINK -> false
        else -> true
    }

    private fun resolvedLink(label: ASTNode, definition: ASTNode, image: Boolean): Node {
        val destination = definition.children.firstOrNull { it.type == E.LINK_DESTINATION }?.text()?.removeSurrounding("<", ">")?.let(::unescapeMarkdown).orEmpty()
        val title = definition.children.firstOrNull { it.type == E.LINK_TITLE }?.text()?.let { unescapeMarkdown(it.drop(1).dropLast(1)) }
        return (if (image) Image(destination, title) else Link(destination, title)).also {
            inline(it, label.children.drop(1).dropLast(1), autolinks = false)
        }
    }
}

/** CommonMark's locale-independent reference-label folding, including sharp S and first-definition wins. */
// raw-value-ok: Reference-label Unicode case folding is parser matching, never uppercase UI text.
private fun normalizeLabel(label: String): String = label.trim { it <= ' ' }.lowercase().uppercase().replace(LABEL_SPACE, " ")
private val LABEL_SPACE = Regex("[ \\t\\r\\n]+")

private fun String.withFinalNewline() = if (isNotEmpty() && !endsWith('\n')) this + "\n" else this

private fun removeIndent(text: String, count: Int): String {
    var at = 0
    var columns = 0
    while (at < text.length && columns < count) {
        when (text[at]) {
            ' ' -> columns++
            '\t' -> columns += 4 - columns % 4
            else -> break
        }
        at++
    }
    return " ".repeat((columns - count).coerceAtLeast(0)) + text.substring(at)
}
