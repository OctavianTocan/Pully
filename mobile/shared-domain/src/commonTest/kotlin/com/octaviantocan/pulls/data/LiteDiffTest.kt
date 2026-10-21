package com.octaviantocan.pulls.data

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
        val patches = json("""{"files":{"b.ts":{"hunks":[$hunk],"sha":"b1"},"gone.ts":{"hunks":[]}}}""").asPatches()
        assertEquals(setOf("b.ts", "gone.ts"), patches.keys)
        assertEquals("b1", patches.getValue("b.ts").sha)
        val filled = opened.files[1].withHunks(patches.getValue("b.ts").hunks)
        assertFalse(filled.patchOmitted)
        assertEquals(opened.files.first().patchLines, filled.patchLines)
        assertEquals(opened.files.first().hunks, filled.hunks)
        assertEquals(900, filled.additions)
    }

    @Test
    fun aPatchForAnotherVersionOfTheFileDoesNotFit() {
        val patch = FilePatch(json("[$hunk]") as kotlinx.serialization.json.JsonArray, "b2")
        assertFalse(opened.files[1].copy(sha = "b1").matches(patch))
        assertTrue(opened.files[1].copy(sha = "b2").matches(patch))
        // An older server sends no sha, and a file without one takes any patch.
        assertTrue(opened.files[1].copy(sha = "b1").matches(patch.copy(sha = "")))
        assertTrue(opened.files[1].matches(patch))
    }

    @Test
    fun anOlderServerSendsNoMarksAndEveryPatchIsThere() {
        val old = json("""{"key":"k","diff":{"files":[{"path":"a.ts","status":"modified","additions":2,"deletions":1,"hunks":[$hunk]}]},"groups":[]}""").asOpened()
        assertFalse(old.stale)
        assertFalse(old.files.single().patchOmitted)
    }
}
