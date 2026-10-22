package com.octaviantocan.pulls.ui.home

import android.content.Context
import androidx.activity.OnBackPressedCallback
import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.octaviantocan.pulls.PullsApplication
import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.PullSizes
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.TourStore
import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.rememberOpenOnGithub
import com.octaviantocan.pulls.ui.rememberOpenInBrowser
import com.octaviantocan.pulls.ui.review.copyText
import com.octaviantocan.pulls.ui.review.reviewSessionCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

internal fun androidHomeScreenData(context: Context, repo: Repo): HomeScreenData {
    val app = context.applicationContext as PullsApplication
    val epoch = RepoIdentity.generation
    val source = runCatching { repo.source() }.getOrNull()
    return app.homeScreenData.bind(source, epoch) {
        val current = {
            RepoIdentity.generation == epoch &&
                if (source == null) runCatching { repo.source() }.isFailure else repo.isCurrent(source)
        }
        val config = ServerStore.load(app)
        Account.load(app)
        val namespace = sourceStorageNamespace(config.url, Account.email, Account.serverHasAccount != false && Account.headers(app).isNotEmpty(), config.headers())
        val sizes = PullSizes.source(namespace ?: "invalid-source:$epoch", bounded = false)
        val effects = AndroidHomeScreenEffects(sizes, current)
        val disk = AndroidRepoPages(File(app.cacheDir, "repo-pulls-source-v2-${namespace ?: "invalid"}")) { namespace != null && current() }
        HomeScreenData(
            repo, FirstPages(disk, current),
            PostedDiffsStore(repo, deviceStore(app, "pully-posted"), { Account.serverHasAccount != false && Account.headers(app).isNotEmpty() }, current),
            GithubStatsStore(repo, deviceStore(app, "pulls-github-stats"), current),
            current, effects, sizes,
        )
    }
}

/** Private native I/O keeps repo-pulls JSON and lowercased owner~repo filenames unchanged. */
private class AndroidRepoPages(private val directory: File, private val valid: () -> Boolean) : RepoPageStorage {
    private fun file(owner: String, name: String) = File(directory, "${"$owner/$name".lowercase().replace('/', '~')}.json")
    override suspend fun read(owner: String, name: String): JsonObject? = withContext(Dispatchers.IO) {
        if (!valid()) return@withContext null
        runCatching { Json.parseToJsonElement(file(owner, name).readText()) as? JsonObject }.getOrNull()?.takeIf { valid() }
    }
    override suspend fun write(owner: String, name: String, raw: JsonObject) = withContext(Dispatchers.IO) {
        if (valid()) runCatching {
            val target = file(owner, name)
            target.parentFile?.mkdirs()
            target.writeText(raw.toString())
        }
        Unit
    }
}

internal class AndroidHomeScreenEffects(private val sizes: PullSizes.SourceLease, private val current: () -> Boolean) : HomeScreenEffects {
    override fun track(name: String, vararg props: Pair<String, Any?>) = Analytics.track(name, *props)
    override fun error(name: String, message: String?) = Analytics.error(name, message)
    override fun size(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) { if (current()) sizes.put(owner, repo, number, additions, deletions, files) }
}

