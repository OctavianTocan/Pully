package com.octaviantocan.pulls.core.markdown

/** CommonMark 0.24.0's escaping and HTML5 entity semantics, including multi-code-point names. */
internal fun unescapeMarkdown(text: String): String = ESCAPES.replace(text) { match ->
    val value = match.value
    if (value.startsWith('\\')) value.substring(1) else {
        val name = value.drop(1).dropLast(1)
        if (!name.startsWith('#')) markdownEntities[name] ?: value
        else {
            val hexadecimal = name.startsWith("#x", true)
            val number = name.drop(if (hexadecimal) 2 else 1).toIntOrNull(if (hexadecimal) 16 else 10)
            when {
                number == null || number == 0 || number > 0x10FFFF -> "\uFFFD"
                number <= 0xFFFF -> number.toChar().toString()
                else -> {
                    val code = number - 0x10000
                    "${(0xD800 + (code shr 10)).toChar()}${(0xDC00 + (code and 0x3FF)).toChar()}"
                }
            }
        }
    }
}

private val ESCAPES = Regex("""\\[!"#$%&'()*+,\-./:;<=>?@\[\]\\^_`{|}~]|&(?:[A-Za-z][A-Za-z0-9]{1,31}|#[0-9]{1,7}|#[xX][0-9A-Fa-f]{1,6});""")
