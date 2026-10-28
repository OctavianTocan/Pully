package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.design.DETAILS_INFO

// --- PrHtml: GitHub's sanitized bodyHTML as Markdown for the app's Markdown renderer ---

private sealed interface HtmlNode
private data class HtmlText(val text: String) : HtmlNode
private class HtmlElement(val tag: String, val attrs: Map<String, String>, val children: MutableList<HtmlNode> = mutableListOf()) : HtmlNode

private val VOID_TAGS = setOf("br", "img", "input", "hr", "meta", "source", "wbr", "col", "area", "link")
private val BLOCK_TAGS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "pre", "blockquote", "table", "hr", "details", "summary", "section", "article", "markdown-accessiblity-table", "dl")
private val TOKEN = Regex("""<!--[\s\S]*?-->|<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^\s=>/]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*(/?)>""")
private val ATTR = Regex("""([^\s=>/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?""")

private fun parseHtml(html: String): HtmlElement {
    val root = HtmlElement("#root", emptyMap())
    val stack = ArrayDeque<HtmlElement>().apply { addLast(root) }
    var at = 0
    for (match in TOKEN.findAll(html)) {
        if (match.range.first > at) stack.last().children += HtmlText(html.substring(at, match.range.first))
        at = match.range.last + 1
        if (match.value.startsWith("<!--")) continue
        val closing = match.groupValues[1] == "/"
        val tag = match.groupValues[2].lowercase()
        if (closing) {
            val index = stack.indexOfLast { it.tag == tag }
            if (index > 0) while (stack.size > index) stack.removeLast()
            continue
        }
        val attrs = ATTR.findAll(match.groupValues[3]).associate { a ->
            a.groupValues[1].lowercase() to decodeEntities(a.groupValues[2].ifEmpty { a.groupValues[3].ifEmpty { a.groupValues[4] } })
        }
        val element = HtmlElement(tag, attrs)
        stack.last().children += element
        if (tag !in VOID_TAGS && match.groupValues[4] != "/") stack.addLast(element)
    }
    if (at < html.length) stack.last().children += HtmlText(html.substring(at))
    return root
}

private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–", "rarr" to "→", "larr" to "←", "middot" to "·", "copy" to "©", "times" to "×")

fun decodeEntities(text: String): String = Regex("""&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z]+);""").replace(text) { m ->
    val body = m.groupValues[1]
    when {
        body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let(::htmlCodePoint) ?: m.value
        body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::htmlCodePoint) ?: m.value
        else -> NAMED_ENTITIES[body] ?: m.value
    }
}

private fun escapeMarkdown(text: String): String = buildString {
    for (ch in text) {
        if (ch in "\\`*_[]<>&~|#") append('\\')
        append(ch)
    }
}

private class MdContext(val inLink: Boolean = false, val inTable: Boolean = false)

private fun textContent(node: HtmlNode): String = when (node) {
    is HtmlText -> decodeEntities(node.text)
    is HtmlElement -> if (node.tag == "br") "\n" else node.children.joinToString("") { textContent(it) }
}

private fun inline(node: HtmlNode, ctx: MdContext): String = when (node) {
    is HtmlText -> escapeMarkdown(decodeEntities(node.text).replace(Regex("\\s+"), " "))
    // Words only a screen reader gets, such as the "Loading" of a diagram's spinner GitHub hides once it draws.
    is HtmlElement -> if (hidden(node)) "" else when (node.tag) {
        "br" -> if (ctx.inTable) " " else "\\\n"
        "strong", "b" -> wrap("**", node, ctx)
        "em", "i" -> wrap("*", node, ctx)
        "del", "s", "strike" -> wrap("~~", node, ctx)
        "code", "kbd", "samp", "tt" -> textContent(node).replace(Regex("\\s+"), " ").let { code ->
            if (code.isBlank()) "" else { val fence = if ('`' in code) "``" else "`"; "$fence$code$fence" }
        }
        "a" -> {
            val href = node.attrs["href"].orEmpty()
            val label = node.children.joinToString("") { inline(it, MdContext(inLink = true, inTable = ctx.inTable)) }.trim()
            // `[&_.user-mention]:font-semibold [&_.user-mention]:no-underline`
            if ("user-mention" in node.attrs["class"].orEmpty()) "**$label**"
            else if (ctx.inLink || href.isBlank() || href.startsWith("#") || label.isBlank()) label else "[$label](<${href.replace(">", "%3E")}>)"
        }
        "picture" -> {
            // `<picture><source media="(prefers-color-scheme: dark)" srcset=…><img …>`: keep both, the renderer picks by theme.
            val img = node.children.filterIsInstance<HtmlElement>().firstOrNull { it.tag == "img" }
            val dark = node.children.filterIsInstance<HtmlElement>().firstOrNull { it.tag == "source" && "dark" in it.attrs["media"].orEmpty() }?.attrs?.get("srcset")?.substringBefore(' ')
            val src = img?.attrs?.get("src").orEmpty()
            val alt = escapeMarkdown(img?.attrs?.get("alt").orEmpty().trim())
            if (src.isBlank()) alt else "![$alt](<$src>${if (dark != null) " \"dark:$dark\"" else ""})"
        }
        "img" -> {
            val alt = escapeMarkdown(node.attrs["alt"].orEmpty().trim())
            val src = node.attrs["src"].orEmpty()
            if (src.isBlank()) alt else "![$alt](<$src>)"
        }
        "input" -> if (node.attrs["type"] == "checkbox") (if ("checked" in node.attrs) "[x]" else "[ ]") else ""
        "clipboard-copy", "svg", "script", "style", "template" -> ""
        else -> node.children.joinToString("") { inline(it, ctx) }
    }
}

