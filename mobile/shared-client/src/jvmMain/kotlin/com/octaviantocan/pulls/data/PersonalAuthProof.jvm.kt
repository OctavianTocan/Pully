package com.octaviantocan.pulls.data

import java.security.MessageDigest
import java.security.SecureRandom

internal actual fun personalProofEntropy(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
internal actual fun personalProofDigest(asciiProof: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(asciiProof.encodeToByteArray())
