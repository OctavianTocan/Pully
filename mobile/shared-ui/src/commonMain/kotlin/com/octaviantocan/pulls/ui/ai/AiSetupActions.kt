package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.aikit.AiSettingsLogic
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.ui.review.LocalReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.settings.ModelSettingsServices
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A preset is available only when every output in its existing chain is ready. */
fun AiCapabilities.canRunAnalysis(choice: AnalyzeChoice): Boolean =
    canAnalyze && (!choice.tour || canTour) && (!choice.brief || canBrief) && (!choice.review || canCritique)

/** The original server-owned analysis/Brief/Tour chain and parallel review request. */
fun ReviewSession.runAnalyze(models: ModelSettingsServices, capabilities: AiCapabilities, choice: AnalyzeChoice = settings.analyzeChoice): Boolean {
    if (!models.current() || !capabilities.canRunAnalysis(choice) || captureView() == null || taskFor("analyze")?.running == true) return false
    val config = models.configuration()
    val efforts = AiSettingsLogic.current(models.cached(config.engine), config.model)?.efforts.orEmpty()
    val run = choice.run(efforts)
    if (!models.current()) return false
    startAiTask("analyze", extra = { put("locale", settings.locale) }, effort = run.effort, then = run.then)
    if (run.review && taskFor("critique")?.running != true) runCritique(models, capabilities, null, run.effort)
    return true
}

/** Same cache variant and request fields as the existing Android review launcher. */
fun ReviewSession.runCritique(models: ModelSettingsServices, capabilities: AiCapabilities, lens: String?, effort: String? = null): Boolean {
    if (!models.current() || !capabilities.canCritique || captureView() == null) return false
    val config = models.configuration()
    val experimental = settings.experiments.mergeRisk
    val variant = critiqueVariant(opened?.headSha, config.engine, config.model, (effort ?: settings.effort).ifBlank { null }, lens) + if (experimental) ":experimental-merge-risk" else ""
    if (!models.current()) return false
    startAiTask("critique", effort = effort, extra = {
        put("variant", variant)
        if (lens != null) put("lens", lens)
        if (experimental) put("experiments", buildJsonObject { put("mergeRisk", true) })
    })
    if (experimental) trackMergeRisk("review")
    return true
}

/** Starts the actual tour in the reader's language, without replacing a running task. */
fun startTour(session: ReviewSession, capabilities: AiCapabilities): Boolean {
    if (!capabilities.canTour || session.captureView() == null || session.taskFor("tour")?.running == true) return false
    session.startTask("tour", { put("locale", session.settings.locale) })
    return true
}

/** Queue behind an existing analysis, or preserve the last complete preset before the first tour. */
fun generateTour(session: ReviewSession, models: ModelSettingsServices, capabilities: AiCapabilities): Boolean {
    if (!models.current() || !capabilities.canTour || session.captureView() == null) return false
    val analysis = session.taskFor("analyze")
    return when {
        analysis?.running == true -> {
            if ("tour" !in analysis.then) session.tourQueued = true
            true
        }
        session.hasAiResult -> startTour(session, capabilities)
        else -> session.runAnalyze(models, capabilities, session.settings.analyzeChoice.copy(tour = true))
    }
}

/** A launch result describes actual work, so an unavailable target never produces a started toast. */
internal enum class AnalysisStart { Unavailable, AlreadyRunning, Started }

/** Retained setup controls belong to the drawn full view and read the latest host readiness. */
internal class AiSetupActions(
    private val session: ReviewSession,
    private val models: ModelSettingsServices,
    private val owner: ReviewSession.Version?,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
    private val capabilities: () -> AiCapabilities,
) {
    private var disposed = false
    val current: Boolean get() = !disposed && models.current() && session.captureTourRead() != null && session.captureView() == owner
    fun canAnalyze(choice: AnalyzeChoice): Boolean = current && readiness.ai(capabilities()).canRunAnalysis(choice)
    fun canStartAnalysis(choice: AnalyzeChoice): Boolean = owner != null && canAnalyze(choice)
    val canTour: Boolean get() = current && readiness.ai(capabilities()).canTour
    val canGenerate: Boolean get() = canTour && (session.taskFor("analyze")?.running == true || session.hasAiResult || readiness.ai(capabilities()).canRunAnalysis(session.settings.analyzeChoice.copy(tour = true)))
    val canBrief: Boolean get() = current && readiness.ai(capabilities()).canBrief
    val canCritique: Boolean get() = current && readiness.ai(capabilities()).canCritique
    val canAsk: Boolean get() = current && readiness.ai(capabilities()).canAsk
    fun read(action: () -> Unit) { if (current && readiness.eligible) action() }
    fun ask(action: () -> Unit) { if (current && readiness.ai(capabilities()).canAsk) action() }
    fun analyze(choice: AnalyzeChoice): AnalysisStart {
        if (!canStartAnalysis(choice)) return AnalysisStart.Unavailable
        val running = session.taskFor("analyze")?.running == true
        if (!running && !session.runAnalyze(models, readiness.ai(capabilities()), choice)) return AnalysisStart.Unavailable
        session.settings.updateAnalyzeChoice(choice)
        return if (running) AnalysisStart.AlreadyRunning else AnalysisStart.Started
    }
    fun generate(): Boolean = canGenerate && generateTour(session, models, readiness.ai(capabilities()))
    fun critique(lens: String?): Boolean = owner != null && canCritique && session.runCritique(models, readiness.ai(capabilities()), lens)
    fun update(): Boolean = canTour && startTour(session, readiness.ai(capabilities()))
    fun close() { disposed = true }
}

@Composable
internal fun rememberAiSetupActions(session: ReviewSession, models: ModelSettingsServices): AiSetupActions {
    val latestCapabilities by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val owner = session.captureView()
    val actions = remember(session, models, owner, readiness) { AiSetupActions(session, models, owner, readiness) { latestCapabilities } }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
