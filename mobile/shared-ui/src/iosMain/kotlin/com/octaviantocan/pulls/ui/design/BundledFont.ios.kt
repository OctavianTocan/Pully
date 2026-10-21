package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.posix.memcpy

internal actual fun bundledFont(name: String, weight: FontWeight): Font = Font(
    identity = "pully/$name",
    getData = { fontBytes(name) },
    weight = weight,
)

@OptIn(ExperimentalForeignApi::class)
private fun fontBytes(name: String): ByteArray {
    val path = requireNotNull(NSBundle.mainBundle.pathForResource(name, ofType = "ttf")) {
        "Missing bundled font: $name"
    }
    val data = requireNotNull(NSFileManager.defaultManager.contentsAtPath(path)) {
        "Cannot read bundled font: $name"
    }
    return ByteArray(data.length.toInt()).apply {
        if (isNotEmpty()) usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
    }
}
