package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PUBLIC_REVIEW_SERVER
import com.octaviantocan.pulls.data.PublicReviewTransport
import com.octaviantocan.pulls.data.PullSizes
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.home.TourIndex
import kotlinx.coroutines.CoroutineScope

/** A pull request Explore can open in Pully: one on github.com, by a valid owner, repository and number. */
fun publicPullTarget(screen: Screen): OpenTarget.Pull? =
    ((screen as? Screen.Review)?.target as? OpenTarget.Pull)?.takeIf { it.host == "github.com" && it.number > 0 }

/**
 * The existing review page for a public pull request, read from GitHub's public API with no Pully server, account,
 * Access credential or AI behind it. Reading works as it does for a signed-in reader (Overview, files, diffs, whole
 * files, comments); every action that writes, asks AI or needs an account stays absent ([ReviewCapabilities.ReadOnly]).
 */
class PublicReviewFrame(
    transport: RpcTransport,
    scope: CoroutineScope,
    private val settings: AppSettings,
    private val native: NativeHomeActions,
    private val back: ReviewFrameBack,
    private val current: () -> Boolean,
    nowMillis: () -> Long,
) {
    private val repo = Repo({ RepoConfiguration(PUBLIC_REVIEW_SERVER) }, PublicReviewTransport(transport, nowMillis))
    private val cache = ReviewSessionCache(scope)
    private val sizes = PullSizes.source(PUBLIC_REVIEW_SERVER)
    private val overview = object : OverviewServices {
        override val native = this@PublicReviewFrame.native
        override val sizes = this@PublicReviewFrame.sizes
        override val ai = SharedOverviewAiContent(AiCapabilities.None, { false }, null)
        override val canEditText = false
        @Composable override fun EditBack(onBack: () -> Unit) = Unit
        @Composable override fun TrackSearch(scope: String, query: String, count: Int) = Unit
    }
    private val services = ReviewFrameServices(
        cache, overview, TourIndex(null), current,
        createSession = { target, sessionScope, from ->
            ReviewSession(target, repo, sessionScope, settings, openedFrom = from, cache = cache, effects = NoSessionEffects)
        },
        preview = { null }, nowMillis = nowMillis, track = { _, _ -> }, back = back,
        reopened = null, actions = null, commands = null, canReadTour = false,
    )

    @Composable
    fun Page(screen: Screen.Review, nav: Nav) {
        CompositionLocalProvider(
            LocalReviewCapabilities provides ReviewCapabilities.ReadOnly,
            LocalAiCapabilities provides AiCapabilities.None,
        ) {
            ReviewScreen(screen.target, nav, services, startOnTour = false, openedFrom = screen.from, place = screen.place)
        }
    }

    fun close() { sizes.invalidate() }
}
