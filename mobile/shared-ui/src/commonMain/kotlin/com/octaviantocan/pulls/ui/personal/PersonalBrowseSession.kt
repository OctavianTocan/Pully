package com.octaviantocan.pulls.ui.personal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.PersonalAccountPull
import com.octaviantocan.pulls.data.PersonalAccountReadClient
import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.PersonalAuthProblem
import com.octaviantocan.pulls.data.PersonalPullRelation
import com.octaviantocan.pulls.data.PersonalReadClient
import com.octaviantocan.pulls.data.PersonalReadError
import com.octaviantocan.pulls.data.PersonalReadProblem
import com.octaviantocan.pulls.data.PersonalRepository
import com.octaviantocan.pulls.data.PersonalRepositoryCapabilities
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class PersonalRows<T>(val rows: List<T>, val nextPage: Int?, val incomplete: Boolean)

/** Retains known content during foreground refresh; a retired page has no retained inputs or rows. */
@Stable
class PersonalListState<T> internal constructor(
    private val scope: CoroutineScope,
    private val current: () -> Boolean,
    private val eligible: () -> Boolean,
    private val fetch: suspend (Int) -> PersonalRows<T>,
    private val key: (T) -> Any,
) {
    var rows by mutableStateOf<List<T>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var hasLoaded by mutableStateOf(false)
        private set
    var nextPage by mutableStateOf<Int?>(null)
        private set
    var incomplete by mutableStateOf(false)
        private set
    var problem by mutableStateOf<PersonalReadProblem?>(null)
        private set
    private var active = true
    private var revision = 0L
    private var request: Job? = null
    private var attemptedPage = 1
    private var loadedPages = 0
    private val valid: Boolean get() = active && current()

    fun start() { if (valid && eligible() && !hasLoaded && !loading) load(1) }
    fun foreground() { if (valid && eligible() && !loading) load(1, quiet = hasLoaded) }
    fun retry() { if (!loading) load(attemptedPage, quiet = hasLoaded && attemptedPage == 1) }
    fun loadMore() { if (!loading) nextPage?.let { load(it) } }
    fun runIfCurrent(action: () -> Unit) { if (valid && eligible()) action() }
    internal fun ownsRow(row: T): Boolean = valid && eligible() && rows.any { it === row }
    fun open(row: T, action: (T) -> Unit) { if (ownsRow(row)) action(row) }

    private fun load(page: Int, quiet: Boolean = false) {
        if (!valid || !eligible()) return
        val attempt = ++revision
        request?.cancel()
        attemptedPage = page
        loading = true; refreshing = quiet; problem = null
        val throughPage = if (quiet) loadedPages else page
        request = scope.launch {
            fun owns() = valid && eligible() && revision == attempt
            try {
                val answers = mutableListOf<PersonalRows<T>>()
                var next = page
                do {
                    if (!owns()) throw CancellationException("The personal page changed.")
                    answers += fetch(next)
                    currentCoroutineContext().ensureActive()
                    if (!owns()) throw CancellationException("The personal page changed.")
                    next = answers.last().nextPage ?: break
                } while (quiet && next <= throughPage)
                if (!owns()) return@launch
                val received = answers.flatMap { it.rows }
                rows = (if (page == 1) received else rows + received).distinctBy(key)
                nextPage = answers.last().nextPage
                incomplete = answers.any { it.incomplete }
                loadedPages = if (quiet) answers.size else page
                hasLoaded = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (owns()) {
                    problem = (error as? PersonalReadError)?.problem ?: PersonalReadProblem.Unavailable
                    if (problem in setOf(PersonalReadProblem.Unauthorized, PersonalReadProblem.Reconnect, PersonalReadProblem.Forbidden, PersonalReadProblem.InvalidResponse)) {
                        rows = emptyList(); nextPage = null; hasLoaded = false; loadedPages = 0; incomplete = false
                    }
                }
            } finally {
                if (valid && revision == attempt) { loading = false; refreshing = false; request = null }
            }
        }
    }

    /** Normal native background stops local read observation without retiring the account or known data. */
    internal fun pause() { revision++; request?.cancel(); request = null; loading = false; refreshing = false }
    internal fun close() {
        active = false; pause(); rows = emptyList(); nextPage = null; hasLoaded = false; problem = null; incomplete = false; loadedPages = 0
    }
}

