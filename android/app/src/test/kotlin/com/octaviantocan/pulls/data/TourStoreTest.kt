package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertNull(TourStore(dir).get("o/r#4"))
    }

    @Test
    fun onlyTheNewestAreKept() {
        val store = TourStore(dir)
        repeat(TourStore.LIMIT + 3) { n -> store.put("o/r#$n", tour("2026-10-15T08:00:00Z")) }
        assertEquals(TourStore.LIMIT, dir.listFiles { file -> file.name.endsWith(".json") }!!.size)
    }
}
