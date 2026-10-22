package com.octaviantocan.pulls.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RpcSource
import com.octaviantocan.pulls.ui.home.HomeScreenServices
import com.octaviantocan.pulls.ui.home.HomeObservations
import com.octaviantocan.pulls.ui.home.HomeUi
import com.octaviantocan.pulls.ui.review.ReviewSessionCache
import com.octaviantocan.pulls.ui.review.ReviewFrameServices
import com.octaviantocan.pulls.ui.settings.SettingsServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/** One controller owns private routes, app caches and the lifetime of its active native source. */
class PullyRootState private constructor(
    private val scope: CoroutineScope,
    private val inboxFilter: () -> String,
    private val entry: Entry?,
) {
    private class Entry(val navigation: AppNavigation, val current: () -> Boolean)
    constructor(scope: CoroutineScope, inboxFilter: () -> String) : this(scope, inboxFilter, null)
    constructor(scope: CoroutineScope, inboxFilter: () -> String, navigation: AppNavigation, current: () -> Boolean) :
        this(scope, inboxFilter, Entry(navigation, current))
    private class HomeBinding(val epoch: Long, val source: RootSource, val services: HomeScreenServices) {
        var review: ReviewFrameServices? = null
        var settings: SettingsServices? = null
        val commands = CommandPaletteOwner { source.current }
    }
    private var binding: HomeBinding? = null
    private var closed = false
    private var observationsReady = true
    internal val current: Boolean get() = !closed && entry?.current?.invoke() != false

    val reviewSessions = ReviewSessionCache(scope)
    var generation by mutableLongStateOf(0L)
        private set
    var navigation by mutableStateOf(entry?.navigation ?: AppNavigation())
        private set
    var homeUi by mutableStateOf(HomeUi(inboxFilter()))
        private set

    /** Ordinary Home/repository/review navigation shares the same application services. */
    fun home(repo: Repo, epoch: Long, create: (RootSource) -> HomeScreenServices): HomeScreenServices {
        check(!closed && entry?.current?.invoke() != false) { "Pully's root has closed." }
        val identity = try {
            repo.source()
        } catch (failure: Exception) {
            invalidate()
            throw failure
        }
        binding?.takeIf { it.epoch == epoch && it.source.identity == identity && it.source.current }
            ?.let { return it.services }
        if (binding != null) invalidate()
        val openingGeneration = generation
        val sourceScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val source = RootSource(identity, sourceScope, HomeObservations(observationsReady)) {
            !closed && entry?.current?.invoke() != false && generation == openingGeneration && repo.isCurrent(identity)
        }
        var created: HomeScreenServices? = null
        try {
            return create(source).also { services ->
                created = services
                check(source.current) { "The account source changed while Home was opening." }
                require(services.repo === repo) { "Home must use the active account's shared client." }
                require(services.observations === source.observations) { "Home must use its source's read authority." }
                require(!services.capabilities.reviewRoutes || services.reviewSessions === reviewSessions) {
                    "Review routes must use the root's shared session cache."
                }
                binding = HomeBinding(epoch, source, services)
            }
        } catch (failure: Throwable) {
            source.observations.close()
            sourceScope.cancel()
            created?.ask?.invalidate()
            created?.home?.invalidate()
            created?.data?.invalidate()
            throw failure
        }
    }

    /** The frame is bound to Home's exact source and application cache, never a second review owner. */
    fun review(home: HomeScreenServices, create: (RootSource, HomeScreenServices) -> ReviewFrameServices): ReviewFrameServices {
        val owner = binding?.takeIf { !closed && it.services === home && it.source.current }
            ?: error("The review's Home source has retired.")
        owner.review?.let { return it }
        val frame = create(owner.source, home)
        check(binding === owner && owner.source.current) { "The source changed while the review services opened." }
        require(frame.cache === reviewSessions) { "Review must share the application's session cache." }
        require(frame.overview.sizes === home.data.sizes) { "Review must share Home's source-owned size hints." }
        owner.review = frame
        return frame
    }

    /** Sheets share one source-owned model/catalog, account, Stats and Feedback owner. */
    fun settings(home: HomeScreenServices, create: (RootSource, HomeScreenServices) -> SettingsServices): SettingsServices {
        val owner = binding?.takeIf { !closed && it.services === home && it.source.current }
            ?: error("The Settings source has retired.")
        owner.settings?.let { return it }
        val settings = create(owner.source, home)
        try {
            check(binding === owner && owner.source.current) { "The source changed while Settings opened." }
            require(settings.settings === home.settings) { "Settings must use the application's shared preferences." }
            check(settings.current()) { "Settings must belong to the active source." }
            owner.settings = settings
            return settings
        } catch (failure: Throwable) {
            settings.close()
            throw failure
        }
    }

    fun commands(home: HomeScreenServices): CommandPaletteOwner = binding
        ?.takeIf { !closed && it.services === home && it.source.current }?.commands
        ?: error("The Commands source has retired.")

    /** Called by native storage BEFORE it publishes a replacement usable credential snapshot. */
    fun invalidate() {
        generation++ // Revoke retained callbacks before clearing any observable child state.
        pauseObservations()
        val previous = binding
        binding = null
        previous?.source?.observations?.close()
        previous?.services?.ask?.invalidate()
        previous?.commands?.close()
        previous?.settings?.close()
        previous?.source?.scope?.cancel()
        previous?.services?.home?.invalidate()
        previous?.services?.data?.invalidate()
        reviewSessions.clear()
        // Replacing the same Compose stack owner also retires its private saveable route keys.
        if (entry == null) navigation = AppNavigation() else entry.navigation.reset(Screen.Home)
        homeUi = HomeUi(inboxFilter())
    }

    /** Cancel local reads synchronously; accepted writes retain their separate source lifetime. */
    fun pauseObservations() {
        observationsReady = false
        reviewSessions.pauseObservations()
        binding?.source?.observations?.pause()
    }

    /** Called only after the active scene has verified its latest account source. */
    fun resumeObservations() {
        if (closed) return
        observationsReady = true
        reviewSessions.resumeObservations()
        binding?.source?.observations?.resume()
    }

    fun close() {
        if (closed) return
        closed = true
        invalidate()
    }
}

/** Native stores and effects retain this revocable source lifetime, never raw credentials. */
class RootSource internal constructor(
    val identity: RpcSource,
    val scope: CoroutineScope,
    val observations: HomeObservations = HomeObservations(),
    private val valid: () -> Boolean,
) {
    val current: Boolean get() = scope.isActive && valid()
}
