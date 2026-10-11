package com.octaviantocan.pulls.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle

/** A small highlighter for review diffs. It is not a parser. It colors the tokens a reviewer scans for. */
object CodeHighlight {
    private val keywords = setOf(
        "if", "else", "for", "while", "return", "function", "const", "let", "var", "class", "interface",
        "type", "import", "export", "from", "async", "await", "new", "try", "catch", "throw", "switch",
        "case", "break", "continue", "public", "private", "protected", "fun", "val", "var", "when",
        "object", "package", "def", "fn", "struct", "enum", "impl", "match", "mut", "pub", "use",
        "true", "false", "null", "undefined", "this", "super",
    )

    fun language(path: String): String = path.substringAfterLast('.', "").lowercase()

    @Composable
    fun text(path: String, source: String): AnnotatedString {
        val keyword = MaterialTheme.colorScheme.primary
        val string = MaterialTheme.colorScheme.tertiary
        val comment = MaterialTheme.colorScheme.onSurfaceVariant
        val number = MaterialTheme.colorScheme.secondary
        return buildAnnotatedString {
            var i = 0
            while (i < source.length) {
                val rest = source.substring(i)
                when {
                    rest.startsWith("//") || (language(path) in setOf("py", "sh", "rb") && rest.startsWith("#")) -> {
                        val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                        withStyle(SpanStyle(color = comment)) { append(source.substring(i, end)) }
                        i = end
                    }
                    rest.startsWith("/*") -> {
                        val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                        withStyle(SpanStyle(color = comment)) { append(source.substring(i, end)) }
                        i = end
                    }
                    rest.first() == '"' || rest.first() == '\'' || rest.first() == '`' -> {
                        val quote = rest.first()
                        var j = i + 1
                        while (j < source.length && source[j] != quote) {
                            if (source[j] == '\\') j += 1
                            j += 1
                        }
                        if (j < source.length) j += 1
                        withStyle(SpanStyle(color = string)) { append(source.substring(i, j)) }
                        i = j
                    }
                    rest.first().isDigit() -> {
                        val end = i + rest.takeWhile { it.isDigit() || it == '.' }.length
                        withStyle(SpanStyle(color = number)) { append(source.substring(i, end)) }
                        i = end
                    }
                    rest.first().isLetter() || rest.first() == '_' -> {
                        val word = rest.takeWhile { it.isLetterOrDigit() || it == '_' }
                        val style = if (word in keywords) SpanStyle(color = keyword) else SpanStyle(color = Color.Unspecified)
                        withStyle(style) { append(word) }
                        i += word.length
                    }
                    else -> {
                        append(rest.first())
                        i += 1
                    }
                }
            }
        }
    }
}
