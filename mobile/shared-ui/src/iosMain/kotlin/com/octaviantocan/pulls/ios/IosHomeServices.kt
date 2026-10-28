package com.octaviantocan.pulls.ios

import com.octaviantocan.pulls.data.IosUsageAnalytics
import com.octaviantocan.pulls.data.PullSizes
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.RootSource
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.home.*
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.review.IosTourCacheOwner
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSUserDefaults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * Native persistence and effects only; all lists, RPCs and presentation policies are the existing shared code.
 * [namespace] is the signed-in account's own storage, from a server sign-in or a GitHub workspace.
 */
internal fun iosHomeServices(
    source: RootSource,
    repo: Repo,
    namespace: String,
    settings: AppSettings,
    native: NativeHomeActions,
    analytics: IosUsageAnalytics,
    owner: () -> String?,
    reviewSessions: com.octaviantocan.pulls.ui.review.ReviewSessionCache,
    tours: IosTourCacheOwner,
    fullJourney: Boolean,
    fixture: Boolean,
    aiPolicy: () -> AiCapabilities,
    defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    unavailable: (HomePreviewFeature) -> Unit,
): HomeScreenServices {
    val current = { source.current }
    val copies = tours.forSource(namespace, current)
    val sourceNative = SourceHomeActions(native, current)
    val store = UserDefaultsHomeStore(namespace, current, defaults)
    fun section(prefix: String) = object : KeyValueStore {
        override val current: Boolean get() = store.current
        override fun get(key: String): String? = store.get("$prefix.$key")
        override fun set(key: String, value: String) = store.set("$prefix.$key", value)
    }
    val sizes = PullSizes.source(namespace)
    val effects = object : HomeScreenEffects {
        override fun track(name: String, vararg props: Pair<String, Any?>) { if (current()) analytics.track(name, *props) }
        override fun error(name: String, message: String?) { if (current()) analytics.error(name, message) }
        override fun size(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) {
            if (current()) sizes.put(owner, repo, number, additions, deletions, files)
        }
    }
    val inboxEffects = object : HomeEffects {
        override fun loadError(message: String?) { if (current()) analytics.error("inbox.load", message) }
        override fun action(action: String, value: String) { if (current()) analytics.track("inbox.action", "action" to action, "value" to value) }
    }
    val homeStore = section("pulls-home")
    val home = HomeState(
        MyPullsSync(
            cached = { source.observations.read { repo.myPullsCached(it)?.asSnapshot() } },
            fresh = { source.observations.read { repo.myPulls(it) } }, storage = homeStore,
            peek = { state, classify -> source.observations.read { repo.myPullsCached(state, revalidate = false, classify = classify)?.asSnapshot() } },
            effects = inboxEffects, currentSource = current,
        ),
        ArchiveStore(section("pulls-inbox")), TourIndex(section("pulls-inbox")), InboxLayout(section("pulls-inbox"), inboxEffects),
    )
    val pages = object : RepoPageStorage {
        private fun key(owner: String, name: String) = "repo-pulls.${"$owner/$name".lowercase()}"
        override suspend fun read(owner: String, name: String): JsonObject? {
            if (!current()) return null
            return store.get(key(owner, name))?.let { raw ->
                runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            }?.takeIf { current() }
        }
        override suspend fun write(owner: String, name: String, raw: JsonObject) {
            if (!current()) throw CancellationException("The account source changed.")
            store.set(key(owner, name), raw.toString())
        }
    }
    val data = HomeScreenData(
        repo, FirstPages(pages, current), PostedDiffsStore(repo, section("pully-posted"), { owner() != null }, current),
        GithubStatsStore(repo, section("pulls-github-stats"), current), current, effects, sizes,
    )
    val canAsk = fullJourney && !fixture
    val ask = if (canAsk) InboxAsk(
        repo, source.scope, settings, sourceNative,
        object : InboxAskEffects {
            override fun nowMillis() = Clock.System.now().toEpochMilliseconds()
            override fun track(name: String, vararg props: Pair<String, Any?>) { if (current()) analytics.track(name, *props) }
            override fun feedback(cue: Cue, emphasis: Emphasis) { if (current()) ControlFeedback.play(cue, emphasis) }
        },
        current, aiPolicy, source.observations,
    ) else null
    return HomeScreenServices(
        repo, settings, home, data, sourceNative, effects,
        HomeScreenCapabilities(
            reviewRoutes = true, tourRoutes = fullJourney, startTour = fullJourney, inboxAsk = canAsk,
            taskObservation = fullJourney, postedDiffs = fullJourney && !fixture, deletePosted = fullJourney && !fixture, profileStats = true,
            settingsRoute = true, commands = true, uiBookRoute = false, serverChanges = fullJourney && !fixture,
        ),
        unavailable, openCommands = { if (current()) Commands.open() }, reviewSessions = reviewSessions,
        tourCopies = copies, ask = ask,
        observations = source.observations,
    )
}