private fun wrap(marker: String, node: HtmlElement, ctx: MdContext): String {
    val inner = node.children.joinToString("") { inline(it, ctx) }
    val trimmed = inner.trim()
    if (trimmed.isEmpty()) return inner
    val lead = if (inner.startsWith(" ")) " " else ""
    val trail = if (inner.endsWith(" ")) " " else ""
    return "$lead$marker$trimmed$marker$trail"
}

private fun isBlock(node: HtmlNode) = node is HtmlElement && (node.tag in BLOCK_TAGS || node.tag == "li" || node.tag == "tr")

/** The blocks of [children], each a Markdown string, in order. */
private fun blocks(children: List<HtmlNode>): List<String> {
    val out = mutableListOf<String>()
    val run = StringBuilder()
    fun flush() {
        val text = run.toString().replace(Regex(" *\\\\\n *"), "\\\\\n").trim().trimEnd('\\').trim()
        if (text.isNotEmpty()) out += text
        run.clear()
    }
    for (child in children) {
        if (!isBlock(child)) { run.append(inline(child, MdContext())); continue }
        flush()
        out += block(child as HtmlElement)
    }
    flush()
    return out.filter { it.isNotBlank() }
}

/** An element GitHub draws for nobody to see: screen-reader words and a diagram's loading spinner. */
private fun hidden(node: HtmlElement): Boolean = node.attrs["class"].orEmpty().split(' ').any { it == "sr-only" || it == "js-render-enrichment-loader" }

/** Markdown blocks as a quote: each line behind `> `. */
private fun quoted(body: String): String = body.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }

private fun block(node: HtmlElement): List<String> = if (hidden(node)) emptyList() else when (node.tag) {
    // A GitHub alert (`> [!NOTE]`) stays a quote with its title in bold, as GitHub sets it apart, not a loose "Note" line.
    "div" -> if ("markdown-alert" !in node.attrs["class"].orEmpty()) blocks(node.children) else {
        val title = node.children.filterIsInstance<HtmlElement>().firstOrNull { "markdown-alert-title" in it.attrs["class"].orEmpty() }
        val label = title?.let { textContent(it) }?.trim().orEmpty()
        val body = blocks(node.children.filter { it !== title }).joinToString("\n\n")
        listOf(quoted(listOfNotNull(label.ifEmpty { null }?.let { "**${escapeMarkdown(it)}**" }, body.ifBlank { null }).joinToString("\n\n")))
    }
    "h1", "h2", "h3", "h4", "h5", "h6" -> listOf("#".repeat(node.tag[1].digitToInt()) + " " + node.children.joinToString("") { inline(it, MdContext()) }.trim()).filter { it.trimEnd('#', ' ').isNotEmpty() }
    "hr" -> listOf("---")
    "pre" -> {
        val code = textContent(node).trimEnd('\n')
        val fence = if ("```" in code) "~~~" else "```"
        // GitHub keeps a diagram's language on its `<pre lang>`, so a Mermaid diagram is drawn rather than shown as code.
        val language = node.attrs["lang"]?.takeIf { Regex("[A-Za-z0-9_+-]+").matches(it) }.orEmpty()
        listOf("$fence$language\n$code\n$fence")
    }
    "ul", "ol" -> listOf(list(node))
    "blockquote" -> listOf(quoted(blocks(node.children).joinToString("\n\n")))
    "table" -> listOfNotNull(table(node))
    "details" -> {
        // A closed `<details>`, as GitHub renders it: [DETAILS_INFO] tells the Markdown renderer to draw a disclosure.
        val summary = node.children.filterIsInstance<HtmlElement>().firstOrNull { it.tag == "summary" }
        val title = summary?.let { textContent(it) }?.trim()?.replace("\n", " ").orEmpty().ifEmpty { "Details" }
        val body = blocks(node.children.filter { it !== summary }).joinToString("\n\n")
        val longest = Regex("`+").findAll(body).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        listOf("$fence$DETAILS_INFO $title\n$body\n$fence")
    }
    "summary" -> listOf("**" + node.children.joinToString("") { inline(it, MdContext()) }.trim() + "**")
    else -> blocks(node.children)
}

