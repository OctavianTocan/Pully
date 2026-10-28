package com.octaviantocan.pulls.core.markdown

import com.octaviantocan.pulls.ui.review.decodeEntities
import com.octaviantocan.pulls.ui.review.htmlToMarkdown

/**
 * [document] with the raw HTML GitHub Markdown carries turned into the nodes it stands for, as GitHub shows it: an HTML
 * block becomes the Markdown it holds ([htmlToMarkdown]), a `<details>` opened and closed around Markdown blocks becomes a
 * [DetailsBlock] of them, and inline tags in running text (`<img>`, `<picture>`, `<a href>`, `<b>`, `<code>`, `<br>`, …)
 * become images, links, emphasis, code and breaks. Comments and tags with no meaning here drop away, their text kept. An
 * alert (`> [!NOTE]`) keeps its quote with its title in bold, as the description's own HTML gives it.
 * Review comments and bots write this HTML (Devin's badge, "Learn more" folds); without it the app showed a stray
 * "Learn more" over text that never folded and dropped every badge.
 */
fun liftHtml(document: Document): Document {
    lift(document)
    return document
}

private fun lift(parent: Node) {
    if (parent is BlockQuote) liftAlert(parent)
    if (parent.children.any { it is HtmlInline }) {
        parent.replaceChildren(liftInline(parent.children))
    }
    if (parent.children.any { it is HtmlBlock }) {
        parent.replaceChildren(liftBlocks(parent.children))
    }
    parent.children.forEach(::lift)
}

private val DETAILS_TAG = Regex("""<(/?)details\b[^>]*>""", RegexOption.IGNORE_CASE)
private val SUMMARY = Regex("""<summary\b[^>]*>([\s\S]*?)</summary\s*>""", RegexOption.IGNORE_CASE)
private val COMMENT = Regex("""<!--[\s\S]*?-->""")

/** The blocks of [nodes], each HTML block read as Markdown and each `<details>` around blocks folded into one. */
private fun liftBlocks(nodes: List<Node>): List<Node> {
    val out = mutableListOf<Node>()
    var i = 0
    while (i < nodes.size) {
        val node = nodes[i]
        if (node !is HtmlBlock) { out += node; i++; continue }
        val html = node.literal.replace(COMMENT, "")
        val opening = unclosedDetails(html)
        if (opening == null) { out += htmlBlocks(html); i++; continue }
        // Everything before the `<details>` that stays open is ordinary HTML.
        out += htmlBlocks(html.substring(0, opening.range.first))
        var head = html.substring(opening.range.last + 1)
        var summary = SUMMARY.find(head)
        val inside = mutableListOf<Node>()
        var after = ""
        var depth = 1
        var j = i + 1
        while (j < nodes.size && depth > 0) {
            val next = nodes[j]
            if (next is HtmlBlock) {
                val literal = next.literal.replace(COMMENT, "")
                if (summary == null && inside.isEmpty() && head.isBlank()) {
                    summary = SUMMARY.find(literal)
                    if (summary != null) { head = literal; j++; continue }
                }
                var cut = -1
                for (tag in DETAILS_TAG.findAll(literal)) {
                    depth += if (tag.groupValues[1] == "/") -1 else 1
                    if (depth == 0) { cut = tag.range.first; after = literal.substring(tag.range.last + 1); break }
                }
                if (cut >= 0) { if (literal.substring(0, cut).isNotBlank()) inside += HtmlBlock(literal.substring(0, cut)); j++; break }
            }
            inside += next
            j++
        }
        val title = summary?.let { textOf(it.groupValues[1]) }?.ifBlank { null } ?: "Details"
        val rest = if (summary != null) head.removeRange(summary.range) else head
        val details = DetailsBlock(title, Regex("""\bopen\b""", RegexOption.IGNORE_CASE).containsMatchIn(opening.value))
        val body = buildList {
            if (rest.isNotBlank()) add(HtmlBlock(rest))
            addAll(inside)
        }
        details.replaceChildren(body)
        out += details
        out += htmlBlocks(after)
        i = j
    }
    return out
}

/** The `<details>` tag in [html] that is still open at its end, or null when every one closes. */
private fun unclosedDetails(html: String): MatchResult? {
    val open = ArrayDeque<MatchResult>()
    for (tag in DETAILS_TAG.findAll(html)) {
        if (tag.groupValues[1] == "/") open.removeLastOrNull() else open.addLast(tag)
    }
    return open.firstOrNull()
}

