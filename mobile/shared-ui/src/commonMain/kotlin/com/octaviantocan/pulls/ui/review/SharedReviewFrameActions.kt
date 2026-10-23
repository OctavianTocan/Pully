package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.AiSetupActions
import com.octaviantocan.pulls.ui.ai.AnalyzePage
import com.octaviantocan.pulls.ui.ai.AnalyzeSheet
import com.octaviantocan.pulls.ui.ai.AskSheet
import com.octaviantocan.pulls.ui.ai.ChatHistorySheet
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.ai.ReviewSheet
import com.octaviantocan.pulls.ui.ai.TourSheet
import com.octaviantocan.pulls.ui.ai.followQueuedTour
import com.octaviantocan.pulls.ui.ai.rememberAiSetupActions
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.settings.ModelSettingsServices

/** Both hosts use the original sheets, one model owner and the same actual request/setup flows. */
class SharedReviewFrameActions(
    private val models: ModelSettingsServices,
    native: NativeHomeActions,
    current: () -> Boolean,
    ready: () -> Boolean = { true },
    aiPolicy: () -> AiCapabilities = { AiCapabilities.Full },
    reviewPolicy: () -> ReviewCapabilities = { ReviewCapabilities.Full },
    checks: (@Composable (OpenTarget.Pull?, Boolean) -> Unit)? = null,
) : ReviewFrameActions {
    private val readiness = ReviewActionReadiness({ current() && ready() && models.current() }, aiPolicy, reviewPolicy)
    private val services = PrSheetServices(models, native, checks)

    /** The root's retained live port stays in scope; neither this factory nor a drawn policy can grant more. */
    @Composable
    internal fun Content(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalAiCapabilities provides readiness.installedAi(LocalAiCapabilities.current),
            LocalReviewCapabilities provides readiness.installedReview(LocalReviewCapabilities.current),
            content = content,
        )
    }

    @Composable
    override fun FollowQueuedTour(session: ReviewSession) = Content {
        val actions = rememberAiSetupActions(session, models)
        followQueuedTour(session, if (actions.canTour) ({ actions.update(); Unit }) else null)
    }

    override fun critique(session: ReviewSession, lens: String?) {
        AiSetupActions(session, models, session.captureView(), readiness) { AiCapabilities.Full }.critique(lens)
    }

    @Composable
    override fun Sheet(session: ReviewSession, ui: DiffUi, top: ReviewTopUi, data: PrOverviewData?, head: PrHead, request: ReviewFrameSheet) = Content {
        val actions = rememberAiSetupActions(session, models)
        when (request.name) {
            "more" -> PrSheet(session, top, data, head, request.prStart, services, request.onClose, request.onReview)
            "tourmore" -> TourSheet(session, models, services.native, onClose = request.onClose, onAsk = request.onAsk, onDiff = request.onChanges, onPrActions = request.onPrActions, start = request.tourStart)
            "tools" -> PrSheet(session, top, data, head, PrPage.Tools, services, request.onClose, request.onReview, ui, request.file)
            "findings" -> FindingsSheet(session, models, request.onClose)
            "analyze" -> AnalyzeSheet(session, models, onClose = request.onClose)
            "tour" -> AnalyzeSheet(session, models, onClose = request.onClose, start = AnalyzePage.Tour, onTour = request.onTour)
            "model" -> AnalyzeSheet(session, models, onClose = request.onClose, start = AnalyzePage.Model)
            "review" -> ReviewSheet(session, models, onClose = request.onClose) { lens ->
                if (actions.canCritique) {
                    request.onClose()
                    if (actions.canCritique) request.onReview(lens)
                }
            }
            else -> error("Unknown review action sheet: ${request.name}")
        }
    }

    @Composable
    override fun History(session: ReviewSession, onClose: () -> Unit, onOpen: () -> Unit) = Content {
        ChatHistorySheet(session, onClose, onOpen)
    }

    @Composable
    override fun Ask(session: ReviewSession, request: ReviewFrameAsk, onOpen: (String, Int) -> Unit, onClose: () -> Unit) = Content {
        AskSheet(session, request.title, request.suggestions, request.initial, request.reference, request.scroll, request.returning, onOpen = { onOpen(it.path, it.line) }, onDismiss = onClose)
    }

    @Composable
    override fun Submit(session: ReviewSession, top: ReviewTopUi, canVerdict: Boolean) = Content {
        ReviewSubmitSheet(session, top, canVerdict)
    }
}
