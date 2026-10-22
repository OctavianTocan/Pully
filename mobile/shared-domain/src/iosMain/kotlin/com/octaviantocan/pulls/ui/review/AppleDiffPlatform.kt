@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.octaviantocan.pulls.ui.review

import kotlin.native.identityHashCode
import platform.Foundation.*

private val diffLocale = NSLocale(localeIdentifier = "en")

internal actual fun compareDiffPaths(first: String, second: String): Int =
    NSString.create(string = first).compare(second, options = 0uL, range = NSMakeRange(0uL, first.length.toULong()), locale = diffLocale).toInt()

internal actual fun diffIdentityHashCode(value: Any): Int = value.identityHashCode()