/** The Markdown blocks an HTML fragment stands for; none when it holds nothing to show. */
private fun htmlBlocks(html: String): List<Node> {
    if (html.isBlank()) return emptyList()
    val markdown = htmlToMarkdown(html)
    if (markdown.isBlank()) return emptyList()
    return parseMarkdown(markdown).children.toList()
}

/** The words of an HTML fragment, tags dropped. */
private fun textOf(html: String): String = decodeEntities(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

private val TAG = Regex("""^<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*?)(/?)>$""", RegexOption.DOT_MATCHES_ALL)
private val ATTR = Regex("""([^\s=>/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?""")

private fun attrs(text: String): Map<String, String> = ATTR.findAll(text).associate { a ->
    a.groupValues[1].lowercase() to decodeEntities(a.groupValues[2].ifEmpty { a.groupValues[3].ifEmpty { a.groupValues[4] } })
}

/** Inline [nodes] with their HTML tags made into the nodes they mean. */
private fun liftInline(nodes: List<Node>): List<Node> {
    // Each open tag is a node collecting what follows until its close; the root collects the rest.
    class Open(val tag: String, val node: Node?, val children: MutableList<Node> = mutableListOf())
    val stack = ArrayDeque<Open>().apply { addLast(Open("", null)) }
    var dark: String? = null
    fun close(top: Open) {
        val done = stack.removeLast()
        val node = done.node
        when {
            node is Code -> stack.last().children += Code(done.children.joinToString("") { plain(it) })
            node != null -> { node.replaceChildren(done.children); stack.last().children += node }
            else -> stack.last().children.addAll(done.children)
        }
        check(done === top)
    }
    for (child in nodes) {
        if (child !is HtmlInline) { stack.last().children += child; continue }
        val match = TAG.matchEntire(child.literal.trim()) ?: continue
        val closing = match.groupValues[1] == "/"
        val tag = match.groupValues[2].lowercase()
        val a = attrs(match.groupValues[3])
        if (closing) {
            val at = stack.indexOfLast { it.tag == tag }
            if (at > 0) while (stack.size > at) close(stack.last())
            if (tag == "picture") dark = null
            continue
        }
        when (tag) {
            "br" -> stack.last().children += HardLineBreak()
            "img" -> a["src"]?.takeIf { it.isNotBlank() }?.let { src ->
                stack.last().children += Image(src, dark?.let { "dark:$it" }).also { image ->
                    a["alt"]?.takeIf { it.isNotBlank() }?.let { image.replaceChildren(listOf(Text(it))) }
                }
            }
            "source" -> if ("dark" in a["media"].orEmpty()) dark = a["srcset"]?.substringBefore(' ')
            else -> {
                if (match.groupValues[4] == "/") continue
                val node: Node? = when (tag) {
                    "b", "strong" -> StrongEmphasis()
                    "i", "em" -> Emphasis()
                    "s", "del", "strike" -> Strikethrough()
                    "code", "kbd", "tt", "samp" -> Code("")
                    "a" -> a["href"]?.takeIf { it.isNotBlank() && !it.startsWith("#") }?.let { Link(it) }
                    else -> null
                }
                stack.addLast(Open(tag, node))
            }
        }
    }
    while (stack.size > 1) close(stack.last())
    return stack.single().children
}

/** The plain text inside an inline node. */
private fun plain(node: Node): String = when (node) {
    is Text -> node.literal
    is Code -> node.literal
    is SoftLineBreak, is HardLineBreak -> " "
    else -> node.children.joinToString("") { plain(it) }
}

private val ALERT = Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\s*""", RegexOption.IGNORE_CASE)
private val ALERT_TITLES = mapOf("note" to "Note", "tip" to "Tip", "important" to "Important", "warning" to "Warning", "caution" to "Caution")

/** `> [!NOTE]` as GitHub writes the alert: the marker becomes its title, "Note", in bold on its own line. */
private fun liftAlert(quote: BlockQuote) {
    val paragraph = quote.firstChild as? Paragraph ?: return
    val marker = paragraph.firstChild as? Text ?: return
    val match = ALERT.find(marker.literal) ?: return
    val title = StrongEmphasis().also { it.replaceChildren(listOf(Text(ALERT_TITLES.getValue(match.groupValues[1].lowercase())))) }
    val rest = marker.literal.substring(match.range.last + 1)
    val after = paragraph.children.drop(1).let { if (it.firstOrNull() is SoftLineBreak) it.drop(1) else it }
    paragraph.replaceChildren(listOfNotNull(title, HardLineBreak().takeIf { rest.isNotEmpty() || after.isNotEmpty() }, rest.ifEmpty { null }?.let(::Text)) + after)
}
