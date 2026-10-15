package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.ui.review.patchesAround
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A lite diff: files whose patches the server left out, and the patches it sends later. */
class LiteDiffTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private val hunk = """{"header":"@@ -1,2 +1,3 @@","oldStart":1,"oldLines":2,"newStart":1,"newLines":3,"patch":"@@ -1,2 +1,3 @@\n line one\n-line two\n+line 2\n+line three"}"""

    private val opened = json(
        """{"key":"github:acme/app#270","stale":true,"diff":{"title":"Huge","files":[
            {"path":"a.ts","status":"modified","additions":2,"deletions":1,"hunks":[$hunk]},
            {"path":"b.ts","status":"modified","additions":900,"deletions":3,"hunks":[],"patchOmitted":true},
            {"path":"c.ts","status":"added","additions":40,"deletions":0,"hunks":[],"patchOmitted":true}
        ]},"groups":[],"omitted":2}""",
    ).asOpened()

    @Test
    fun aLiteDiffMarksTheFilesWithoutTheirPatch() {
        assertEquals(listOf(false, true, true), opened.files.map { it.patchOmitted })
        assertEquals(4, opened.files.first().patchLines.size)
        assertTrue(opened.files[1].patchLines.isEmpty())
        // The stored copy GitHub could not confirm says so.
        assertTrue(opened.stale)
    }

    @Test
    fun aPatchSentLaterParsesAsTheWholeDiffWouldHave() {
        val patches = json("""{"files":{"b.ts":{"hunks":[$hunk]},"gone.ts":{"hunks":[]}}}""").asPatches()
        assertEquals(setOf("b.ts", "gone.ts"), patches.keys)
        val filled = opened.files[1].withHunks(patches.getValue("b.ts"))
        assertFalse(filled.patchOmitted)
        assertEquals(opened.files.first().patchLines, filled.patchLines)
        assertEquals(opened.files.first().hunks, filled.hunks)
        assertEquals(900, filled.additions)
    }

    @Test
    fun aFileOnScreenAsksForItselfAndTheNextLeftOutOnes() {
        val many = (1..10).map { ReviewFile("f$it.ts", "modified", 1, 1, patchLines = emptyList(), patchOmitted = it % 2 == 0) }
        assertEquals(listOf("f4.ts", "f6.ts", "f8.ts", "f10.ts"), patchesAround(many, "f4.ts"))
        // The file asked for comes first even when its own patch is there, and at most six go in one request.
        assertEquals(listOf("f1.ts", "f2.ts", "f4.ts", "f6.ts", "f8.ts", "f10.ts"), patchesAround(many, "f1.ts"))
        assertEquals(listOf("f9.ts", "f10.ts"), patchesAround(many, "f9.ts"))
    }

    @Test
    fun anOlderServerSendsNoMarksAndEveryPatchIsThere() {
        val old = json("""{"key":"k","diff":{"files":[{"path":"a.ts","status":"modified","additions":2,"deletions":1,"hunks":[$hunk]}]},"groups":[]}""").asOpened()
        assertFalse(old.stale)
        assertFalse(old.files.single().patchOmitted)
    }
}
