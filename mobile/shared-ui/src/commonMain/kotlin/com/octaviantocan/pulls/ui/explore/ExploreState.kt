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

    fun start() { if (!started && owner.valid) { started = true; search() } }
    fun search() {
        if (!owner.valid) return
        started = true
        submittedQuery = query.trim()
        load(1)
    }
    fun editQuery(value: String) { if (owner.valid) query = value.take(80) }
    fun loadMore() { if (!loading) nextPage?.let(::load) }
    fun retry() { if (!loading) load(attemptedPage) }
    fun runIfCurrent(action: () -> Unit) { if (owner.valid) action() }
    fun open(repository: ExploreRepository, action: (ExploreRepository) -> Unit) {
        if (owner.valid && repositories.any { it.id == repository.id && it.fullName == repository.fullName }) action(repository)
    }

    private fun load(page: Int) {
        val query = submittedQuery
        owner.load(
            before = { attemptedPage = page; loading = true; problem = null; if (page == 1) { repositories = emptyList(); nextPage = null; incomplete = false; hasLoaded = false } },
            fetch = { owner.client.repositories(query, page, owner.source) },
            accept = { answer -> repositories = (if (page == 1) answer.repositories else repositories + answer.repositories).distinctBy { it.id }; nextPage = answer.nextPage; incomplete = answer.incomplete; hasLoaded = true },
            fail = { problem = it }, finish = { loading = false },
        )
    }

    fun close() {
        owner.close(); query = ""; submittedQuery = ""; repositories = emptyList(); nextPage = null; loading = false; problem = null; incomplete = false; hasLoaded = false
    }
}

@Stable
class ExplorePullsState(val repository: ExploreRepository, client: PublicBrowseClient, scope: CoroutineScope, current: () -> Boolean = { true }) {
    private val owner = BrowseOwner(client, scope, current)
    var pulls by mutableStateOf<List<ExplorePull>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var problem by mutableStateOf<PublicBrowseProblem?>(null)
        private set
    var nextPage by mutableStateOf<Int?>(null)
        private set
    var hasLoaded by mutableStateOf(false)
        private set
    private var started = false
    private var attemptedPage = 1

    fun start() { if (!started && owner.valid) { started = true; load(1) } }
    fun refresh() = load(1)
    fun retry() { if (!loading) load(attemptedPage) }
    fun loadMore() { if (!loading) nextPage?.let(::load) }
    fun open(pull: ExplorePull, action: (String) -> Unit) {
        if (owner.valid && pulls.any { it == pull }) action(pull.url)
    }
    fun runIfCurrent(action: () -> Unit) { if (owner.valid) action() }

    private fun load(page: Int) {
        owner.load(
            before = { attemptedPage = page; loading = true; problem = null; if (page == 1) { pulls = emptyList(); nextPage = null; hasLoaded = false } },
            fetch = { owner.client.pulls(repository.owner, repository.name, page, owner.source, repository.id) },
            accept = { answer -> pulls = (if (page == 1) answer.pulls else pulls + answer.pulls).distinctBy { it.number }; nextPage = answer.nextPage; hasLoaded = true },
            fail = { failure ->
                problem = failure
                if (failure == PublicBrowseProblem.RepositoryUnavailable || failure == PublicBrowseProblem.InvalidResponse) { pulls = emptyList(); nextPage = null }
            }, finish = { loading = false },
        )
    }

    fun close() { owner.close(); pulls = emptyList(); nextPage = null; loading = false; problem = null; hasLoaded = false }
}
