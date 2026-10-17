package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.core.BriefBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ReviewModelsTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun aTaskKeepsWhatItWasAskedToStartAfterItselfAndTheTitleOfItsPull() {
        val task = json("""{"id":"t","kind":"analyze","key":"k","status":"running","title":"Fix cache","then":["tour","brief"],"activities":[]}""").jsonObject.asTaskState()
        assertEquals(listOf("tour", "brief"), task.then)
        assertEquals("Fix cache", task.title)
        assertEquals(emptyList(), json("""{"id":"t","kind":"chat","key":"k"}""").jsonObject.asTaskState().then)
    }

    @Test
    fun theSavedStateCarriesTheBriefAsTypedBlocks() {
        val state = json(
            """{"threads":[],"chat":[],"aiGroups":[],"brief":{"headSha":"h1","model":"sonnet","generatedAt":"2026-10-14T00:00:00Z",
                "tree":{"type":"Stack","props":{"children":[{"type":"Text","props":{"markdown":"Read this first."}}]}}}}""",
        ).asSavedState()
        val brief = state.brief
        assertEquals("h1", brief?.headSha)
        assertEquals("sonnet", brief?.model)
        assertIs<BriefBlock.Text>(brief?.page?.blocks?.single())
    }

    @Test
    fun noBriefOrAnEmptyOneIsNoBrief() {
        assertNull(json("""{"threads":[],"chat":[],"aiGroups":[]}""").asSavedState().brief)
        assertNull(json("""{"threads":[],"chat":[],"aiGroups":[],"brief":{"generatedAt":"x","tree":{"type":"Stack","props":{"children":[]}}}}""").asSavedState().brief)
    }

    @Test
    fun aLensShowsItsTitleAndFallsBackToItsName() {
        val lens = json("""{"name":"security","title":"Security","description":"Places where a bad input gets in."}""").jsonObject.asReviewLens()
        assertEquals(ReviewLens("security", "Security", "Places where a bad input gets in."), lens)
        // A server from before titles sends only the name and the description.
        assertEquals(ReviewLens("perf", "perf", null), json("""{"name":"perf"}""").jsonObject.asReviewLens())
    }

    @Test
    fun followUpQuestionsComeWithTheSavedChat() {
        assertEquals(listOf("What evicts entries?"), json("""{"threads":[],"chat":[],"aiGroups":[],"suggestions":["What evicts entries?"]}""").asSavedState().suggestions)
        assertEquals(emptyList(), json("""{"threads":[],"chat":[],"aiGroups":[]}""").asSavedState().suggestions)
    }

    @Test
    fun critiqueKeepsTheExactRevisionNeededForPosting() {
        val state = json("""{"critique":{"summary":"S","findings":[],"headSha":"h1","variant":"h1:codex:security","revision":"content-hash","generatedAt":"2026-10-15"}}""").asSavedState()
        assertEquals("h1", state.critique?.headSha)
        assertEquals("h1:codex:security", state.critique?.variant)
        assertEquals("content-hash", state.critique?.revision)
        // Legacy responses still display, but cannot authorize posting an unidentified result.
        val legacy = json("""{"summary":"S","findings":[]}""").jsonObject.asCritique()
        assertNull(legacy.variant)
        assertNull(legacy.revision)
    }

}
