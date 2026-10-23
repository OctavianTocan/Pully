package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.core.sha256Utf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceStorageNamespaceTest {
    @Test
    fun onlyAnEligibleCurrentAccountCanOwnAServerCopy() {
        val anonymous = sourceStorageNamespace("https://review.example", null, false)
        assertNotNull(anonymous)
        assertEquals(64, anonymous.length)
        assertEquals(anonymous, sourceStorageNamespace("https://REVIEW.example:443/", "old@example.test", false))
        val alice = sourceStorageNamespace("https://review.example", "alice@example.test", true)
        assertNotEquals(anonymous, alice)
        assertNotEquals(alice, sourceStorageNamespace("https://review.example", "bob@example.test", true))
        assertNotEquals(alice, sourceStorageNamespace("https://another.example", "alice@example.test", true))
        assertFalse(alice.orEmpty().contains("alice"))
        assertNull(sourceStorageNamespace("https://user:secret@review.example", "alice@example.test", true))
    }

    @Test
    fun headerCaseAndOrderAreCanonicalWhileAccessRotationStartsANewNamespace() {
        val first = mapOf("CF-Access-Client-Id" to "fixture-id", "CF-Access-Client-Secret" to "fixture-secret")
        val reordered = mapOf("cf-access-client-secret" to "fixture-secret", "cf-access-client-id" to "fixture-id")
        val namespace = sourceStorageNamespace("https://review.example", "alice@example.test", true, first)
        assertEquals(namespace, sourceStorageNamespace("https://REVIEW.example:443/", "alice@example.test", true, reordered))
        assertNotEquals(namespace, sourceStorageNamespace("https://review.example", "alice@example.test", true, first + ("CF-Access-Client-Secret" to "rotated")))
        assertFalse(namespace.orEmpty().contains("fixture"))
        assertNotEquals(sourceStorageNamespace("https://review.example", null, false, first), sourceStorageNamespace("https://review.example", null, false, first + ("CF-Access-Client-Secret" to "rotated")))
    }

    @Test
    fun sessionRenewalKeepsTheSameEligibleOwnerInsteadOfHashingASessionToken() {
        val headers = mapOf("CF-Access-Client-Id" to "fixture-id", "CF-Access-Client-Secret" to "fixture-secret")
        assertEquals(
            sourceStorageNamespace("https://review.example", "alice@example.test", true, headers + ("X-Pully-Session" to "session-a")),
            sourceStorageNamespace("https://review.example", "alice@example.test", true, headers + ("x-pully-session" to "session-b")),
        )
    }

    @Test
    fun platformDigestMatchesSha256KnownVectorsAndExactUtf8() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Utf8(""))
        assertEquals("b335630551682c19a781afebcf4d07bf978fb1f8ac04c6bf87428ed5106870f5", sha256Utf8("README.md"))
        assertEquals("720dd3febeadb4ee85a604e326756792b18060ee86f73e81a9d6460449f74895", sha256Utf8("src/café/変更.kt"))
    }

    @Test
    fun aCloudflareSignInKeepsItsCopiesAcrossNewTokensForTheSamePerson() {
        fun token(payload: String) = "e30." + kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .encode(payload.encodeToByteArray()) + ".sig"
        val monday = token("""{"email":"alice@example.test","exp":1}""")
        val tuesday = token("""{"email":"alice@example.test","exp":2}""")
        assertEquals("alice@example.test", accessTokenIdentity(monday))
        val namespace = sourceStorageNamespace("https://review.example", null, false, mapOf(ACCESS_TOKEN_HEADER to monday))
        assertEquals(namespace, sourceStorageNamespace("https://review.example", null, false, mapOf(ACCESS_TOKEN_HEADER to tuesday)))
        assertNotEquals(namespace, sourceStorageNamespace("https://review.example", null, false, mapOf(ACCESS_TOKEN_HEADER to token("""{"email":"bob@example.test"}"""))))
        assertNull(accessTokenIdentity("not-a-token"))
    }
}
