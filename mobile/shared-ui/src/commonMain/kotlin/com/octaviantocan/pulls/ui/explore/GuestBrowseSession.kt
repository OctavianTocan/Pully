package com.octaviantocan.pulls.ui.explore

import com.octaviantocan.pulls.data.ExploreRepository
import com.octaviantocan.pulls.data.PublicBrowseClient
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class GuestBrowseSession(
    transport: RpcTransport,
    parentScope: CoroutineScope,
    private val source: NativeEntryState.GuestSource,
    private val navigation: AppNavigation,
) {
    private var active = true
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    val current: Boolean get() = active && source.current && job.isActive
    private val client = PublicBrowseClient(transport) { if (current) 0 else 1 }
    val repositories = ExploreRepositoriesState(client, scope, { current })
    private val pages = mutableListOf<Page>()
    private var stopObserving: (() -> Unit)? = null

    init { stopObserving = source.onRetired(::close) }

    private inner class Page(val screen: Screen.PublicRepo) {
        var leases = 0
        val state = ExplorePullsState(screen.repository, client, scope) { current && navigation.stack.any { it === screen } }
    }

    fun openRepository(repository: ExploreRepository) {
        if (current && navigation.stack.lastOrNull() == Screen.Explore)
            repositories.open(repository) { navigation.stack.add(Screen.PublicRepo(it)) }
    }

    fun runFrom(screen: Screen, action: () -> Unit) {
        if (current && navigation.stack.lastOrNull() === screen) action()
    }

    fun foreground() {
        if (!current) return
        when (val screen = navigation.stack.lastOrNull()) {
            Screen.Explore -> repositories.foreground()
            is Screen.PublicRepo -> pages.firstOrNull { it.screen === screen }?.state?.foreground()
            else -> Unit
        }
    }

    suspend fun openIncoming(screen: Screen, isPending: () -> Boolean, closeSheets: suspend () -> Unit, consume: () -> Unit) {
        if (!current || !isPending()) return
        closeSheets()
        currentCoroutineContext().ensureActive()
        if (!current || !isPending()) return
        if (navigation.stack.lastOrNull() != screen) navigation.stack.add(screen)
        consume()
    }

    fun pulls(screen: Screen.PublicRepo): ExplorePullsState {
        pages.firstOrNull { it.screen === screen }?.let { return it.state }
        val page = Page(screen)
        if (current) pages.add(page) else page.state.close()
        return page.state
    }

    fun retain(screen: Screen.PublicRepo) { pages.firstOrNull { it.screen === screen }?.let { it.leases++ } }
    fun release(screen: Screen.PublicRepo) {
        pages.firstOrNull { it.screen === screen }?.let { it.leases = (it.leases - 1).coerceAtLeast(0) }
        keepRoutes()
    }

    fun keepRoutes() {
        val removed = pages.filter { page -> page.leases == 0 && navigation.stack.none { it === page.screen } }
        removed.forEach { it.state.close() }
        pages.removeAll(removed.toSet())
    }

    fun close() {
        if (!active) return
        active = false
        stopObserving?.invoke()
        stopObserving = null
        repositories.close()
        pages.forEach { it.state.close() }
        pages.clear()
        scope.cancel()
    }
}
