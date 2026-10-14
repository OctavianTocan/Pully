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
    fun followUpQuestionsComeWithTheSavedChat() {
        assertEquals(listOf("What evicts entries?"), json("""{"threads":[],"chat":[],"aiGroups":[],"suggestions":["What evicts entries?"]}""").asSavedState().suggestions)
        assertEquals(emptyList(), json("""{"threads":[],"chat":[],"aiGroups":[]}""").asSavedState().suggestions)
    }
}