/** The same Android clipboard/browser/lifecycle/back services are shared by Home and Overview wrappers. */
@Composable
fun rememberAndroidNativeHomeActions(): NativeHomeActions {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
    val github = rememberOpenOnGithub()
    val browser = rememberOpenInBrowser()
    return remember(context, lifecycle, github, browser, backDispatcher) {
        object : NativeHomeActions {
                override fun copyText(text: String) = copyText(context, text)
                override fun openOnGithub(url: String) = github(url)
                override fun openInBrowser(url: String) = browser(url)
                override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver): HomeLifecycleSubscription {
                    var tracking = false
                    val callback = object : OnBackPressedCallback(true) {
                        override fun handleOnBackStarted(backEvent: BackEventCompat) {
                            tracking = observer.enabled()
                            if (tracking) observer.began()
                        }
                        override fun handleOnBackProgressed(backEvent: BackEventCompat) { if (tracking) observer.changed(backEvent.progress) }
                        override fun handleOnBackCancelled() { if (tracking) observer.cancelled(); tracking = false }
                        override fun handleOnBackPressed() {
                            if (observer.enabled()) { if (!tracking) observer.began(); observer.committed() }
                            else { isEnabled = false; try { backDispatcher.onBackPressed() } finally { isEnabled = true } }
                            tracking = false
                        }
                    }
                    backDispatcher.addCallback(callback)
                    return object : HomeLifecycleSubscription {
                        override fun cancel() { if (tracking) observer.cancelled(); tracking = false; callback.remove() }
                    }
                }
                override fun observeLifecycle(observer: HomeLifecycleObserver): HomeLifecycleSubscription {
                    val events = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> observer.started()
                            Lifecycle.Event.ON_RESUME -> observer.resumed()
                            Lifecycle.Event.ON_STOP -> observer.stopped()
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(events)
                    return object : HomeLifecycleSubscription { override fun cancel() { lifecycle.removeObserver(events) } }
                }
            }
    }
}

/** Android retains its existing full renderers/actions and supplies only native services. */
@Composable
internal fun rememberAndroidHomeScreenServices(repo: Repo): HomeScreenServices {
    val context = LocalContext.current
    val native = rememberAndroidNativeHomeActions()
    val parentScope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val home = rememberHomeState(repo)
    val askEpoch = remember(repo, home) { RepoIdentity.generation }
    val askScope = remember(repo, home) { CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])) }
    DisposableEffect(askScope, askEpoch) {
        val unsubscribe = RepoIdentity.observeInvalidation { askScope.cancel() }
        if (RepoIdentity.generation != askEpoch) askScope.cancel()
        onDispose { unsubscribe(); askScope.cancel() }
    }
    return remember(repo, home, native) {
        val data = androidHomeScreenData(context, repo)
        val settings = AppSettings.get(context)
        val source = runCatching { repo.source() }.getOrNull()
        val epoch = RepoIdentity.generation
        val current = { RepoIdentity.generation == epoch && source != null && repo.isCurrent(source) }
        val tourStore = TourStore.get(context)
        val canAsk = cliEngine(context)
        val ask = if (canAsk) InboxAsk(repo, askScope, settings) else null
        HomeScreenServices(
            repo, settings, home, data,
            native = native,
            effects = data.effects,
            capabilities = HomeScreenCapabilities(
                reviewRoutes = true, tourRoutes = true, startTour = true, inboxAsk = canAsk,
                taskObservation = true, postedDiffs = true, deletePosted = true, profileStats = true,
                settingsRoute = true, commands = true,
            ),
            unavailable = { toaster.show("This feature is unavailable") },
            openCommands = { Commands.open() },
            reviewSessions = reviewSessionCache(context),
            tourCopies = object : HomeTourCopies {
                override suspend fun contains(pull: String): Boolean = withContext(Dispatchers.IO) { current() && tourStore.has(pull) && current() }
                override suspend fun put(pull: String, tour: JsonObject) = withContext(Dispatchers.IO) { if (current()) tourStore.put(pull, tour) }
            },
            ask = ask?.let { conversation -> object : HomeAskSurface {
                override fun sendOrQueue(question: String) { if (!data.current) return; if (conversation.accepting) conversation.ask(question) else conversation.input = question }
                @Composable override fun Content(listed: List<com.octaviantocan.pulls.core.ListedPull>, onOpen: (com.octaviantocan.pulls.core.ListedPull) -> Unit, onClose: () -> Unit) =
                    InboxAskSheet(conversation, listed, onOpen, onClose)
            } },
        )
    }
}

/** Startup warming uses the same source-owned profile cache as the shared profile sheet. */
internal object StatsMemory {
    suspend fun warm(context: Context) = androidHomeScreenData(context, Repo(context)).stats.warm()
}
