package com.octaviantocan.pulls.core.markdown

/**
 * The scanners used by Android's CommonMark autolink extension, ported from
 * org.nibor.autolink 0.11.0. Markdown structure comes from the proven GFM parser;
 * this preserves the existing plain-text URL/email extension. MIT attribution
 * and the original license are in markdown-parsers.txt.
 */
internal fun autolinkText(text: String): List<Node> {
    val result = mutableListOf<Node>()
    var consumed = 0
    var at = 0
    while (at < text.length) {
        val range = when (text[at]) {
            ':' -> urlSpan(text, at, consumed)
            '@' -> emailSpan(text, at, consumed)
            else -> null
        }
        if (range == null) { at++; continue }
        if (range.first > consumed) result += Text(text.substring(consumed, range.first))
        val label = text.substring(range)
        val destination = if (text[at] == '@') "mailto:$label" else label
        result += Link(destination).also { it.append(Text(label)) }
        consumed = range.last + 1
        at = consumed
    }
    if (consumed < text.length) result += Text(text.substring(consumed))
    return result
}

private fun urlSpan(text: String, at: Int, rewind: Int): IntRange? {
    val after = at + 3
    if (after >= text.length || text.getOrNull(at + 1) != '/' || text.getOrNull(at + 2) != '/') return null
    var first = -1
    var digit = -1
    for (index in at - 1 downTo rewind) {
        val char = text[index]
        when {
            char in 'A'..'Z' || char in 'a'..'z' -> first = index
            char in '0'..'9' -> digit = index
            char !in "+-." -> break
        }
    }
    if (first == -1 || first > 0 && first - 1 == digit) return null
    val last = urlEnd(text, after)
    return if (last == -1) null else first..last
}

private fun emailSpan(text: String, at: Int, rewind: Int): IntRange? {
    var first = -1
    var atomBoundary = true
    for (index in at - 1 downTo rewind) {
        val char = text[index]
        when {
            emailAtom(char) -> { first = index; atomBoundary = false }
            char == '.' -> { if (atomBoundary) break; atomBoundary = true }
            else -> break
        }
    }
    if (first == -1) return null
    var firstInDomain = true
    var canEndDomain = false
    var firstDot = -1
    var last = -1
    for (index in at + 1 until text.length) {
        val char = text[index]
        if (firstInDomain) {
            if (!emailDomain(char)) break
            last = index
            firstInDomain = false
            canEndDomain = true
        } else when {
            char == '.' -> {
                if (!canEndDomain) break
                firstInDomain = true
                if (firstDot == -1) firstDot = index
            }
            char == '-' -> canEndDomain = false
            emailDomain(char) -> { last = index; canEndDomain = true }
            else -> break
        }
    }
    return if (firstDot == -1 || firstDot > last) null else first..last
}

private fun urlEnd(text: String, from: Int): Int {
    var round = 0
    var square = 0
    var curly = 0
    var singleQuote = false
    var last = -1
    for (index in from until text.length) {
        val char = text[index]
        if (char.code <= 0x20 || char.code in 0x7F..0x9F || char in "\"<>`\u00A0\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000") break
        when (char) {
            '?', '!', '.', ',', ':', ';' -> Unit
            '/' -> if (last == index - 1) last = index
            '(' -> round++
            ')' -> { round--; if (round >= 0) last = index else break }
            '[' -> square++
            ']' -> { square--; if (square >= 0) last = index else break }
            '{' -> curly++
            '}' -> { curly--; if (curly >= 0) last = index else break }
            '\'' -> { singleQuote = !singleQuote; if (!singleQuote) last = index }
            else -> last = index
        }
    }
    return last
}

private fun asciiAlnum(char: Char): Boolean = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9'
private fun emailDomain(char: Char): Boolean = asciiAlnum(char) || char.code >= 0x80
private fun emailAtom(char: Char): Boolean = emailDomain(char) || char in "!#$%&'*+-/=?^_`{|}~"
