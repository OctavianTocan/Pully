package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/*
 * Markdown that reads rendered while it is typed, as Obsidian's live preview does ("It is fine to let me do markdown,
 * but just render it immediately, basically. Something like Obsidian": recording 17 Oct 14:45, 02:00 to 02:25; Q4).
 * The words keep every character, so the cursor and the text never drift apart: headings and **strong** words are
 * set heavier, *emphasis* slanted, `code` in the code face, a link's words in the link ink, and a list's dash reads as a
 * bullet. The marks themselves (#, **, `, the link's address) fold away on every line but the one the cursor is on,
 * and show, quiet, on that line so they can be edited. HTML comments and tags are quiet throughout.
 */

/** [text] styled as live Markdown for a field whose cursor is at [cursor]: the same characters, one for one. */
fun markdownLive(text: String, cursor: Int, c: PullsColors, codeFont: FontFamily = PullsType.mono): AnnotatedString {
    val folded = SpanStyle(color = Color.Transparent, fontSize = 0.01.em)
    val quiet = SpanStyle(color = c.muted)
    val code = SpanStyle(fontFamily = codeFont, background = c.code)
    val link = SpanStyle(color = c.activeText, textDecoration = TextDecoration.Underline)
    return buildAnnotatedString {
        append(bulleted(text))
        var start = 0
        var fenced = false
        for (line in text.split('\n')) {
            val end = start + line.length
            val here = cursor in start..end
            // A mark the cursor's line shows quietly, and any other line folds away.
            val mark = if (here) quiet else folded
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            when {
                trimmed.startsWith("```") -> {
                    // A fence is all marks: it folds away like the others, and shows quietly on the cursor's line.
                    fenced = !fenced
                    addStyle(code, start, end)
                    addStyle(mark, start, end)
                }
                fenced -> addStyle(code, start, end)
                else -> {
                    HEADING.find(line)?.let { m ->
                        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, end)
                        addStyle(mark, start, start + m.value.length)
                    }
                    QUOTE.find(line)?.let { m ->
                        addStyle(quiet, start, end)
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                        addStyle(mark, start, start + m.value.length)
                    }
                    if (BULLET.matches(line.take(indent + 2))) addStyle(quiet, start + indent, start + indent + 1)
                    ORDERED.find(line)?.let { m -> addStyle(quiet, start, start + m.value.length) }
                    inline(line, start, mark, code, link)
                }
            }
            start = end + 1
        }
        // Comments and tags, which the page does not show, stay quiet wherever they run.
        for (m in COMMENT.findAll(text)) addStyle(quiet, m.range.first, m.range.last + 1)
        for (m in TAG.findAll(text)) addStyle(quiet, m.range.first, m.range.last + 1)
    }
}

private fun AnnotatedString.Builder.inline(line: String, start: Int, mark: SpanStyle, code: SpanStyle, link: SpanStyle) {
    val taken = BooleanArray(line.length)
    fun free(range: IntRange) = range.none { taken[it] }
    fun take(range: IntRange) = range.forEach { taken[it] = true }
    for (m in CODE.findAll(line)) {
        take(m.range)
        addStyle(code, start + m.range.first, start + m.range.last + 1)
        addStyle(mark, start + m.range.first, start + m.range.first + 1)
        addStyle(mark, start + m.range.last, start + m.range.last + 1)
    }
    for (m in LINK.findAll(line)) {
        if (!free(m.range)) continue
        take(m.range)
        val wordsStart = m.range.first + if (m.value.startsWith("!")) 2 else 1
        val wordsEnd = wordsStart + m.groupValues[1].length
        addStyle(link, start + wordsStart, start + wordsEnd)
        addStyle(mark, start + m.range.first, start + wordsStart)
        addStyle(mark, start + wordsEnd, start + m.range.last + 1)
    }
    for ((pattern, style) in listOf(STRONG to SpanStyle(fontWeight = FontWeight.SemiBold), EMPHASIS to SpanStyle(fontStyle = FontStyle.Italic), STRIKE to SpanStyle(textDecoration = TextDecoration.LineThrough))) {
        for (m in pattern.findAll(line)) {
            if (!free(m.range)) continue
            take(m.range)
            val innerStart = m.range.first + m.groupValues[1].length
            val innerEnd = innerStart + m.groupValues[2].length
            addStyle(style, start + innerStart, start + innerEnd)
            addStyle(mark, start + m.range.first, start + innerStart)
            addStyle(mark, start + innerEnd, start + m.range.last + 1)
        }
    }
}

/** A list's dash, star or plus, at a line's start, reads as the bullet the page draws (one character for one); code is left as typed. */
private fun bulleted(text: String): String {
    var fenced = false
    return text.split('\n').joinToString("\n") { line ->
        if (line.trimStart().startsWith("```")) fenced = !fenced
        val indent = line.length - line.trimStart().length
        if (!fenced && BULLET.matches(line.take(indent + 2))) line.substring(0, indent) + "•" + line.substring(indent + 1) else line
    }
}

private val HEADING = Regex("^#{1,6} ")
private val QUOTE = Regex("^[ \\t]*> ?")
private val BULLET = Regex("^[ \\t]*[-*+] $")
private val ORDERED = Regex("^[ \\t]*\\d+[.)] ")
private val CODE = Regex("`[^`\\n]+`")
private val LINK = Regex("!?\\[([^\\]\\n]+)]\\([^)\\n]*\\)")
private val STRONG = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
private val EMPHASIS = Regex("(?<![*_\\w])([*_])(?=\\S)(.+?)(?<=\\S)\\1(?![*_\\w])")
private val STRIKE = Regex("(~~)(?=\\S)(.+?)(?<=\\S)\\1")
private val COMMENT = Regex("<!--[\\s\\S]*?(-->|$)")
private val TAG = Regex("</?[a-zA-Z][^>\\n]*>")
