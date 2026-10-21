@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.core

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

internal actual fun sha256Utf8(text: String): String {
    val bytes = text.encodeToByteArray()
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { output ->
        bytes.usePinned { input ->
            CC_SHA256(if (bytes.isEmpty()) null else input.addressOf(0), bytes.size.convert(), output.addressOf(0).reinterpret())
        }
    }
    return digest.hexDigest()
}
