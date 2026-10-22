package com.octaviantocan.pulls.data

internal expect fun analyticsStackTop(error: Throwable, frames: Int): String
internal expect fun analyticsErrorKind(error: Throwable): String
