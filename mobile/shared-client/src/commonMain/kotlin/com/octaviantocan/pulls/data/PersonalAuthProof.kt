package com.octaviantocan.pulls.data

import kotlin.io.encoding.Base64

/** In-memory ownership proof. It is never a browser parameter or a persisted session. */
class PersonalAuthProof private constructor(internal val secret: String) {
    val challenge: String = personalProofDigest(secret).let { digest ->
        try { proofBase64.encode(digest) } finally { digest.fill(0) }
    }

    override fun toString(): String = "PersonalAuthProof([redacted])"

    companion object {
        fun create(): PersonalAuthProof {
            val bytes = personalProofEntropy()
            return try { fromEntropy(bytes) } finally { bytes.fill(0) }
        }

        internal fun fromEntropy(bytes: ByteArray): PersonalAuthProof {
            require(bytes.size == 32) { "A sign-in proof requires 32 random bytes." }
            return PersonalAuthProof(proofBase64.encode(bytes))
        }
    }
}

internal val proofBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
internal expect fun personalProofEntropy(): ByteArray
internal expect fun personalProofDigest(asciiProof: String): ByteArray

internal fun canonicalPersonalSecret(value: String): Boolean = runCatching {
    value.length == 43 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_" } &&
        proofBase64.decode(value).let { it.size == 32 && proofBase64.encode(it) == value }
}.getOrDefault(false)
