package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TourStoreTest {
    private val dir = Files.createTempDirectory("tours").toFile()

    private fun tour(at: String, stops: Int = 2): JsonObject = Json.parseToJsonElement(
        """{"title":"Cache","overview":"How it caches.","locale":"en","generatedAt":"$at","headSha":"h1",
           "stops":[${(1..stops).joinToString(",") { """{"title":"Stop $it","purpose":"","takeaway":"","blocks":[{"kind":"text","text":"Read"}],"sources":[]}""" }}],
           "glossary":[{"term":"TTL","meaning":"How long"}]}""",
    ).jsonObject

    @Test
    fun aKeptTourIsReadBackByANewStoreAsTheServerSentIt() {
        TourStore(dir).put("o/r#1", tour("2026-10-15T08:00:00Z"))
        val again = TourStore(dir).get("o/r#1")
        assertEquals("Cache", again?.title)
        assertEquals(listOf("Stop 1", "Stop 2"), again?.stops?.map { it.title })
        assertEquals("h1", again?.headSha)
        assertEquals(listOf("TTL" to "How long"), again?.glossary)
        assertTrue(TourStore(dir).has("o/r#1"))
    }

    @Test
    fun aNewerTourReplacesTheOldOne() {
        val store = TourStore(dir)
        store.put("o/r#1", tour("2026-10-15T08:00:00Z"))
        store.put("o/r#1", tour("2026-10-15T09:00:00Z", stops = 3))
        assertEquals(3, TourStore(dir).get("o/r#1")?.stops?.size)
    }

    @Test
    fun aTourWithNoStopsAndAPullWithNoTourAreNotKept() {
        val store = TourStore(dir)
        store.put("o/r#2", tour("2026-10-15T08:00:00Z", stops = 0))
        assertFalse(store.has("o/r#2"))
        assertNull(store.get("o/r#3"))
    }

    @Test
    fun aDamagedFileIsNoTour() {
        val store = TourStore(dir)
        store.put("o/r#4", tour("2026-10-15T08:00:00Z"))
        dir.listFiles()!!.forEach { it.writeText("{not json") }
        val again = TourStore(dir)
        assertFalse(again.has("o/r#4"))
        assertNull(again.get("o/r#4"))
        // The damaged file is gone, so Home fetches the tour again.
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun onlyTheNewestAreKept() {
        val store = TourStore(dir)
        repeat(TourStore.LIMIT + 3) { n -> store.put("o/r#$n", tour("2026-10-15T08:00:00Z")) }
        assertEquals(TourStore.LIMIT, dir.listFiles { file -> file.name.endsWith(".json") }!!.size)
    }

    @Test
    fun aTourBelongsToItsServerAndAccountAndLegacyUnownedCopiesAreNotAdopted() {
        TourStore(dir).put("o/r#1", tour("legacy"))
        val alice = TourStore(dir, namespace = "server-a/alice")
        val bob = TourStore(dir, namespace = "server-a/bob")
        val otherServer = TourStore(dir, namespace = "server-b/alice")
        assertNull(alice.get("o/r#1"))
        alice.put("o/r#1", tour("owned"))
        assertEquals("owned", alice.get("o/r#1")?.generatedAt)
        assertNull(bob.get("o/r#1"))
        assertNull(otherServer.get("o/r#1"))
        assertEquals("owned", TourStore(dir, namespace = "server-a/alice").get("o/r#1")?.generatedAt)
    }

    @Test
    fun anInvalidatedStoreCannotExposeItsParsedTourOrWriteForTheNextAccount() {
        var current = true
        val store = TourStore(dir, namespace = "server/alice", current = { current })
        store.put("o/r#1", tour("before"))
        assertNotNull(store.get("o/r#1"))
        current = false
        assertNull(store.get("o/r#1"))
        store.put("o/r#1", tour("late"))
        assertEquals("before", TourStore(dir, namespace = "server/alice").get("o/r#1")?.generatedAt)
        assertNull(TourStore(dir, namespace = "server/bob").get("o/r#1"))
    }

    @Test
    fun theExistingTwoHundredFileBudgetAppliesAcrossIdentityNamespaces() {
        repeat(3) { account ->
            val store = TourStore(dir, namespace = "server/account-$account")
            repeat(80) { pull -> store.put("o/r#$pull", tour("$account-$pull")) }
        }
        assertEquals(TourStore.LIMIT, dir.listFiles { file -> file.name.endsWith(".json") }!!.size)
    }

    @Test
    fun anEmailRememberedFromAnotherServerDoesNotSelectAnAuthenticatedTourNamespace() {
        val signedOut = tourNamespace("https://review.example", null, eligibleSession = false)
        assertEquals(signedOut, tourNamespace("https://REVIEW.example:443/", "alice@example.test", eligibleSession = false))
        assertFalse(signedOut == tourNamespace("https://review.example", "alice@example.test", eligibleSession = true))
        assertEquals(
            tourNamespace("https://review.example", "alice@example.test", eligibleSession = true),
            tourNamespace("https://REVIEW.example:443/", "alice@example.test", eligibleSession = true),
        )
    }
    @Test
    fun invalidServerNamespacesRetainNoUserinfoOrAccountIdentity() {
        val namespace = tourNamespace("https://user:secret@review.example", "alice@example.test", true)
        assertEquals("invalid-server", namespace)
        assertFalse(namespace.contains("secret") || namespace.contains("alice"))
    }

    @Test
    fun accessRotationCannotAdoptAnotherAuthorizationsKeptTour() {
        val first = mapOf("CF-Access-Client-Id" to "fixture-id", "CF-Access-Client-Secret" to "fixture-secret")
        val reordered = mapOf("cf-access-client-secret" to "fixture-secret", "cf-access-client-id" to "fixture-id")
        val source = tourNamespace("https://review.example", "alice@example.test", true, first)
        TourStore(dir, source).put("o/r#1", tour("owned-access"))
        val same = tourNamespace("https://REVIEW.example:443/", "alice@example.test", true, reordered)
        assertEquals("owned-access", TourStore(dir, same).get("o/r#1")?.generatedAt)
        val rotated = tourNamespace("https://review.example", "alice@example.test", true, first + ("CF-Access-Client-Secret" to "rotated"))
        assertNull(TourStore(dir, rotated).get("o/r#1"))
        assertEquals("owned-access", TourStore(dir, source).get("o/r#1")?.generatedAt)
    }

}
