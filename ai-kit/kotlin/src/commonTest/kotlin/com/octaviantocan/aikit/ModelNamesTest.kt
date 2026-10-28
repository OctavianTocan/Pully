package com.octaviantocan.aikit

import kotlin.test.assertEquals
import kotlin.test.Test

class ModelNamesTest {
    private fun model(id: String, name: String = id) = CliModel(id, name, null, emptyList(), null, false)

    @Test
    fun anIdIsWrittenAsAName() {
        assertEquals("GPT-6 Luna", prettyModelId("gpt-6-luna"))
        assertEquals("Claude Opus 4.1", prettyModelId("claude-opus-4-1"))
        assertEquals("GPT-5.5 Codex", prettyModelId("gpt-5.5-codex"))
    }

    /** An id saved before the server grouped Pi's list still reads cleanly where no catalog names it (K1, K3). */
    @Test
    fun aRoutePrefixThatRepeatsTheFamilyIsSaidOnce() {
        assertEquals("Claude Opus 5", prettyModelId("claude-claude-opus-5"))
        assertEquals("Claude Opus 5", prettyModelId("claude:claude-opus-5"))
        assertEquals("Claude Opus 4.8", prettyModelId("claude/claude-opus-4-8"))
        assertEquals("AGY Gemini 3.8 Flash", prettyModelId("agy-gemini-3.8-flash"))
    }

    @Test
    fun aSavedIdTheListDroppedTicksTheRowOfTheSameName() {
        val listed = listOf(model("claude-opus-5", "Claude Opus 5"), model("gpt-6-sol", "GPT-6 Sol"))
        assertEquals("claude-opus-5", sameNamed(listed, "claude-claude-opus-5")?.id)
        assertEquals(null, sameNamed(listed, "kimi-k3"))
        assertEquals(null, sameNamed(listed, ""))
    }

    @Test
    fun aRowNamedByItsIdGetsAName() {
        assertEquals("GPT-6 Luna", modelLabel(model("gpt-6-luna")))
        assertEquals("GPT-5.1 Codex Max", modelLabel(model("gpt-5.1-codex-max", "GPT-5.1-Codex-Max")))
    }
}
