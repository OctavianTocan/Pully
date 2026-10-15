package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.settings.CliModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The short list a model picker shows first, and the names it shows (rule 89). */
class ModelPickerTest {
    private fun model(id: String, name: String, description: String? = null, isDefault: Boolean = false) =
        CliModel(id, name, description, emptyList(), null, isDefault)

    private val claude = listOf(
        model("default", "Default (recommended)", "Use the default model (currently Opus 5.5) · \$5/\$25 per Mtok", isDefault = true),
        model("claude-opus-5-5", "Opus 5.5"),
        model("claude-fable-5-1", "Fable 5.1"),
        model("claude-sonnet-5-5", "Sonnet 5.5"),
        model("claude-haiku-4-5", "Haiku 4.5"),
        model("claude-opus-4-1", "Opus 4.1"),
    )

    private fun names(models: List<CliModel>) = models.map { it.name }

    @Test fun theNewestGenerationComesFirstAndTheRestFolds() {
        val groups = groupModels(claude)
        assertEquals(listOf("Default (recommended)", "Opus 5.5", "Fable 5.1", "Sonnet 5.5"), names(groups.recent))
        assertEquals(listOf("Haiku 4.5", "Opus 4.1"), names(groups.older))
    }

    @Test fun onlyTheNewestOfAFamilyIsRecent() {
        val groups = groupModels(claude + model("claude-opus-5", "Opus 5"))
        assertEquals(listOf("Opus 5"), names(groups.older).filter { it == "Opus 5" })
        assertEquals(1, groups.recent.count { it.name.startsWith("Opus") })
    }

    @Test fun anAliasStandsForTheNewestSoItIsNeverFolded() {
        val groups = groupModels(claude + model("opus[1m]", "Opus (1M context)"))
        assertEquals(true, groups.recent.any { it.name == "Opus (1M context)" })
    }

    @Test fun aSizeIsNotAVersion() {
        assertNull(modelVersion(model("opus[1m]", "Opus (1M context)")))
        assertEquals(5 to 5, modelVersion(model("x", "Opus 5.5")))
        assertEquals(4 to 1, modelVersion(model("claude-opus-4-1", "Opus")))
        assertEquals(5 to 0, modelVersion(model("x", "Sonnet 5")))
    }

    @Test fun aListWithNothingOlderHasNoSecondGroup() {
        val groups = groupModels(claude.take(4))
        assertEquals(emptyList(), groups.older)
    }

    @Test fun aListWithoutVersionsIsAllRecent() {
        val groups = groupModels(listOf(model("default", "Default", isDefault = true), model("opus", "Opus")))
        assertEquals(2, groups.recent.size)
    }

    @Test fun codexKeepsTheNewestOfEachFamily() {
        val codex = listOf(
            model("gpt-5.5-codex", "GPT-5.5-Codex", isDefault = true),
            model("gpt-5.1-codex-max", "GPT-5.1-Codex-Max"),
            model("gpt-5.5", "GPT-5.5"),
            model("gpt-5.1", "GPT-5.1"),
        )
        val groups = groupModels(codex)
        assertEquals(listOf("GPT-5.5-Codex", "GPT-5.5"), names(groups.recent))
        assertEquals(listOf("GPT-5.1-Codex-Max", "GPT-5.1"), names(groups.older))
    }

    @Test fun theDefaultIsNeverFoldedEvenWhenOld() {
        val groups = groupModels(listOf(model("a", "Alpha 5.5"), model("b", "Beta 3.1", isDefault = true)))
        assertEquals(listOf("Alpha 5.5", "Beta 3.1"), names(groups.recent))
    }

    @Test fun aNameLosesItsRecommendedAndItsHyphens() {
        assertEquals("Default", modelLabel(model("default", "Default (recommended)")))
        assertEquals("GPT-5.1 Codex Max", modelLabel(model("x", "GPT-5.1-Codex-Max")))
        assertEquals("GPT-5.5", modelLabel(model("x", "gpt-5.5")))
        assertEquals("Opus 5.5", modelLabel(model("x", "Opus 5.5")))
        assertEquals("o3 mini", modelLabel(model("x", "o3-mini")))
    }

    /** Claude Code's own list: aliases named by family, the version at the head of the description. */
    private val aliases = listOf(
        model("default", "Default (recommended)", "Sonnet 5.5 · Efficient for routine tasks", isDefault = true),
        model("sonnet", "Sonnet", "Sonnet 5.5 · Efficient for routine tasks"),
        model("fable", "Fable", "Fable 5.1 · Most capable for your hardest and longest-running tasks"),
        model("opus", "Opus", "Opus 5.5 · Best for everyday, complex tasks"),
        model("haiku", "Haiku", "Haiku 5.5 · Fastest for quick answers"),
        model("claude-haiku-4-5-20251001", "Haiku 4.5", "Fastest for quick answers"),
        model("claude-sonnet-5", "Sonnet 5", "Efficient for routine tasks"),
        model("claude-opus-4-8", "Opus 4.8"),
    )

    @Test fun anAliasNamedByItsFamilyReadsItsVersionFromTheDescription() {
        val groups = groupModels(aliases)
        assertEquals(listOf("Default", "Sonnet 5.5", "Fable 5.1", "Opus 5.5", "Haiku 5.5"), groups.recent.map(::modelLabel))
        assertEquals(listOf("Haiku 4.5", "Sonnet 5", "Opus 4.8"), groups.older.map(::modelLabel))
        assertEquals(5 to 5, modelVersion(aliases[3]))
        // The default's description names the model it stands for, not its own version.
        assertNull(modelVersion(aliases[0]))
        assertEquals("Sonnet 5.5", modelNote(aliases[0]))
    }

    @Test fun onlyTheDefaultCarriesALine() {
        assertEquals("Use the default model (currently Opus 5.5)", modelNote(claude[0]))
        assertNull(modelNote(claude[1]))
        assertNull(modelNote(model("gpt", "GPT-5.5", "Latest frontier agentic coding model.", isDefault = true)))
    }
}
