package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.PlaceRequest
import com.octaviantocan.pulls.ui.ai.AskReference
import com.octaviantocan.pulls.ui.ai.AnalyzePage
import com.octaviantocan.pulls.ui.ai.TourSheetPage
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.home.TourIndex
import kotlinx.coroutines.CoroutineScope

/** One native source owns the same session, cached row preview and Tour index as Home. */
class ReviewFrameServices(
    val cache: ReviewSessionCache,
    val overview: OverviewServices,
    val tours: TourIndex,
    val current: () -> Boolean,
    val createSession: (OpenTarget, CoroutineScope, String) -> ReviewSession,
    val preview: (OpenTarget) -> PullPreview?,
    val nowMillis: () -> Long,
    val track: (String, Array<out Pair<String, Any?>>) -> Unit,
    val back: ReviewFrameBack,
    val reopened: (@Composable () -> ReviewFrameReopen?)?,
    val actions: ReviewFrameActions?,
    val commands: ReviewFrameCommands?,
    val canReadTour: Boolean,
)

/** Native back translates input only; the shared frame owns the nested page stack. */
interface ReviewFrameBack {
    @Composable fun Page(motion: PageMotion, enabled: Boolean, onBack: () -> Unit)
    @Composable fun Priority(enabled: Boolean, onBack: () -> Unit)
}

/** A native incoming-link event, with no second route stack or duplicated parsing. */
class ReviewFrameReopen(val target: OpenTarget, val place: PlaceRequest?, val aiResult: String?) {
    val overview = OverviewReopen(target)
}

/** Only complete host workflows install these actions; unavailable controls are absent before work. */
interface ReviewFrameActions {
    @Composable fun FollowQueuedTour(session: ReviewSession)
    fun critique(session: ReviewSession, lens: String?)
    @Composable fun Sheet(session: ReviewSession, ui: DiffUi, top: ReviewTopUi, data: PrOverviewData?, head: PrHead, request: ReviewFrameSheet)
    @Composable fun History(session: ReviewSession, onClose: () -> Unit, onOpen: () -> Unit)
    @Composable fun Ask(session: ReviewSession, request: ReviewFrameAsk, onOpen: (path: String, line: Int) -> Unit, onClose: () -> Unit)
    @Composable fun Submit(session: ReviewSession, top: ReviewTopUi, canVerdict: Boolean)
}

class ReviewFrameSheet(
    val name: String,
    val prStart: PrPage,
    val tourStart: TourSheetPage,
    val file: ReviewFile?,
    val onClose: () -> Unit,
    val onAsk: () -> Unit,
    val onChanges: () -> Unit,
    val onPrActions: () -> Unit,
    val onTour: () -> Unit,
    val onReview: (String?) -> Unit,
)

/** The original Ask sheet's draft/scroll/returning state remains owned by the shared review frame. */
data class ReviewFrameAsk(
    val title: String,
    val suggestions: List<String>,
    val initial: String?,
    val reference: AskReference? = null,
    val scroll: ScrollState = ScrollState(0),
    val returning: Boolean = false,
)

/** The existing Android palette remains required until its complete renderer is shared. */
interface ReviewFrameCommands {
    @Composable fun Effect(
        session: ReviewSession, files: () -> List<ReviewFile>, path: String?, onGo: (String) -> Unit,
        onAnalyze: () -> Unit, onDiff: Boolean, head: PrHead?, onOverview: Boolean, onReviewFiles: () -> Unit,
        onAsk: () -> Unit, onReview: (String) -> Unit,
    )
    @Composable fun Pull(modifier: Modifier, content: @Composable () -> Unit)
}
