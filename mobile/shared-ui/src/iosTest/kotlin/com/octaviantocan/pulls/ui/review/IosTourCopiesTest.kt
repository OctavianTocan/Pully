@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.LinkPlaces
import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.ui.home.HomeTourCopies
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import platform.Foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosTourCopiesTest {
    @Test
    fun theSameActualHomeAndSessionAdapterPersistsAcrossCloseAndVerifiedReopening() = runTest {
        val f = NativeTourFiles()
        try {
            val copy = f.owner.forSource(f.namespace) { true }
            assertSame(copy, f.owner.forSource(f.namespace) { true })
            val home: HomeTourCopies = copy
            val session: SessionTourCache = copy
            home.put("owner/repo#1", tour("first"))
            assertTrue(home.contains("owner/repo#1"))
            assertEquals("Original tour", session.get("owner/repo#1")?.title)
            assertEquals("head-a", session.get("owner/repo#1")?.headSha)
            f.owner.close()
            assertNull(copy.get("owner/repo#1"))
            copy.put("owner/repo#1", tour("late closed write"))
            val reopenedOwner = f.owner()
            val reopened = reopenedOwner.forSource(f.namespace) { true }
            assertEquals("first", reopened.get("owner/repo#1")?.generatedAt)
            assertEquals(tour("first").toString(), f.raw("owner/repo#1"))
        } finally { f.close() }
    }

    @Test
    fun sourceInvalidationPurgesBeforeAQueuedOldWriterAndCannotDeleteANewerOwnersCopy() = runTest {
        val f = NativeTourFiles()
        try {
            val old = f.owner.forSource(f.namespace) { true }
            old.put("owner/repo#1", tour("old"))
            val late = launch(start = CoroutineStart.UNDISPATCHED) { old.put("owner/repo#1", tour("retired")) }
            f.owner.invalidateSource()
            assertNull(old.get("owner/repo#1"))
            val nextOwner = f.owner()
            val next = nextOwner.forSource(f.namespace) { true }
            assertNull(next.get("owner/repo#1"))
            next.put("owner/repo#1", tour("replacement"))
            late.join()
            old.put("owner/repo#1", tour("another late writer"))
            f.owner.invalidateSource()
            assertEquals("replacement", next.get("owner/repo#1")?.generatedAt)
            assertEquals(tour("replacement").toString(), f.raw("owner/repo#1"))
        } finally { f.close() }
    }

    @Test
    fun namespacesAndFileNamesContainOnlyOpaqueDigestsAndDoNotAdoptAnotherAccountOrAccessSource() = runTest {
        val f = NativeTourFiles()
        try {
            val pull = "../Secret owner/repo#42"
            val original = f.owner.forSource(f.namespace) { true }
            original.put(pull, tour("owned")); f.owner.close()
            val otherNamespace = requireNotNull(sourceStorageNamespace("https://review.example", "b@octaviantocan.com", true))
            val other = f.owner().forSource(otherNamespace) { true }
            assertNull(other.get(pull))
            assertFalse(other.contains(pull))
            other.put(pull, tour("other"))
            val folders = f.names(f.root)
            assertTrue(folders.all { it.matches(Regex("[0-9a-f]{64}")) })
            folders.forEach { folder -> assertTrue(f.names("${f.root}/$folder").all { it.matches(Regex("[0-9a-f]{64}\\.json")) }) }
            val rotated = requireNotNull(sourceStorageNamespace("https://review.example", "a@octaviantocan.com", true, mapOf("CF-Access-Client-Secret" to "rotated")))
            val rotatedCopy = f.owner().forSource(rotated) { true }
            assertNull(rotatedCopy.get(pull))
        } finally { f.close() }
    }

    @Test
    fun corruptionEmptyResultsAndOversizedFilesAreRemovedAndThenRepairedByARealWrite() = runTest {
        val f = NativeTourFiles()
        try {
            for (raw in listOf("{broken", """{"stops":[]}""", "x".repeat(IosTourCopies.MAX_FILE_BYTES + 1))) {
                f.seed("owner/repo#1", raw)
                val owner = f.owner()
                val copy = owner.forSource(f.namespace) { true }
                assertNull(copy.get("owner/repo#1"))
                assertFalse(NSFileManager.defaultManager.fileExistsAtPath(f.path("owner/repo#1")))
                copy.put("owner/repo#1", tour("repaired"))
                assertTrue(copy.contains("owner/repo#1"))
                assertEquals("repaired", copy.get("owner/repo#1")?.generatedAt)
                owner.close()
            }
            val copy = f.owner().forSource(f.namespace) { true }
            copy.put("owner/repo#2", tour("too large", "x".repeat(IosTourCopies.MAX_FILE_BYTES)))
            assertFalse(copy.contains("owner/repo#2"))
            assertNull(f.raw("owner/repo#2"))
        } finally { f.close() }
    }

    @Test
    fun theActualFileCacheKeepsAtMostTwoHundredCopiesAndAtomicReplacementLeavesOneJson() = runTest {
        val f = NativeTourFiles()
        try {
            val copy = f.owner.forSource(f.namespace) { true }
            repeat(203) { n -> copy.put("owner/repo#$n", tour("$n")) }
            assertEquals(200, f.names("${f.root}/${f.namespace}").size)
            copy.put("owner/repo#202", tour("replacement"))
            assertEquals(tour("replacement").toString(), f.raw("owner/repo#202"))
            assertTrue(f.names("${f.root}/${f.namespace}").all { it.endsWith(".json") })
        } finally { f.close() }
    }

    @Test
    fun theGlobalByteBudgetPrunesRealFilesAcrossNamespaces() = runTest {
        val f = NativeTourFiles()
        try {
            val other = LinkPlaces.fileAnchor("another source")
            val large = tour("large", "x".repeat(3 * 1024 * 1024)).toString()
            repeat(22) { index -> f.seed("owner/repo#$index", large, if (index % 2 == 0) f.namespace else other) }
            val copy = f.owner.forSource(f.namespace) { true }
            val sizes = f.names(f.root).flatMap { source -> f.names("${f.root}/$source").map { name ->
                val attributes = NSFileManager.defaultManager.attributesOfItemAtPath("${f.root}/$source/$name", error = null)!!
                (attributes[NSFileSize] as NSNumber).longLongValue
            } }
            assertTrue(sizes.sum() <= IosTourCopies.MAX_TOTAL_BYTES)
            assertTrue(sizes.size < 22) // Activation itself recovers the budget, before any new write.
            copy.put("owner/repo#latest", tour("latest"))
            assertNotNull(copy.get("owner/repo#latest"))
            assertNotNull(f.raw("owner/repo#latest"))
        } finally { f.close() }
    }

    @Test
    fun reopeningAnInterruptedOverCountCachePrunesBeforeReadingAndDoesNotRewriteADuplicateTour() = runTest {
        val f = NativeTourFiles()
        try {
            // This is the durable state a process can leave between the final atomic write and its prune.
            repeat(201) { index -> f.seed("owner/repo#$index", tour("$index").toString()) }
            val copy = f.owner.forSource(f.namespace) { true }
            assertEquals(200, f.names("${f.root}/${f.namespace}").size)
            val kept = (0..200).first { NSFileManager.defaultManager.fileExistsAtPath(f.path("owner/repo#$it")) }
            val pull = "owner/repo#$kept"
            assertTrue(copy.contains(pull))
            assertEquals("$kept", copy.get(pull)?.generatedAt)
            val before = NSFileManager.defaultManager.attributesOfItemAtPath(f.path(pull), error = null)!![NSFileModificationDate]
            copy.put(pull, tour("$kept"))
            val after = NSFileManager.defaultManager.attributesOfItemAtPath(f.path(pull), error = null)!![NSFileModificationDate]
            assertEquals(before, after)
            assertEquals(200, f.names("${f.root}/${f.namespace}").size)
        } finally { f.close() }
    }

    @Test
    fun theActualSerialWorkerKeepsWriteSubmissionOrderAndALateOldOwnerCannotRevokeItsReplacement() = runTest {
        val f = NativeTourFiles()
        try {
            val copy = f.owner.forSource(f.namespace) { true }
            val pending = (0 until 32).map { index -> launch(start = CoroutineStart.UNDISPATCHED) { copy.put("owner/repo#1", tour("$index")) } }
            pending.forEach { it.join() }
            val replacementOwner = f.owner()
            val replacement = replacementOwner.forSource(f.namespace) { true }
            assertEquals("31", replacement.get("owner/repo#1")?.generatedAt)
            f.owner.invalidateSource()
            assertEquals("31", replacement.get("owner/repo#1")?.generatedAt)
            assertNull(copy.get("owner/repo#1"))
        } finally { f.close() }
    }
}