/** Personal pages reuse the public workspace's ONE stack, never its anonymous transport or legacy Repo. */
class PersonalBrowseSession(
    account: PersonalAccountRuntime,
    parentScope: CoroutineScope,
    private val source: NativeEntryState.GuestSource,
    private val navigation: AppNavigation,
    onAccountRecovery: (PersonalAuthProblem) -> Unit,
) {
    private var active = true
    private var inForeground = false
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    val login = account.view.value.account?.login
    private val public = account.personalReads()
    private val personal = account.accountReads()
    private val access = public?.let { PersonalBrowseAccess(it, personal) }
    private val pages = mutableListOf<Page>()
    private val routes = mutableListOf<Screen>()
    private var stopSource: (() -> Unit)? = null
    private var stopAccount: (() -> Unit)? = null
    private var recoveryAfterRetirement = false
    private var capabilityRequest: Job? = null
    private var capabilityRevision = 0L
    var capabilities by mutableStateOf<PersonalRepositoryCapabilities?>(null)
        private set
    var capabilityProblem by mutableStateOf<PersonalReadProblem?>(null)
        private set
    val current: Boolean get() = active && lifetime.isActive && source.current && access?.current == true
    val privateEnabled: Boolean get() = personal != null && current

    init {
        stopSource = source.onRetired(::close)
        stopAccount = account.onReadsRetired({
            recoveryAfterRetirement = active && source.current
            close()
        }, { problem ->
            if (recoveryAfterRetirement && source.current) {
                recoveryAfterRetirement = false
                onAccountRecovery(problem)
            }
        })
    }

    private inner class Page(val screen: Screen) {
        var retired = false
            private set
        private fun owns() = !retired && admits(screen) && navigation.stack.any { it === screen }
        var homeTab by mutableStateOf(0)
        var homeQuery by mutableStateOf("")
            private set
        var homeFolded by mutableStateOf(emptySet<String>())
            private set
        private fun eligible() = owns() && inForeground && navigation.stack.lastOrNull() === screen &&
            (!(screen is Screen.PersonalPulls && screen.home) || homeTab == 0)
        var leases = 0
        var relation by mutableStateOf((screen as? Screen.PersonalPulls)?.relation ?: PersonalPullRelation.ReviewRequested)
            private set
        val repositories = if (screen is Screen.PersonalRepositories) PersonalListState(scope, ::owns, ::eligible,
            { page -> requireNotNull(access).repositories(page) }, { it.id }) else null
        private val relationPages = mutableMapOf<PersonalPullRelation, PersonalListState<PersonalAccountPull>>()
        private val repositoryPulls = if (screen is Screen.PersonalRepo) PersonalListState(scope, ::owns, ::eligible,
            { page -> requireNotNull(access).pulls(screen.repository, page) }, { it.repository.id to it.number }) else null
        val pulls: PersonalListState<PersonalAccountPull>? get() = when (screen) {
            is Screen.PersonalRepo -> repositoryPulls
            is Screen.PersonalPulls -> relationPages.getOrPut(relation) {
                val selected = relation
                PersonalListState(scope, ::owns, { eligible() && selected == relation },
                    { page -> requireNotNull(access).pulls(selected, page) }, { it.repository.id to it.number })
            }
            else -> null
        }
        fun selectRelation(next: PersonalPullRelation) {
            if (screen !is Screen.PersonalPulls || !eligible() || next == relation) return
            pulls?.pause()
            relation = next
        }
        fun updateHomeQuery(query: String) {
            if (screen is Screen.PersonalPulls && screen.home && eligible()) homeQuery = query
        }
        fun toggleHomeFold(key: String) {
            if (screen is Screen.PersonalPulls && screen.home && eligible())
                homeFolded = if (key in homeFolded) homeFolded - key else homeFolded + key
        }
        fun foreground() { repositories?.foreground(); pulls?.foreground() }
        fun pause() { repositories?.pause(); repositoryPulls?.pause(); relationPages.values.forEach { it.pause() } }
        fun close() {
            retired = true; homeQuery = ""; homeFolded = emptySet()
            repositories?.close(); repositoryPulls?.close(); relationPages.values.forEach { it.close() }; relationPages.clear()
        }
    }

    /** The native owner invokes this synchronously, including inactive/pause before deferred UI writes. */
    fun foreground(active: Boolean) {
        if (!current) return
        inForeground = active
        if (active) {
            refreshCapabilities()
            pages.firstOrNull { it.screen === navigation.stack.lastOrNull() }?.foreground()
        } else {
            capabilityRevision++; capabilityRequest?.cancel(); capabilityRequest = null
            pages.forEach { it.pause() }
        }
    }

    fun refreshCapabilities() {
        val public = public ?: return
        if (!current || !inForeground || capabilityRequest?.isActive == true) return
        val attempt = ++capabilityRevision
        capabilityRequest = scope.launch {
            try {
                val result = public.capabilities()
                if (current && inForeground && capabilityRevision == attempt) { capabilities = result; capabilityProblem = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current && inForeground && capabilityRevision == attempt)
                    capabilityProblem = (error as? PersonalReadError)?.problem ?: PersonalReadProblem.Unavailable
            } finally { if (capabilityRevision == attempt) capabilityRequest = null }
        }
    }

    fun openHome() {
        if (!current || !inForeground || !privateEnabled) return
        val existing = routes.filterIsInstance<Screen.PersonalPulls>().firstOrNull { it.home && admits(it) }
        val home = existing ?: Screen.PersonalPulls(PersonalPullRelation.Involved, home = true).also(routes::add)
        navigation.reset(home)
        keepRoutes()
    }
    fun openExplore() {
        if (!current || !inForeground) return
        navigation.reset(Screen.Explore)
        keepRoutes()
    }

    fun openPulls(relation: PersonalPullRelation = PersonalPullRelation.ReviewRequested) {
        if (!current || !inForeground) return
        val top = navigation.stack.lastOrNull()
        if (top is Screen.PersonalPulls && admits(top)) {
            page(top).selectRelation(relation)
            return
        }
        openRoute(Screen.PersonalPulls(relation))
    }
    fun openRepositories() { if (current && inForeground) openRoute(Screen.PersonalRepositories()) }
    fun openRepository(repository: PersonalRepository) {
        if (!current || !inForeground) return
        val page = pages.firstOrNull { it.screen === navigation.stack.lastOrNull() } ?: return
        page.repositories?.open(repository) { openRoute(Screen.PersonalRepo(it)) }
    }
    fun openRepositoryFromPull(pull: PersonalAccountPull) {
        if (!current || !inForeground) return
        val page = pages.firstOrNull { it.screen === navigation.stack.lastOrNull() } ?: return
        page.pulls?.open(pull) { openRoute(Screen.PersonalRepo(it.repository)) }
    }
    private fun openRoute(screen: Screen) { routes.add(screen); navigation.stack.add(screen) }
    fun admits(screen: Screen): Boolean = current && routes.any { it === screen }
    fun runFrom(screen: Screen, action: () -> Unit) {
        if (admits(screen) && inForeground && navigation.stack.lastOrNull() === screen) action()
    }
    fun requestPrivateConsent(screen: Screen, action: () -> Unit) {
        if (!privateEnabled && capabilities?.consentAvailable == true) runFrom(screen, action)
    }

    fun repositories(screen: Screen.PersonalRepositories): PersonalListState<PersonalRepository> = requireNotNull(page(screen).repositories)
    fun homeQuery(screen: Screen.PersonalPulls): String = page(screen).homeQuery
    fun setHomeQuery(screen: Screen.PersonalPulls, query: String) { page(screen).updateHomeQuery(query) }
    fun homeFolded(screen: Screen.PersonalPulls): Set<String> = page(screen).homeFolded
    fun toggleHomeFold(screen: Screen.PersonalPulls, key: String) { page(screen).toggleHomeFold(key) }
    fun homeTab(screen: Screen.PersonalPulls): Int = page(screen).homeTab
    fun selectHomeTab(screen: Screen.PersonalPulls, tab: Int) {
        if (!screen.home || tab !in 0..1 || !admits(screen) || !inForeground || navigation.stack.lastOrNull() !== screen) return
        val page = page(screen)
        if (page.homeTab == tab) return
        page.pause()
        page.homeTab = tab
        if (tab == 0) page.foreground()
    }
    fun relation(screen: Screen.PersonalPulls): PersonalPullRelation = page(screen).relation
    fun selectRelation(screen: Screen.PersonalPulls, relation: PersonalPullRelation) {
        if (admits(screen) && inForeground && navigation.stack.lastOrNull() === screen) page(screen).selectRelation(relation)
    }
    fun pulls(screen: Screen): PersonalListState<PersonalAccountPull> {
        require(screen is Screen.PersonalPulls || screen is Screen.PersonalRepo)
        return requireNotNull(page(screen).pulls)
    }
    private fun page(screen: Screen): Page {
        pages.firstOrNull { it.screen === screen }?.let { return it }
        return Page(screen).also { if (admits(screen) && navigation.stack.any { route -> route === screen }) pages.add(it) else it.close() }
    }
    fun retain(screen: Screen) { pages.firstOrNull { it.screen === screen }?.let { it.leases++ } }
    fun release(screen: Screen) {
        pages.firstOrNull { it.screen === screen }?.let { it.leases = (it.leases - 1).coerceAtLeast(0) }
        keepRoutes()
    }
    fun keepRoutes() {
        routes.removeAll { route -> navigation.stack.none { it === route } }
        pages.filter { !admits(it.screen) }.forEach { it.close() }
        pages.removeAll { it.retired && it.leases == 0 }
    }
    fun close() {
        if (!active) return
        active = false
        stopSource?.invoke(); stopSource = null
        stopAccount?.invoke(); stopAccount = null
        capabilityRevision++; capabilityRequest?.cancel(); capabilityRequest = null
        capabilities = null; capabilityProblem = null
        pages.forEach { it.close() }; pages.clear()
        navigation.stack.removeAll { screen -> routes.any { it === screen } }
        routes.clear()
        if (navigation.stack.isEmpty()) navigation.reset(Screen.Explore)
        scope.cancel()
    }
}

