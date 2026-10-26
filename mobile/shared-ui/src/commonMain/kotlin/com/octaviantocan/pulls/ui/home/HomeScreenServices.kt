package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.PostedRow
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.review.ReviewSessionCache
import kotlinx.serialization.json.JsonObject

/** A host enables a route only once its actual renderer and every action it can start are installed. */
data class HomeScreenCapabilities(
    val reviewRoutes: Boolean,
    val tourRoutes: Boolean,
    val startTour: Boolean,
    val inboxAsk: Boolean,
    val taskObservation: Boolean,
    val postedDiffs: Boolean,
    val deletePosted: Boolean,
    val profileStats: Boolean,
    val settingsRoute: Boolean,
    val commands: Boolean,
    val uiBookRoute: Boolean = false,
)

enum class HomePreviewFeature { Review, Tour, InboxAsk, PostedDelete, Settings, UiBook, Commands }

/** One source-owned actual Inbox Ask surface, shared by Home and both native hosts. */
interface HomeAskSurface {
    /** Retire private conversation/draft state before publishing replacement credentials. */
    fun invalidate()
    fun sendOrQueue(question: String)
    /** True once this source's Inbox chat has a question in it, so a closed chat can be opened again from the search. */
    val hasChat: Boolean get() = false
    @Composable fun Content(listed: List<ListedPull>, onOpen: (ListedPull) -> Unit, onClose: () -> Unit)
}

interface HomeTourCopies {
    suspend fun contains(pull: String): Boolean
    suspend fun put(pull: String, tour: JsonObject)
}

/** Native first-party analytics and the existing Android instant-size hint; no content is recorded. */
interface HomeScreenEffects {
    fun track(name: String, vararg props: Pair<String, Any?>)
    fun error(name: String, message: String?)
    fun size(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?)
}

/** Required host services, never inferred from a platform context inside production screen code. */
class HomeScreenServices(
    val repo: Repo,
    val settings: AppSettings,
    val home: HomeState,
    val data: HomeScreenData,
    val native: NativeHomeActions,
    val effects: HomeScreenEffects,
    val capabilities: HomeScreenCapabilities,
    val unavailable: (HomePreviewFeature) -> Unit,
    val openCommands: () -> Unit,
    val reviewSessions: ReviewSessionCache? = null,
    val tourCopies: HomeTourCopies? = null,
    val ask: HomeAskSurface? = null,
    val observations: HomeObservations = HomeObservations(),
) {
    init {
        require(!capabilities.inboxAsk || ask != null) { "Inbox Ask needs its complete renderer before it can read or start work." }
        require(!capabilities.reviewRoutes || reviewSessions != null) { "Review routes need their shared session owner." }
        require(!capabilities.startTour || capabilities.tourRoutes) { "Starting a tour needs its complete result route." }
        require(!capabilities.deletePosted || capabilities.postedDiffs) { "Deleting posted diffs needs their account list." }
    }

    fun commands() {
        if (!data.current) return
        if (capabilities.commands) openCommands() else unavailable(HomePreviewFeature.Commands)
    }

    fun sendQuestion(question: String) {
        if (!data.current) return
        val surface = ask
        if (!capabilities.inboxAsk || surface == null) unavailable(HomePreviewFeature.InboxAsk)
        else surface.sendOrQueue(question)
    }

    fun warmReview(pull: PullRow) {
        if (!data.current || !capabilities.reviewRoutes) return
        reviewSessions?.warm(repo, OpenTarget.Pull(pull.owner, pull.repo, pull.number, "github.com"))
    }

    /** The live gate admits a new write; its same-source receipt survives an ordinary scene pause. */
    suspend fun deletePosted(row: PostedRow): Boolean {
        if (!data.current || !observations.ready || !capabilities.deletePosted) return false
        data.posted.delete(row)
        return data.current
    }

    private fun current(action: () -> Unit) { if (data.current) action() }

    /** Unsupported destinations give an explicit limitation before any navigation or paid work. */
    fun navigation(nav: Nav): Nav = Nav(
        openTarget = { target -> current { if (capabilities.reviewRoutes) nav.openTarget(target) else unavailable(HomePreviewFeature.Review) } },
        openTour = { target -> current { if (capabilities.tourRoutes) nav.openTour(target) else unavailable(HomePreviewFeature.Tour) } },
        openFrom = { target, from, tour ->
            current {
                if (tour) { if (capabilities.tourRoutes) nav.openFrom(target, from, true) else unavailable(HomePreviewFeature.Tour) }
                else if (capabilities.reviewRoutes) nav.openFrom(target, from, false) else unavailable(HomePreviewFeature.Review)
            }
        },
        openPlace = { target, from, place -> current { if (capabilities.reviewRoutes) nav.openPlace(target, from, place) else unavailable(HomePreviewFeature.Review) } },
        openRepo = { owner, repo -> current { nav.openRepo(owner, repo) } },
        openRepoTours = { owner, repo -> current { nav.openRepoTours(owner, repo) } }, home = nav.home,
        settings = { current { if (capabilities.settingsRoute) nav.settings() else unavailable(HomePreviewFeature.Settings) } },
        uiBook = { current { if (capabilities.uiBookRoute) nav.uiBook() else unavailable(HomePreviewFeature.UiBook) } },
        back = nav.back,
    )
}

val LocalHomeScreenServices = staticCompositionLocalOf<HomeScreenServices> {
    error("Install the native Home screen services before presenting production Home content.")
}
