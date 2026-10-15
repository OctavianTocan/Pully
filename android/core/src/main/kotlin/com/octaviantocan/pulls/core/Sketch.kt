package com.octaviantocan.pulls.core

/*
 * The sketches a tour stop draws (rule 95), read from the fences the model writes: ```calls, a call tree indented two
 * spaces per level, ```tree, a file tree with a short comment per entry, and ```pseudo, the logic as pseudocode. The app
 * draws the trees as rows on guide lines and colours the pseudocode with the diff's syntax colours.
 */

/** One line of a call tree or a file tree: how deep it sits, its name, and the comment written beside it (may be empty). */
data class SketchRow(val depth: Int, val name: String, val note: String)

/**
 * Guides, bullets and arrows a tree line may open with, before its name: `├── `, `│   `, `` `-- ``, `+-- `, `- `, `* `,
 * `-> `, `→ `. A `+`, `-` or backtick that starts a name (`+page.svelte`, `` `parse()` ``) stays with it.
 */
private val TREE_PREFIX = Regex("""^(?:\s|[│├└┌┬┼─━┃┣┗╰╭•▸▶→↳|\\]|[-+`](?=[-─>])|[-*>](?=\s)|=>(?=\s))*""")

/**
 * Where a name ends and its comment starts: `# …`, `// …`, a spaced dash, `← …`, `): …` after a call, or a run of two or
 * more spaces (comments lined up in a column).
 */
private val NOTE_MARK = Regex("""\s+#(?=\s|$)|\s+//|\s+[—–]\s+|\s+-{1,2}\s+|\s*←\s*|\s+<-{1,2}\s*|(?<=\))\s*:\s+|\s{2,}(?=\S)""")

/**
 * The rows of a ```calls or ```tree sketch. A row's depth comes from the column its name starts in, after any guides
 * or bullets, compared with the rows above it: so a tree indented by two, by four, or drawn with `├──` guides reads the
 * same. A line that is only a comment keeps its words as a row with no name.
 */
fun sketchRows(text: String): List<SketchRow> {
    val rows = mutableListOf<SketchRow>()
    // The columns of the open levels, outermost first.
    val columns = ArrayDeque<Int>()
    for (raw in text.lines()) {
        val line = raw.replace("\t", "    ").trimEnd()
        val prefix = TREE_PREFIX.find(line)?.value.orEmpty()
        val content = line.substring(prefix.length)
        if (content.isBlank()) continue
        val column = prefix.length
        while (columns.isNotEmpty() && column < columns.last()) columns.removeLast()
        if (columns.isEmpty() || column > columns.last()) columns.addLast(column)
        val (name, note) = splitNote(content)
        rows += SketchRow(columns.size - 1, name, note)
    }
    return rows
}

/** A tree line's name and its comment. A line that opens with a comment mark is all comment. */
private fun splitNote(content: String): Pair<String, String> {
    val text = content.trim()
    if (text.startsWith("#") || text.startsWith("//")) return "" to text.trimStart('#', '/').trim()
    val mark = NOTE_MARK.find(text) ?: return clean(text) to ""
    return clean(text.substring(0, mark.range.first)) to text.substring(mark.range.last + 1).trim()
}

/** A name without the Markdown the model may wrap it in. */
private fun clean(name: String): String = name.trim().trim('*', '`').trim()

/**
 * The guide lines in front of each row of a tree whose rows sit at [depths]. Row i gets one flag per level above it:
 * flag k says a guide runs down past the row in column k, because a later row at depth k + 1 still hangs from the same
 * parent. The last flag is the row's own branch: false when it is its parent's last child, so its guide stops at it.
 */
fun treeGuides(depths: List<Int>): List<List<Boolean>> = depths.mapIndexed { i, depth ->
    List(depth) { k ->
        var below = false
        for (j in i + 1 until depths.size) {
            if (depths[j] <= k) break
            if (depths[j] == k + 1) { below = true; break }
        }
        below
    }
}

/** Whether a file tree's row is a folder: written with a trailing slash, or with rows hanging under it. */
fun isFolderRow(rows: List<SketchRow>, index: Int): Boolean =
    rows[index].name.endsWith("/") || (rows.getOrNull(index + 1)?.depth ?: -1) > rows[index].depth

/**
 * The path a file tree's row names: its ancestors' names joined with its own (`src/` + `api/` + `client.ts`), so a row
 * can open the file it names. A row already written as a path keeps it.
 */
fun treePath(rows: List<SketchRow>, index: Int): String {
    val parts = ArrayDeque<String>()
    var depth = rows[index].depth
    parts.addFirst(rows[index].name)
    for (j in index - 1 downTo 0) {
        if (rows[j].depth < depth) {
            parts.addFirst(rows[j].name)
            depth = rows[j].depth
        }
        if (depth == 0) break
    }
    return parts.map { it.trim('/') }.filter { it.isNotEmpty() && it != "." }.joinToString("/")
}

