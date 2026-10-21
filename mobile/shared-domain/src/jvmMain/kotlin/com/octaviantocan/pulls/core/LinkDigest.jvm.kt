package com.octaviantocan.pulls.core

import java.security.MessageDigest

internal actual fun sha256Utf8(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).hexDigest()
