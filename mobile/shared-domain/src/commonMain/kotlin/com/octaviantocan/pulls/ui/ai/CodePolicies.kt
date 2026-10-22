package com.octaviantocan.pulls.ui.ai

/** The file extension the highlighter knows a fence language by, or null for a language it does not colour. */
fun fenceExtension(lang: String): String? = when (lang.lowercase()) {
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


/** The steps of a call written as a chain (`a → b`, `a -> b`, `a => b`), or the call alone. */
fun callChain(name: String): List<String> = name.split(CHAIN).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(name) }

/** An arrow between two steps of a call chain, with a space on each side, so `a->b` in code stays one name. */
private val CHAIN = Regex("""\s+(?:->|→|=>|⟶|⇒)\s+""")

/** The changed file a tree row's path names: the whole path, or the end of it when the tree starts above the repository. */
fun changedFileOf(path: String, resolve: (String) -> String?): String? {
    val parts = path.split('/')
    for (drop in parts.indices) resolve(parts.drop(drop).joinToString("/"))?.let { return it }
    return null
}


/** One hop of a flow: [from] hands [label] (may be empty) to [to]. */
data class FlowHop(val from: String, val to: String, val label: String)

/** What a ```flow sketch draws, in reading order: runs of hops, and any line that is not a hop kept as its words. */
sealed interface FlowItem {
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
fun parseFlow(text: String): List<FlowItem>? {
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
fun flowHops(items: List<FlowItem>): List<FlowHop> = items.filterIsInstance<FlowItem.Run>().flatMap { it.hops }


/** The first sentence of [text]: up to the first `. `, `! ` or `? `, or all of it. */
fun firstSentence(text: String): String {
    val trimmed = text.trim()
    val end = Regex("""[.!?](?=\s)""").find(trimmed)?.range?.first ?: return trimmed
    return trimmed.substring(0, end + 1)
}


/** An answer as plain text for the clipboard: links keep their words, code keeps its text. */
fun plainAnswer(markdown: String): String = markdown
    .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
    .replace(Regex("""(?m)^```.*$\n?"""), "")
    .replace(Regex("""`([^`]+)`"""), "$1")
    .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
    .trim()
