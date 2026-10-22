package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.Brief
import com.octaviantocan.pulls.ui.ai.AiMarkdown
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.BriefSection
import com.octaviantocan.pulls.ui.ai.ProvideAiText
import com.octaviantocan.pulls.ui.ai.SharedAnalysisBannerImpl
import com.octaviantocan.pulls.ui.ai.rememberBriefRewriteActions
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PullsType

/**
 * Actual Overview result renderers, shared by the existing Android provider and the native host.
 * A host keeps reading disabled until its native SVG/route integration is verified. A null
 * regeneration action explicitly withholds paid controls; it is not an empty action implementation.
 * Supplying that action requires the host's complete paid setup, confirmation and result routes.
 */
open class SharedOverviewAiContent(
    private val policy: () -> AiCapabilities,
    private val engineAvailable: @Composable () -> Boolean,
    private val regenerateBrief: ((ReviewSession) -> Unit)?,
) : OverviewAiContent {
    constructor(
        capabilities: AiCapabilities,
        engineAvailable: @Composable () -> Boolean,
        regenerateBrief: ((ReviewSession) -> Unit)?,
    ) : this({ capabilities }, engineAvailable, regenerateBrief)

    private val capabilities: AiCapabilities get() = policy()

    override val canReadResults: Boolean get() = capabilities.canReadResults
    // Overview's existing setup menu exposes the whole Analyze/Review/Tour family.
    // A partial native host keeps that entry unavailable until all of its routes work.
    override val canStartPaidWork: Boolean get() = regenerateBrief != null && capabilities.run {
        canAnalyze && canCritique && canTour && canBrief
    }

    @Composable
    override fun Summary(session: ReviewSession, text: String, onCite: (Cite) -> Unit) {
        val c = LocalPullsColors.current
        ProvideAiText(session, { place -> onCite(Cite(place.path, place.line)) }) {
            Markdown(text, style = PullsType.read.copy(color = c.text))
        }
    }

    @Composable
    override fun Brief(session: ReviewSession, brief: Brief, onCite: (Cite) -> Unit, modifier: Modifier) {
        val actions = rememberBriefRewriteActions(session, brief)
        ProvideAiText(session, { place -> onCite(Cite(place.path, place.line)) }) {
            BriefSection(
                session, brief,
                onOpen = { target -> onCite(Cite(target.path, target.line ?: 0)) },
                onRegenerate = regenerateBrief?.takeIf { capabilities.canBrief && actions.canRewrite }?.let { action ->
                    { actions.rewrite { action(session) } }
                },
                modifier = modifier,
            )
        }
    }

    @Composable
    override fun NeedsHuman(session: ReviewSession, onCite: (Cite) -> Unit, onSheet: (String) -> Unit) =
        com.octaviantocan.pulls.ui.ai.NeedsHuman(session, engineAvailable(), canStartPaidWork, capabilities.canCritique, onCite, onSheet)

    @Composable
    override fun SharedBanner(session: ReviewSession, modifier: Modifier) = SharedAnalysisBannerImpl(session, modifier)

    @Composable
    override fun FileNote(session: ReviewSession, text: String) {
        val c = LocalPullsColors.current
        AiMarkdown(session, text, style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.4f, color = c.muted), maxLines = 2)
    }
}
