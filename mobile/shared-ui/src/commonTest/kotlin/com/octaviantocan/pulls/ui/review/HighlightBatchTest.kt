package com.octaviantocan.pulls.ui.review

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlightBatchTest {
    private fun response(lines: List<String>) = JsonObject(mapOf("lines" to JsonArray(lines.map { text ->
        JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(text)))))
    })))

    @Test
    fun everyLineAtAndAboveTheServerLimitIsTokenized() {
        for (count in listOf(400, 401, 1500)) {
            val texts = (1..count).map { "const value$it = $it" }
            val cached = mutableMapOf<String, List<Tok>>()
            for (chunk in texts.chunked(HIGHLIGHT_BATCH_SIZE)) {
                assertTrue(chunk.size <= 400)
                cached.putAll(readHighlight(response(chunk.take(400)), chunk).second.orEmpty())
            }
            assertEquals(texts.toSet(), cached.keys)
            for (text in texts) assertEquals(text, cached.getValue(text).single().text)
        }
    }

    @Test
    fun missingAndMalformedRowsStayUncachedAndCanBeRetried() {
        val chunk = listOf("first", "missing", "last")
        val short = readHighlight(response(chunk.take(1)), chunk).second.orEmpty().toMutableMap()
        assertEquals(setOf("first"), short.keys)
        val missing = chunk.filter { it !in short }
        short.putAll(readHighlight(response(missing), missing).second.orEmpty())
        assertEquals(chunk.toSet(), short.keys)

        val malformed = JsonObject(mapOf("lines" to JsonArray(listOf(JsonArray(emptyList()), JsonPrimitive("not tokens")))))
        val cached = readHighlight(malformed, chunk).second.orEmpty()
        assertTrue("first" in cached) // An explicit empty token row is a real result.
        assertFalse("missing" in cached)
        assertFalse("last" in cached)
    }
}
