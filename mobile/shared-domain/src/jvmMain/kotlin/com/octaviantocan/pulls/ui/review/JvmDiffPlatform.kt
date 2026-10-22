package com.octaviantocan.pulls.ui.review

import java.text.Collator
import java.util.Locale

private val diffCollator = Collator.getInstance(Locale.ENGLISH)

internal actual fun compareDiffPaths(first: String, second: String): Int =
    synchronized(diffCollator) { diffCollator.compare(first, second) }

internal actual fun diffIdentityHashCode(value: Any): Int = System.identityHashCode(value)
