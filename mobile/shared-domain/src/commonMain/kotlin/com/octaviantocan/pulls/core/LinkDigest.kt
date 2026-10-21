package com.octaviantocan.pulls.core

/** Platform SHA256 over the path's UTF8 bytes; never case-fold or normalize a GitHub path. */
internal expect fun sha256Utf8(text: String): String

internal fun ByteArray.hexDigest(): String = buildString(size * 2) {
    val digits = "0123456789abcdef"
    for (byte in this@hexDigest) {
        val value = byte.toInt() and 0xff
        append(digits[value ushr 4])
        append(digits[value and 15])
    }
}
