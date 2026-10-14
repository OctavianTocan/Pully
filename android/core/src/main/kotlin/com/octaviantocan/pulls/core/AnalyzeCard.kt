package com.octaviantocan.pulls.core

enum class StepMark { Pending, Active, Done, Failed, Stopped }

data class CardStep(val label: String, val mark: StepMark)

/**
 * What the Analyze card on the overview says: its title, the steps beneath it, and what a reviewer may do next.
 * [restart] is set when the analysis itself did not finish (failed or stopped), so a way back is always on screen.
 */
data class AnalyzeCard(val title: String, val steps: List<CardStep>, val working: Boolean, val restart: Boolean)

/**
 * Builds the card from task statuses (`running`, `done`, `failed`, `cancelled`, or `null` for no task) and
 * from what is already saved. [then] is what the analysis was asked to start after itself, so a step is on
 * screen from the first moment and not when its task appears.
 */
fun analyzeCard(
    analyze: String?,
    tour: String?,
    brief: String?,
    hasTour: Boolean,
    hasBrief: Boolean,
    then: List<String> = emptyList(),
): AnalyzeCard {
    val analyzing = analyze == "running"
    val tourRunning = tour == "running"
    val briefRunning = brief == "running"
    val title = when {
        analyzing -> "Analyzing"
        analyze == "failed" -> "Analysis failed"
        analyze == "cancelled" -> "Analysis stopped"
        tourRunning && briefRunning -> "Writing the tour and the brief"
        tourRunning -> "Writing the tour"
        briefRunning -> "Designing the brief"
        else -> "Analyzed"
    }
    val stopped = analyze == "cancelled"
    val notes = when {
        analyzing -> StepMark.Active
        analyze == "failed" -> StepMark.Failed
        stopped -> StepMark.Stopped
        else -> StepMark.Done
    }
    val summary = if (analyze == "failed" || stopped || analyzing) StepMark.Pending else StepMark.Done

    fun followUp(status: String?, has: Boolean): StepMark = when {
        status == "running" -> StepMark.Active
        has -> StepMark.Done
        status == "failed" -> StepMark.Failed
        status == "cancelled" -> StepMark.Stopped
        else -> StepMark.Pending
    }

    val steps = buildList {
        add(CardStep("File notes", notes))
        add(CardStep("Summary", summary))
        // A pass is on the card when it was asked for, is running, or already has a result; Quick shows neither.
        if ("tour" in then || tour != null || hasTour) add(CardStep("Tour", followUp(tour, hasTour)))
        if ("brief" in then || brief != null || hasBrief) add(CardStep("Brief", followUp(brief, hasBrief)))
    }
    return AnalyzeCard(title, steps, working = analyzing || tourRunning || briefRunning, restart = analyze == "failed" || stopped)
}
