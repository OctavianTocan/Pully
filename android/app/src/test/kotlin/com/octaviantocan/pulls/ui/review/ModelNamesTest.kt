package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.settings.CliModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelNamesTest {
    private fun model(id: String, name: String = id) = CliModel(id, name, null, emptyList(), null, false)

    @Test
    fun anIdIsWrittenAsAName() {
        assertEquals("GPT-6 Luna", prettyModelId("gpt-6-luna"))
        assertEquals("Claude Opus 4.1", prettyModelId("claude-opus-4-1"))
        assertEquals("GPT-5.5 Codex", prettyModelId("gpt-5.5-codex"))
    }

    @Test
    fun aRowNamedByItsIdGetsAName() {
        assertEquals("GPT-6 Luna", modelLabel(model("gpt-6-luna")))
        assertEquals("GPT-5.1 Codex Max", modelLabel(model("gpt-5.1-codex-max", "GPT-5.1-Codex-Max")))
    }
}
