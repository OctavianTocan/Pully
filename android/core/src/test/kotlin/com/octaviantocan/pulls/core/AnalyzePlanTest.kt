package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyzePlanTest {
    private val claude = listOf("low", "medium", "high", "xhigh", "max")

    @Test
    fun standardKeepsTheSettingsEffortAndStartsTheTourAndBrief() {
        assertEquals(AnalyzeRun(null, listOf("tour", "brief"), review = false), AnalyzeChoice.of(AnalyzePreset.Standard).run(claude))
    }

    @Test
    fun quickRunsAtTheLowestEffortAndNothingAfter() {
        assertEquals(AnalyzeRun("low", emptyList(), review = false), AnalyzeChoice.of(AnalyzePreset.Quick).run(claude))
        assertEquals("minimal", AnalyzeChoice.of(AnalyzePreset.Quick).run(listOf("high", "minimal", "medium")).effort)
    }

    @Test
    fun deepRunsAtHighAndReviewsToo() {
        assertEquals(AnalyzeRun("high", listOf("tour", "brief"), review = true), AnalyzeChoice.of(AnalyzePreset.Deep).run(claude))
    }

    @Test
    fun deepNeverPicksMaxAndFallsBackToTheMostThoroughOffered() {
        assertEquals("xhigh", AnalyzeChoice.of(AnalyzePreset.Deep).run(listOf("low", "xhigh", "max")).effort)
        assertEquals("medium", AnalyzeChoice.of(AnalyzePreset.Deep).run(listOf("low", "medium")).effort)
        assertNull(AnalyzeChoice.of(AnalyzePreset.Deep).run(listOf("max")).effort)
    }

    @Test
    fun aModelWithoutKnownEffortsLeavesTheSettingsEffortAlone() {
        assertNull(AnalyzeChoice.of(AnalyzePreset.Quick).run(emptyList()).effort)
        assertNull(AnalyzeChoice.of(AnalyzePreset.Deep).run(listOf("turbo")).effort)
    }

    @Test
    fun customizeKeepsTheSettingsEffortAndTheChosenPasses() {
        val choice = AnalyzeChoice(null, tour = false, brief = true, review = true)
        assertEquals(AnalyzeRun(null, listOf("brief"), review = true), choice.run(claude))
    }

    @Test
    fun theChoiceSurvivesPreferencesAndBadTextFallsBackToStandard() {
        for (preset in AnalyzePreset.entries) assertEquals(AnalyzeChoice.of(preset), AnalyzeChoice.decode(AnalyzeChoice.of(preset).encode()))
        val custom = AnalyzeChoice(null, tour = true, brief = false, review = true)
        assertEquals("custom:tour,review", custom.encode())
        assertEquals(custom, AnalyzeChoice.decode(custom.encode()))
        assertEquals(AnalyzeChoice(null, tour = false, brief = false, review = false), AnalyzeChoice.decode("custom:"))
        assertEquals(AnalyzeChoice.Default, AnalyzeChoice.decode(null))
        assertEquals(AnalyzeChoice.Default, AnalyzeChoice.decode("turbo"))
    }

    @Test
    fun theButtonNamesTheChoiceAndSaysWhatItProduces() {
        assertEquals("Standard", AnalyzeChoice.Default.title)
        assertEquals("Custom", AnalyzeChoice(null, tour = false, brief = false, review = false).title)
        assertEquals("A summary and a note per file.", AnalyzeChoice.of(AnalyzePreset.Quick).summary())
        assertEquals("A summary, a note per file, a tour and a brief.", AnalyzeChoice.of(AnalyzePreset.Standard).summary())
        assertEquals("A summary, a note per file, a tour, a brief and an AI review.", AnalyzeChoice.of(AnalyzePreset.Deep).summary())
        assertEquals("A summary, a note per file and an AI review.", AnalyzeChoice(null, tour = false, brief = false, review = true).summary())
    }
}
