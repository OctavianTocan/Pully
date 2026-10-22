package com.octaviantocan.pulls.core

/** Existing FileLinks repository-root policy, independent of its JVM GitHub hashing adapter. */
fun repositoryPath(link: String): String? {
    val decoded = decodeUrlPath(link) ?: link
    val segments = decoded.split('/')
    if (segments.last() in FOLDER_ENDS) return null
    val path = ArrayDeque<String>()
    for (segment in segments) when (segment) {
        "", "." -> Unit
        ".." -> path.removeLastOrNull()
        else -> path.addLast(segment)
    }
    return path.joinToString("/").ifEmpty { null }
}

private val FOLDER_ENDS = setOf("", ".", "..")

/** URLDecoder's UTF-8 percent decoding with literal plus signs, and null for malformed escapes. */
fun decodeUrlPath(source: String): String? = buildString {
    var at = 0
    while (at < source.length) {
        if (source[at] != '%') {
            append(source[at++])
            continue
        }
        val bytes = ArrayList<Byte>()
        while (at < source.length && source[at] == '%') {
            if (at + 2 >= source.length) return null
            val high = source[at + 1].digitToIntOrNull(16) ?: return null
            val low = source[at + 2].digitToIntOrNull(16) ?: return null
            bytes += ((high shl 4) or low).toByte()
            at += 3
        }
        append(bytes.toByteArray().decodeToString())
    }
}