private class NativeTourFiles {
    val root = NSTemporaryDirectory() + "pully-tour-copy-tests-" + NSUUID().UUIDString
    val namespace = requireNotNull(sourceStorageNamespace("https://review.example", "a@octaviantocan.com", true))
    private val owners = mutableListOf<IosTourCacheOwner>()
    val owner = owner()
    fun owner() = IosTourCacheOwner(root).also { owners += it }
    fun path(pull: String, source: String = namespace) = "$root/$source/${LinkPlaces.fileAnchor(pull)}.json"
    fun names(path: String) = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, error = null) as? List<*>)
        .orEmpty().filterIsInstance<String>()
    fun seed(pull: String, raw: String, source: String = namespace) {
        check(NSFileManager.defaultManager.createDirectoryAtPath("$root/$source", true, null, null))
        check(NSString.create(string = raw).writeToFile(path(pull, source), atomically = true, encoding = NSUTF8StringEncoding, error = null))
    }
    fun raw(pull: String): String? = NSFileManager.defaultManager.contentsAtPath(path(pull))?.let {
        NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString()
    }
    fun close() {
        owners.forEach { it.close() }
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }
}

private fun tour(at: String, text: String = "Read this"): JsonObject = Json.parseToJsonElement(
    """{"title":"Original tour","headSha":"head-a","generatedAt":"$at","stops":[{"title":"Code","takeaway":"$text"}]}""",
).jsonObject