/** What a piece of pseudocode is, for the colour the diff gives that kind of code. */
enum class PseudoKind { Plain, Keyword, Literal, Number, Text, Comment, Call, Operator }

/** A run of a pseudocode line, and what it is. */
data class PseudoToken(val text: String, val kind: PseudoKind)

/** The words pseudocode is written in, as the reader would see them highlighted in real code. */
private val PSEUDO_KEYWORDS = setOf(
    "if", "else", "elif", "elseif", "then", "end", "endif", "for", "foreach", "each", "in", "while", "do", "loop", "repeat",
    "until", "unless", "return", "function", "func", "fn", "def", "procedure", "call", "await", "async", "and", "or", "not",
    "try", "catch", "except", "finally", "throw", "raise", "break", "continue", "switch", "case", "when", "match",
    "default", "let", "const", "var", "new", "yield", "emit", "begin", "otherwise",
)
private val PSEUDO_LITERALS = setOf("true", "false", "null", "nil", "none", "undefined")
private const val OPERATOR_CHARS = "=<>!+-*/%&|^~?→⇒"

/**
 * One line of pseudocode in runs a highlighter would colour: keywords, literals, numbers, quoted text, comments (`//`,
 * or `#` standing on its own), names called with `(`, and operators. Everything else is plain. An apostrophe inside a
 * word ("the widget's") is not a quote. The runs joined give back the line.
 */
fun pseudoTokens(line: String): List<PseudoToken> {
    val out = mutableListOf<PseudoToken>()
    fun add(text: String, kind: PseudoKind) {
        val last = out.lastOrNull()
        if (last != null && last.kind == kind && kind == PseudoKind.Plain) out[out.lastIndex] = PseudoToken(last.text + text, kind)
        else out += PseudoToken(text, kind)
    }
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        val prev = line.getOrNull(i - 1)
        when {
            ch == '/' && line.getOrNull(i + 1) == '/' && prev != ':' -> { add(line.substring(i), PseudoKind.Comment); i = line.length }
            ch == '#' && (prev == null || prev.isWhitespace()) && (i + 1 >= line.length || line[i + 1].isWhitespace()) -> { add(line.substring(i), PseudoKind.Comment); i = line.length }
            ch == '"' || ch == '`' || (ch == '\'' && (prev == null || !prev.isLetterOrDigit())) -> {
                var j = i + 1
                while (j < line.length && line[j] != ch) j += if (line[j] == '\\') 2 else 1
                val end = minOf(j + 1, line.length)
                add(line.substring(i, end), PseudoKind.Text)
                i = end
            }
            ch.isDigit() -> {
                var j = i + 1
                while (j < line.length && (line[j].isDigit() || line[j] == '.' && line.getOrNull(j + 1)?.isDigit() == true || line[j] == '_')) j++
                add(line.substring(i, j), PseudoKind.Number)
                i = j
            }
            ch.isLetter() || ch == '_' || ch == '$' -> {
                var j = i + 1
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '$')) j++
                val word = line.substring(i, j)
                val lower = word.lowercase()
                var k = j
                while (k < line.length && line[k] == ' ') k++
                val kind = when {
                    lower in PSEUDO_KEYWORDS -> PseudoKind.Keyword
                    lower in PSEUDO_LITERALS -> PseudoKind.Literal
                    line.getOrNull(k) == '(' -> PseudoKind.Call
                    else -> PseudoKind.Plain
                }
                add(word, kind)
                i = j
            }
            ch in OPERATOR_CHARS -> {
                var j = i + 1
                while (j < line.length && line[j] in OPERATOR_CHARS) j++
                add(line.substring(i, j), PseudoKind.Operator)
                i = j
            }
            else -> { add(ch.toString(), PseudoKind.Plain); i++ }
        }
    }
    return out
}

/**
 * A line of real code with one run of each [PseudoKind] in it, and where each run starts: the app has the diff's
 * highlighter colour it (as Python, a grammar it has), then reads each kind's colour at its offset, so pseudocode wears
 * the diff's own colours. Plain words take the colour of the space after `if`, the theme's own text colour: the
 * highlighter colours a call's argument like the call.
 */
const val PSEUDO_PROBE = "if f(x) == \"s\" and 1 or True:  # c"

/** The offset in [PSEUDO_PROBE] of a run of each kind. */
val PSEUDO_PROBE_AT: Map<PseudoKind, Int> = mapOf(
    PseudoKind.Keyword to 0,
    PseudoKind.Call to 3,
    PseudoKind.Plain to 2,
    PseudoKind.Operator to 8,
    PseudoKind.Text to 12,
    PseudoKind.Number to 19,
    PseudoKind.Literal to 24,
    PseudoKind.Comment to 33,
)
