@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal actual fun personalProofEntropy(): ByteArray = ByteArray(32).also { bytes ->
    val result = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), it.addressOf(0)) }
    check(result == errSecSuccess) { "Secure sign-in is temporarily unavailable." }
}

internal actual fun personalProofDigest(asciiProof: String): ByteArray {
    val bytes = asciiProof.encodeToByteArray()
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    try {
        digest.usePinned { output -> bytes.usePinned { input ->
            CC_SHA256(input.addressOf(0), bytes.size.convert(), output.addressOf(0).reinterpret())
        } }
    } finally { bytes.fill(0) }
    return digest
}