/** Fixed public/account adapters converge only after each typed client has verified its own envelope. */
private class PersonalBrowseAccess(private val public: PersonalReadClient, private val personal: PersonalAccountReadClient?) {
    val current: Boolean get() = public.current && (personal?.current != false)
    suspend fun repositories(page: Int): PersonalRows<PersonalRepository> = personal?.repositories(page)?.let {
        PersonalRows(it.repositories, it.nextPage, it.incomplete)
    } ?: public.repositories(page).let { PersonalRows(it.repositories.map { repository -> PersonalRepository(repository, false) }, it.nextPage, it.incomplete) }

    suspend fun pulls(relation: PersonalPullRelation, page: Int): PersonalRows<PersonalAccountPull> = personal?.pulls(relation, page)?.let {
        PersonalRows(it.pulls, it.nextPage, it.incomplete)
    } ?: public.pulls(relation, page).let { result -> PersonalRows(result.pulls.map {
        PersonalAccountPull(PersonalRepository(it.repository, false), it.number, it.title, it.author, it.draft, it.createdAt, it.updatedAt)
    }, result.nextPage, result.incomplete) }

    suspend fun pulls(repository: PersonalRepository, page: Int): PersonalRows<PersonalAccountPull> {
        if (personal != null) return personal.pulls(repository, page).let { PersonalRows(it.pulls, it.nextPage, it.incomplete) }
        if (repository.private) throw PersonalReadError(PersonalReadProblem.Forbidden)
        return public.pulls(repository.metadata, page).let { result -> PersonalRows(result.pulls.map {
            PersonalAccountPull(repository, it.number, it.title, it.author, it.draft, it.createdAt, it.updatedAt)
        }, result.nextPage, result.incomplete) }
    }
}
