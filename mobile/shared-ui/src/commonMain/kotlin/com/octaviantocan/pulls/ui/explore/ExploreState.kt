package com.octaviantocan.pulls.ui.explore

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.ExplorePull
import com.octaviantocan.pulls.data.ExploreRepository
import com.octaviantocan.pulls.data.PublicBrowseClient
import com.octaviantocan.pulls.data.PublicBrowseError
import com.octaviantocan.pulls.data.PublicBrowseProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Public screens own their requests without adopting any Inbox, review or account store. */
private class BrowseOwner(val client: PublicBrowseClient, private val scope: CoroutineScope, private val current: () -> Boolean) {
    val source = client.source()
    private var active = true
    private var revision = 0L
    private var request: Job? = null
    val valid: Boolean get() = active && scope.isActive && current() && client.isCurrent(source)

    fun <T> load(before: () -> Unit, fetch: suspend () -> T, accept: (T) -> Unit, fail: (PublicBrowseProblem) -> Unit, finish: () -> Unit) {
        if (!valid) return
        val opening = ++revision
        request?.cancel()
        before()
        request = scope.launch {
            fun owns() = valid && opening == revision
            try {
                val answer = fetch()
                if (owns()) accept(answer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PublicBrowseError) {
                if (owns()) fail(error.problem)
            } catch (_: Exception) {
                if (owns()) fail(PublicBrowseProblem.Unavailable)
            } finally { if (owns()) finish() }
        }
    }

    fun close() { active = false; revision++; request?.cancel(); request = null }
}

@Stable
class ExploreRepositoriesState(client: PublicBrowseClient, scope: CoroutineScope, current: () -> Boolean = { true }) {
    private val owner = BrowseOwner(client, scope, current)
    var query by mutableStateOf("")
        private set
    var submittedQuery by mutableStateOf("")
        private set
    var repositories by mutableStateOf<List<ExploreRepository>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var problem by mutableStateOf<PublicBrowseProblem?>(null)
        private set
    var incomplete by mutableStateOf(false)
        private set
    var nextPage by mutableStateOf<Int?>(null)
        private set
    var hasLoaded by mutableStateOf(false)
        private set
    private var started = false
    private var attemptedPage = 1
    private var loadedPages = 0

    fun start() { if (!started && owner.valid) { started = true; search() } }
    fun foreground() { if (owner.valid && !loading) { if (!started) start() else load(1, quiet = hasLoaded) } }
    fun search() {
        if (!owner.valid) return
        started = true
        submittedQuery = query.trim()
        load(1)
    }
    fun editQuery(value: String) { if (owner.valid) query = value.take(80) }
    fun loadMore() { if (!loading) nextPage?.let(::load) }
    fun retry() { if (!loading) load(attemptedPage, quiet = hasLoaded && attemptedPage == 1) }
    fun runIfCurrent(action: () -> Unit) { if (owner.valid) action() }
    fun open(repository: ExploreRepository, action: (ExploreRepository) -> Unit) {
        if (owner.valid && repositories.any { it.id == repository.id && it.fullName == repository.fullName }) action(repository)
    }

    private fun load(page: Int, quiet: Boolean = false) {
        val query = submittedQuery
        val throughPage = if (quiet) loadedPages else page
        owner.load(
            before = { attemptedPage = page; loading = true; refreshing = quiet; problem = null; if (page == 1 && !quiet) { repositories = emptyList(); nextPage = null; incomplete = false; hasLoaded = false; loadedPages = 0 } },
            fetch = {
                buildList {
                    var next = page
                    do {
                        val answer = owner.client.repositories(query, next, owner.source)
                        add(answer)
                        next = answer.nextPage ?: break
                    } while (quiet && next <= throughPage)
                }
            },
            accept = { answers ->
                val rows = answers.flatMap { it.repositories }
                repositories = (if (page == 1) rows else repositories + rows).distinctBy { it.id }
                nextPage = answers.last().nextPage; incomplete = answers.any { it.incomplete }; hasLoaded = true
                loadedPages = if (quiet) answers.size else page
            },
            fail = { problem = it }, finish = { loading = false; refreshing = false },
        )
    }

    fun close() {
        owner.close(); query = ""; submittedQuery = ""; repositories = emptyList(); nextPage = null; loading = false; refreshing = false; problem = null; incomplete = false; hasLoaded = false; loadedPages = 0
    }
}

@Stable
class ExplorePullsState(val repository: ExploreRepository, client: PublicBrowseClient, scope: CoroutineScope, current: () -> Boolean = { true }) {
    private val owner = BrowseOwner(client, scope, current)
    var pulls by mutableStateOf<List<ExplorePull>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var problem by mutableStateOf<PublicBrowseProblem?>(null)
        private set
    var nextPage by mutableStateOf<Int?>(null)
        private set
    var hasLoaded by mutableStateOf(false)
        private set
    private var started = false
    private var attemptedPage = 1
    private var loadedPages = 0

    fun start() { if (!started && owner.valid) { started = true; load(1) } }
    fun foreground() { if (owner.valid && !loading) { if (!started) start() else load(1, quiet = hasLoaded) } }
    fun retry() { if (!loading) load(attemptedPage, quiet = hasLoaded && attemptedPage == 1) }
    fun loadMore() { if (!loading) nextPage?.let(::load) }
    fun open(pull: ExplorePull, action: (ExplorePull) -> Unit) {
        if (owner.valid && pulls.any { it == pull }) action(pull)
    }
    fun runIfCurrent(action: () -> Unit) { if (owner.valid) action() }

    private fun load(page: Int, quiet: Boolean = false) {
        val throughPage = if (quiet) loadedPages else page
        owner.load(
            before = { attemptedPage = page; loading = true; refreshing = quiet; problem = null; if (page == 1 && !quiet) { pulls = emptyList(); nextPage = null; hasLoaded = false; loadedPages = 0 } },
            fetch = {
                buildList {
                    var next = page
                    do {
                        val answer = owner.client.pulls(repository.owner, repository.name, next, owner.source, repository.id)
                        add(answer)
                        next = answer.nextPage ?: break
                    } while (quiet && next <= throughPage)
                }
            },
            accept = { answers ->
                val rows = answers.flatMap { it.pulls }
                pulls = (if (page == 1) rows else pulls + rows).distinctBy { it.number }
                nextPage = answers.last().nextPage; hasLoaded = true; loadedPages = if (quiet) answers.size else page
            },
            fail = { failure ->
                problem = failure
                if (failure == PublicBrowseProblem.RepositoryUnavailable || failure == PublicBrowseProblem.InvalidResponse) { pulls = emptyList(); nextPage = null; hasLoaded = false; loadedPages = 0 }
            }, finish = { loading = false; refreshing = false },
        )
    }

    fun close() { owner.close(); pulls = emptyList(); nextPage = null; loading = false; refreshing = false; problem = null; hasLoaded = false; loadedPages = 0 }
}
