package com.octaviantocan.pulls.data

internal actual fun analyticsStackTop(error: Throwable, frames: Int): String =
    error.stackTrace.take(frames).joinToString("\n") { it.toString() }
internal actual fun analyticsErrorKind(error: Throwable): String = error::class.java.simpleName.ifBlank { "Throwable" }