private fun list(node: HtmlElement): String {
    var number = node.attrs["start"]?.toIntOrNull() ?: 1
    val items = node.children.filterIsInstance<HtmlElement>().filter { it.tag == "li" }
    return items.joinToString("\n") { item ->
        val marker = if (node.tag == "ol") "${number++}. " else "- "
        val indent = " ".repeat(marker.length)
        val body = blocks(item.children).joinToString("\n\n").ifEmpty { " " }
        body.lines().mapIndexed { i, line -> if (i == 0) marker + line else if (line.isEmpty()) "" else indent + line }.joinToString("\n")
    }
}

private fun table(node: HtmlElement): String? {
    val rows = mutableListOf<List<String>>()
    fun collect(element: HtmlElement) {
        for (child in element.children.filterIsInstance<HtmlElement>()) {
            if (child.tag == "tr") rows += child.children.filterIsInstance<HtmlElement>().filter { it.tag == "td" || it.tag == "th" }
                .map { cell -> cell.children.joinToString("") { inline(it, MdContext(inTable = true)) }.trim().replace("\n", " ") }
            else collect(child)
        }
    }
    collect(node)
    if (rows.isEmpty()) return null
    val width = rows.maxOf { it.size }.coerceAtLeast(1)
    fun row(cells: List<String>) = "| " + (0 until width).joinToString(" | ") { cells.getOrElse(it) { "" } } + " |"
    return (listOf(row(rows.first()), "|" + " --- |".repeat(width)) + rows.drop(1).map(::row)).joinToString("\n")
}

/** GitHub's rendered (and sanitized) `bodyHTML` as GitHub-flavored Markdown with the same structure. */
fun htmlToMarkdown(html: String): String = blocks(parseHtml(html).children).joinToString("\n\n")

/** Invalid numeric references stay inert literal text instead of throwing while rendering a remote body. */
private fun htmlCodePoint(code: Int): String? = when {
    code !in 0..0x10FFFF -> null
    code <= 0xFFFF -> code.toChar().toString()
    else -> {
        val value = code - 0x10000
        "${(0xD800 + (value shr 10)).toChar()}${(0xDC00 + (value and 0x3FF)).toChar()}"
    }
}

/**
 * An account that is a tool, not a person, as GitHub says it: the server marks an app's account ([PrActor.bot], from its
 * GraphQL type), and a login ending in "[bot]" is one too. No name is ever looked up in a list, so a new app is a bot
 * from its first comment and nobody is one for their name ("without banning names", recording of 16 Oct 12:10, 16:02 to
 * 16:21; N31). A bot's comments start folded (rule 91).
 */
fun isBot(actor: PrActor?): Boolean = actor != null && (actor.bot || actor.login.endsWith("[bot]", ignoreCase = true))

/** The first line of a comment as plain words, for the dimmed line under a folded comment: no Markdown marks, links as their text. */
fun commentPreview(html: String): String =
    htmlToMarkdown(html).lineSequence()
        .map { line ->
            line.trim().trimStart('#', '>', '-', '*', '+', ' ')
                .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
                .replace(Regex("""\\(.)"""), "$1")
                .replace(Regex("""[`*_~]"""), "")
                .trim()
        }
        .firstOrNull { it.isNotEmpty() }.orEmpty()

/** Which part of the conversation shows: all of it, what people wrote, or what bots wrote (J19). */
enum class Talk(val label: String) { All("All"), People("People"), Bots("Bots") }

/** Whether [item] belongs under [talk]: an event (a label, a push of the title) only under All. */
fun inTalk(item: PrTimelineItem, talk: Talk): Boolean = when (talk) {
    Talk.All -> true
    Talk.People -> item !is PrTimelineItem.Event && !isBot(item.actor)
    Talk.Bots -> item !is PrTimelineItem.Event && isBot(item.actor)
}
