package com.octaviantocan.pulls.data

internal actual fun analyticsStackTop(error: Throwable, frames: Int): String =
    error.stackTraceToString().lineSequence().drop(1).filter { it.isNotBlank() }.take(frames).joinToString("\n")
internal actual fun analyticsErrorKind(error: Throwable): String = error::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"
