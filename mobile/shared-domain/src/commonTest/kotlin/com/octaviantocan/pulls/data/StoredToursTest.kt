package com.octaviantocan.pulls.data

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredToursTest {
    @Test
    fun theSameOriginalTourJsonAndHeadRoundTripAndAreParsedOnlyOnce() {
        val disk = TourFiles()
        val original = tour("first")
        StoredTours(disk) { true }.put("owner/repo#1", original)
        assertEquals(original.toString(), disk.values["owner/repo#1"])
        val reopened = StoredTours(disk) { true }
        val first = reopened.get("owner/repo#1")!!
        assertEquals("Original tour", first.title)
        assertEquals("head-a", first.headSha)
        assertEquals("first", first.generatedAt)
        assertEquals("Code", first.stops.single().title)
        assertEquals(first, reopened.get("owner/repo#1"))
        assertEquals(1, disk.reads)
    }

    @Test
    fun theSameGeneratedTourIsNotWrittenTwiceButAMissingFileIsRepaired() {
        val disk = TourFiles()
        val stored = StoredTours(disk) { true }
        stored.put("a/b#1", tour("first")); stored.put("a/b#1", tour("first"))
        assertEquals(1, disk.writes)
        disk.values.clear()
        stored.put("a/b#1", tour("first"))
        assertEquals(2, disk.writes)
        stored.put("a/b#1", tour("newer"))
        assertEquals("newer", StoredTours(disk) { true }.get("a/b#1")?.generatedAt)
    }

    @Test
    fun emptyAndCorruptEntriesCannotBecomeAnAvailableTourAndAValidFetchCanRepairThem() {
        val disk = TourFiles()
        val stored = StoredTours(disk) { true }
        stored.put("a/b#1", Json.parseToJsonElement("""{"stops":[]}""").jsonObject)
        assertEquals(0, disk.writes)
        disk.values["a/b#1"] = "{corrupted"
        assertNull(stored.get("a/b#1"))
        assertFalse("a/b#1" in disk.values)
        disk.values["a/b#1"] = """{"stops":[]}"""
        assertNull(stored.get("a/b#1"))
        assertFalse("a/b#1" in disk.values)
        stored.put("a/b#1", tour("recovered"))
        assertEquals("recovered", stored.get("a/b#1")?.generatedAt)
    }

    @Test
    fun sourceRevocationRejectsBothParsedAndDiskCopiesAndEveryLaterWrite() {
        val disk = TourFiles()
        var current = true
        val stored = StoredTours(disk) { current }
        stored.put("a/b#1", tour("owned"))
        current = false
        assertNull(stored.get("a/b#1"))
        stored.put("a/b#1", tour("retired write"))
        assertEquals(1, disk.writes)
        assertEquals("owned", StoredTours(disk) { true }.get("a/b#1")?.generatedAt)
    }

    @Test
    fun revocationDuringAReadCannotRememberOrDeleteTheReturnedPrivateEntry() {
        val disk = TourFiles()
        disk.values["a/b#1"] = tour("kept").toString()
        var current = true
        disk.didRead = { current = false }
        val stored = StoredTours(disk) { current }
        assertNull(stored.get("a/b#1"))
        assertTrue("a/b#1" in disk.values)
        disk.didRead = {}
        current = true
        assertEquals("kept", stored.get("a/b#1")?.generatedAt)
        assertEquals(2, disk.reads)
    }

    @Test
    fun cacheFailuresNeverHideTheCurrentServerResultOrTurnIntoACorruptDelete() {
        val disk = TourFiles()
        disk.failWrites = true
        val stored = StoredTours(disk) { true }
        stored.put("a/b#1", tour("server result"))
        assertEquals("server result", stored.get("a/b#1")?.generatedAt)
        disk.failReads = true
        assertNull(StoredTours(disk) { true }.get("a/b#1"))
        assertEquals(0, disk.removes)
    }

    @Test
    fun cancellationRemainsCancellationInsteadOfAnUnavailableCache() {
        val disk = TourFiles()
        disk.didRead = { throw CancellationException("Source retired") }
        assertFailsWith<CancellationException> { StoredTours(disk) { true }.get("a/b#1") }
    }

    @Test
    fun parsedAndDiskCopiesKeepTheSameTwoHundredTourBound() {
        val disk = TourFiles()
        val stored = StoredTours(disk) { true }
        repeat(StoredTours.LIMIT + 3) { n -> stored.put("a/b#$n", tour("$n")) }
        assertEquals(StoredTours.LIMIT, disk.values.size)
        assertEquals(listOf(StoredTours.LIMIT), disk.limits.distinct())
        assertNull(stored.get("a/b#0"))
        assertEquals("202", stored.get("a/b#202")?.generatedAt)
        val reads = disk.reads
        stored.clearMemory()
        assertEquals("202", stored.get("a/b#202")?.generatedAt)
        assertEquals(reads + 1, disk.reads)
    }
}

private class TourFiles : StoredTourFiles {
    val values = linkedMapOf<String, String>()
    val limits = mutableListOf<Int>()
    var reads = 0
    var writes = 0
    var removes = 0
    var failReads = false
    var failWrites = false
    var didRead: () -> Unit = {}
    override fun read(pull: String): String? {
        reads++
        didRead()
        check(!failReads) { "Disk unavailable" }
        return values[pull]
    }
    override fun exists(pull: String) = values.containsKey(pull)
    override fun write(pull: String, tour: String) {
        check(!failWrites) { "Disk full" }
        writes++
        values.remove(pull)
        values[pull] = tour
    }
    override fun remove(pull: String) { removes++; values.remove(pull) }
    override fun prune(limit: Int) {
        limits += limit
        while (values.size > limit) values.remove(values.keys.first())
    }
}

private fun tour(at: String): JsonObject = Json.parseToJsonElement(
    """{"title":"Original tour","headSha":"head-a","generatedAt":"$at","stops":[{"title":"Code","takeaway":"Read this"}]}""",
).jsonObject
