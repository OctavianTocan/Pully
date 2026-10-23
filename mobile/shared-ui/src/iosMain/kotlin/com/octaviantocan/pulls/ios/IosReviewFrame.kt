package com.octaviantocan.pulls.ios

import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.AnalyticsRules
import com.octaviantocan.pulls.data.IosUsageAnalytics
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.ui.RootSource
import com.octaviantocan.pulls.ui.IncomingLinkInbox
import com.octaviantocan.pulls.ui.PlaceRequest
import androidx.compose.runtime.remember
import com.octaviantocan.pulls.ui.SharedBackHandler
import com.octaviantocan.pulls.ui.SharedPageBack
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.startBrief
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.home.HomeScreenServices
import com.octaviantocan.pulls.ui.home.PullsSnapshot
import com.octaviantocan.pulls.ui.home.TrackHomeSearch
import com.octaviantocan.pulls.ui.review.*
import com.octaviantocan.pulls.ui.settings.ModelSettingsServices
import kotlin.time.Clock

/** The actual review frame/session/Overview; UIKit only supplies the already-required native actions port. */
internal fun iosReviewFrameServices(
    source: RootSource,
    home: HomeScreenServices,
    cache: ReviewSessionCache,
    analytics: IosUsageAnalytics,
    models: ModelSettingsServices,
    tours: IosTourCopies,
    incoming: IncomingLinkInbox,
    aiPolicy: () -> AiCapabilities,
    reviewPolicy: () -> ReviewCapabilities,
    ready: () -> Boolean,
): ReviewFrameServices {
    val current = { source.current && home.data.current }
    val readiness = ReviewActionReadiness({ current() && ready() && models.current() }, aiPolicy, reviewPolicy)
    val overview = object : OverviewServices {
        override val native = home.native
        override val sizes = home.data.sizes
        override val ai = SharedOverviewAiContent(
            policy = { readiness.installedAi(AiCapabilities.Full) },
            engineAvailable = { models.current() && models.configuration().engine.isNotBlank() },
            regenerateBrief = { session -> startBrief(session, readiness.ai(AiCapabilities.Full)) },
        )
        override val canEditText: Boolean get() = readiness.review(ReviewCapabilities.Full).canEditPullRequest
        @Composable override fun EditBack(onBack: () -> Unit) = SharedBackHandler(onBack)
        @Composable override fun TrackSearch(scope: String, query: String, count: Int) = TrackHomeSearch(scope, query, count)
    }
    val effects = object : SessionEffects {
        override fun track(name: String, vararg props: Pair<String, Any?>) { if (current()) analytics.track(name, *props) }
        override fun error(name: String, message: String?) { if (current()) analytics.error(name, message) }
        override fun fileShown(path: String, lines: Int, layout: String) {
            if (current()) analytics.track("file.view", "kind" to AnalyticsRules.fileKind(path), "lines" to lines, "layout" to layout)
        }
        override fun cue(cue: SessionCue) {
            if (current()) ControlFeedback.play(if (cue == SessionCue.Ready) Cue.Ready else Cue.Error, Emphasis.Subtle)
        }
    }
    return ReviewFrameServices(
        cache, overview, home.home.tours, current,
        createSession = { target, _, from ->
            check(current()) { "The review's source changed." }
            ReviewSession(target, home.repo, source.scope, home.settings, tourStore = tours, openedFrom = from, cache = cache, effects = effects)
        },
        preview = { target ->
            val pull = target as? OpenTarget.Pull
            if (!current() || pull == null) null
            else pullPreview(pull, listOf(PullsSnapshot(home.home.sync.pulls.orEmpty(), home.home.sync.fetchedAt ?: 0L)))
                ?: home.home.tours.get("${pull.owner}/${pull.repo}#${pull.number}")?.prTitle?.takeIf(String::isNotBlank)?.let(::PullPreview)
        },
        nowMillis = { Clock.System.now().toEpochMilliseconds() },
        track = { name, props -> if (current()) analytics.track(name, *props) },
        back = object : ReviewFrameBack {
            @Composable override fun Page(motion: PageMotion, enabled: Boolean, onBack: () -> Unit) = SharedPageBack(motion, enabled, onBack)
            @Composable override fun Priority(enabled: Boolean, onBack: () -> Unit) { if (enabled) SharedBackHandler(onBack) }
        },
        // The same incoming-link owner reopens the existing shared stack.
        reopened = {
            val arrival = incoming.reopened.takeIf { current() }
            remember(arrival) { arrival?.let { ReviewFrameReopen(it.target, it.place?.let(::PlaceRequest), it.aiResult) } }
        },
        actions = SharedReviewFrameActions(models, home.native, current, ready, aiPolicy, reviewPolicy),
        commands = SharedReviewFrameCommands(home.native) { current() && ready() },
        canReadTour = true,
    )
}
