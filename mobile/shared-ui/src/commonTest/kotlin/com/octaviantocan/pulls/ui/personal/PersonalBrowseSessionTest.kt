package com.octaviantocan.pulls.ui.personal

import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.PersonalAuthOrigin
import com.octaviantocan.pulls.data.PersonalAuthProblem
import com.octaviantocan.pulls.data.PersonalAuthority
import com.octaviantocan.pulls.data.PersonalPullRelation
import com.octaviantocan.pulls.data.PersonalReadProblem
import com.octaviantocan.pulls.data.PersonalSessionBlobStorage
import com.octaviantocan.pulls.data.PersonalSessionVault
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalBrowseSessionTest {
    @Test
    fun homeSearchAndFoldsSurviveRendererDisposalAndTheRepositoryRoundTrip() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        try {
            browse.foreground(true); browse.openHome()
            val home = h.navigation.stack.single() as Screen.PersonalPulls
            assertEquals("", browse.homeQuery(home))
            assertTrue(browse.homeFolded(home).isEmpty())
            browse.retain(home)
            browse.setHomeQuery(home, "acme/editor")
            browse.toggleHomeFold(home, "repo:acme/editor")
            browse.selectRelation(home, PersonalPullRelation.Authored)

            browse.openRepositories()
            browse.release(home)
            val repositories = h.navigation.stack.last() as Screen.PersonalRepositories
            browse.repositories(repositories).start(); runCurrent()
            assertTrue(browse.repositories(repositories).hasLoaded)
            browse.setHomeQuery(home, "covered callback")
            browse.toggleHomeFold(home, "repo:acme/editor")

            h.navigation.nav.back(); browse.keepRoutes()
            assertSame(home, h.navigation.stack.single())
            assertEquals(PersonalPullRelation.Authored, browse.relation(home))
            assertEquals("acme/editor", browse.homeQuery(home))
            assertEquals(setOf("repo:acme/editor"), browse.homeFolded(home))

            browse.foreground(false)
            browse.setHomeQuery(home, "background callback")
            browse.toggleHomeFold(home, "repo:acme/editor")
            browse.foreground(true)
            assertEquals("acme/editor", browse.homeQuery(home))
            assertEquals(setOf("repo:acme/editor"), browse.homeFolded(home))
            browse.selectHomeTab(home, 1)
            browse.setHomeQuery(home, "hidden Inbox callback")
            browse.toggleHomeFold(home, "repo:acme/editor")
            browse.selectHomeTab(home, 0)
            assertEquals("acme/editor", browse.homeQuery(home))
            assertEquals(setOf("repo:acme/editor"), browse.homeFolded(home))
            browse.setHomeQuery(home, "Review 7")
            browse.toggleHomeFold(home, "repo:acme/editor")
            assertEquals("Review 7", browse.homeQuery(home))
            assertTrue(browse.homeFolded(home).isEmpty())
        } finally { browse.close(); h.runtime.close() }
    }

    @Test
    fun retiringHomeClearsSearchAndFoldsBeforeItsRendererLeaseEnds() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        try {
            browse.foreground(true); browse.openHome()
            val home = h.navigation.stack.single() as Screen.PersonalPulls
            browse.setHomeQuery(home, "private query")
            browse.toggleHomeFold(home, "repo:acme/editor")
            browse.retain(home)
            browse.openExplore()
            assertEquals("", browse.homeQuery(home))
            assertTrue(browse.homeFolded(home).isEmpty())

            h.navigation.stack.add(home)
            browse.setHomeQuery(home, "retired callback")
            browse.toggleHomeFold(home, "repo:acme/editor")
            assertFalse(browse.admits(home))
            assertEquals("", browse.homeQuery(home))
            assertTrue(browse.homeFolded(home).isEmpty())
            browse.release(home)

            browse.openHome()
            val fresh = h.navigation.stack.single() as Screen.PersonalPulls
            val copied = fresh.copy()
            browse.setHomeQuery(fresh, "fresh query")
            browse.setHomeQuery(copied, "copied route callback")
            browse.toggleHomeFold(copied, "repo:acme/editor")
            assertEquals("fresh query", browse.homeQuery(fresh))
            assertEquals("", browse.homeQuery(copied))
            assertTrue(browse.homeFolded(fresh).isEmpty())
            assertTrue(browse.homeFolded(copied).isEmpty())
        } finally { browse.close(); h.runtime.close() }
    }

    @Test
    fun signOutAndSourceRetirementClearHomeInputsAndRejectRetainedMutations() = runTest {
        val retirements: List<(Harness) -> Unit> = listOf(
            { it.runtime.signOut() },
            { it.entry.renewExplore() },
        )
        for (retire in retirements) {
            val h = Harness(this)
            h.verify(private = true)
            val browse = h.browse()
            try {
                browse.foreground(true); browse.openHome()
                val home = h.navigation.stack.single() as Screen.PersonalPulls
                browse.setHomeQuery(home, "private query")
                browse.toggleHomeFold(home, "repo:acme/editor")
                browse.retain(home)

                retire(h)
                assertFalse(browse.current)
                assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
                assertEquals("", browse.homeQuery(home))
                assertTrue(browse.homeFolded(home).isEmpty())
                browse.foreground(true)
                browse.setHomeQuery(home, "retired callback")
                browse.toggleHomeFold(home, "repo:acme/editor")
                assertEquals("", browse.homeQuery(home))
                assertTrue(browse.homeFolded(home).isEmpty())
                browse.release(home)
            } finally { browse.close(); h.runtime.close() }
        }
    }

    @Test
    fun homeOwnsItsRootAndPausesInboxReadsWhileToursIsVisible() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        try {
            browse.foreground(true); browse.openHome()
            val home = h.navigation.stack.single() as Screen.PersonalPulls
            assertTrue(home.home)
            assertEquals(PersonalPullRelation.Involved, browse.relation(home))
            val rows = browse.pulls(home)
            rows.start(); runCurrent()
            assertTrue(rows.rows.single().repository.private)
            browse.selectHomeTab(home, 1)
            val before = h.reads().count { !it.url.endsWith("/capabilities") }
            rows.retry(); rows.loadMore(); rows.start()
            browse.foreground(false); browse.foreground(true); runCurrent()
            assertEquals(before, h.reads().count { !it.url.endsWith("/capabilities") })
            browse.openRepositories()
            h.navigation.nav.back(); browse.keepRoutes()
            assertSame(home, h.navigation.stack.single())
            assertEquals(1, browse.homeTab(home))
            browse.selectHomeTab(home, 0); runCurrent()
            assertTrue(h.reads().count { !it.url.endsWith("/capabilities") } > before)
            assertFalse(browse.admits(home.copy()))
            val old = rows.rows.single()
            h.runtime.signOut()
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
            assertTrue(rows.rows.isEmpty())
            rows.open(old) { error("Signed-out Home retained a selection") }
            h.navigation.nav.home()
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
        } finally { browse.close(); h.runtime.close() }
    }

    @Test
    fun aGuestCannotMintAnAccountPageOrStartPersonalRequests() = runTest {
        val h = Harness(this)
        val browse = h.browse()
        browse.foreground(true); browse.openPulls(); browse.openRepositories()
        assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
        assertFalse(browse.current)
        assertTrue(h.requests.isEmpty())
        browse.close(); h.runtime.close()
    }

    @Test
    fun publicAccountRepositoriesAndSelectedPullsUseTheSameStackWithNoPrivateAuthority() = runTest {
        val h = Harness(this)
        h.verify()
        val browse = h.browse()
        browse.foreground(true); browse.openRepositories()
        val route = h.navigation.stack.last() as Screen.PersonalRepositories
        val repositories = browse.repositories(route)
        repositories.start(); runCurrent()
        val selected = repositories.rows.single()
        assertFalse(selected.private)
        browse.openRepository(selected.copy(metadata = selected.metadata.copy(id = 99)))
        assertSame(route, h.navigation.stack.last(), "Only an actual loaded selection can open a repository")
        browse.openRepository(selected)
        val repositoryRoute = h.navigation.stack.last() as Screen.PersonalRepo
        val pulls = browse.pulls(repositoryRoute)
        pulls.start(); runCurrent()
        assertEquals(7L, pulls.rows.single().number)
        assertEquals(3, h.navigation.stack.size)
        assertTrue(h.reads().all { "/github/account/" !in it.url || it.url.endsWith("/capabilities") })
        h.navigation.nav.back(); browse.keepRoutes()
        assertSame(route, h.navigation.stack.last())
        assertTrue(pulls.rows.isEmpty())
        assertSame(repositories, browse.repositories(route))
        browse.close(); h.runtime.close()
    }

    @Test
    fun confirmedPrivateConsentMountsAccountListsAndRelationFiltersWithoutAnotherStack() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openPulls()
        val route = h.navigation.stack.last() as Screen.PersonalPulls
        val waiting = browse.pulls(route)
        waiting.start(); runCurrent()
        assertTrue(waiting.rows.single().repository.private)
        browse.selectRelation(route, PersonalPullRelation.Authored)
        val authored = browse.pulls(route)
        authored.start(); runCurrent()
        assertEquals(2, h.navigation.stack.size)
        assertSame(route, h.navigation.stack.last())
        assertEquals(PersonalPullRelation.Authored, browse.relation(route))
        assertTrue(h.reads().any { it.url.endsWith("/github/account/pulls?relation=authored&page=1") })
        browse.selectRelation(route, PersonalPullRelation.ReviewRequested)
        assertSame(waiting, browse.pulls(route))
        assertEquals(7L, waiting.rows.single().number)
        browse.openRepositoryFromPull(waiting.rows.single())
        assertTrue((h.navigation.stack.last() as Screen.PersonalRepo).repository.private)
        assertTrue(h.reads().all { it.url.startsWith("https://accounts.example/api/personal/v1/github/") })
        assertTrue(h.reads().all { it.headers.keys == setOf("Accept", "Cache-Control", "Authorization") })
        browse.close(); h.runtime.close()
    }

    @Test
    fun equalLookingInjectedOrPoppedRoutesNeverReviveASelectionOrCallback() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openRepositories()
        val repositoriesRoute = h.navigation.stack.last() as Screen.PersonalRepositories
        val repositories = browse.repositories(repositoriesRoute)
        repositories.start(); runCurrent()
        browse.openRepository(repositories.rows.single())
        val route = h.navigation.stack.last() as Screen.PersonalRepo
        val state = browse.pulls(route)
        state.start(); runCurrent()
        val pull = state.rows.single()
        val injected = route.copy()
        h.navigation.stack.add(injected)
        val previousReads = h.reads().size
        assertFalse(browse.admits(injected))
        browse.pulls(injected).start(); runCurrent()
        assertEquals(previousReads, h.reads().size)
        browse.runFrom(injected) { error("Route text minted a personal action") }
        h.navigation.nav.back(); h.navigation.nav.back(); browse.keepRoutes()
        state.open(pull) { error("A popped private PR row opened") }
        h.navigation.stack.add(route.copy())
        browse.runFrom(route) { error("An equal route revived a popped callback") }
        browse.close(); h.runtime.close()
    }

    @Test
    fun signOutSynchronouslyClearsPrivateRowsAndRoutesBeforeAnyUiDisposalOrLateRead() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openPulls()
        val route = h.navigation.stack.last() as Screen.PersonalPulls
        val state = browse.pulls(route)
        state.start(); runCurrent()
        val oldPull = state.rows.single()
        val response = CompletableDeferred<RpcResponse>()
        h.onRead = { withContext(NonCancellable) { response.await() } }
        state.foreground(); runCurrent()
        h.runtime.signOut()
        assertTrue(state.rows.isEmpty())
        assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
        assertFalse(browse.current)
        assertNull(browse.capabilities)
        state.open(oldPull) { error("A signed-out callback opened") }
        response.complete(h.json(h.pullPage(true))); runCurrent()
        assertTrue(state.rows.isEmpty())
        assertNull(h.runtime.view.value.account)
        browse.close(); h.runtime.close()
    }

    @Test
    fun nativePauseBlocksNewReadDispatchBeforeUiEffectsAndRejectsAnOldHeldPage() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        h.paginated = true
        val browse = h.browse()
        browse.foreground(true); browse.openPulls()
        val route = h.navigation.stack.last() as Screen.PersonalPulls
        val state = browse.pulls(route)
        state.start(); runCurrent()
        assertEquals(20, state.rows.size)
        val response = CompletableDeferred<RpcResponse>()
        h.onRead = { withContext(NonCancellable) { response.await() } }
        state.loadMore(); runCurrent()
        browse.foreground(false)
        val before = h.reads().size
        state.loadMore(); state.retry(); state.start(); browse.openRepositories()
        assertEquals(before, h.reads().size)
        assertSame(route, h.navigation.stack.last())
        assertEquals(20, state.rows.size)
        response.complete(h.json(h.pullPage(true, page = 2))); runCurrent()
        assertEquals(20, state.rows.size, "Paused pagination cannot publish after the native event")
        h.onRead = null
        browse.foreground(true); runCurrent()
        assertEquals(20, state.rows.size)
        browse.close(); h.runtime.close()
    }

    @Test
    fun foregroundRefreshAtomicallyReplaysLoadedPaginationWhileKeepingKnownRows() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        h.paginated = true
        val browse = h.browse()
        browse.foreground(true); browse.openPulls()
        val route = h.navigation.stack.last() as Screen.PersonalPulls
        val state = browse.pulls(route)
        state.start(); runCurrent(); state.loadMore(); runCurrent()
        assertEquals((1L..21L).toList(), state.rows.map { it.number })
        val second = CompletableDeferred<RpcResponse>()
        h.version = 100
        h.onRead = { request -> if (request.url.endsWith("page=2")) second.await() else h.defaultRead(request) }
        browse.foreground(false); browse.foreground(true); runCurrent()
        assertTrue(state.refreshing)
        assertEquals((1L..21L).toList(), state.rows.map { it.number })
        second.complete(h.json(h.pullPage(true, page = 2))); runCurrent()
        assertEquals((101L..121L).toList(), state.rows.map { it.number })
        assertFalse(state.loading)
        browse.close(); h.runtime.close()
    }

    @Test
    fun typedPermissionLossPurgesRowsWhileProxyHtmlDoesNotExpireTheVerifiedAccount() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openPulls()
        val route = h.navigation.stack.last() as Screen.PersonalPulls
        val state = browse.pulls(route)
        state.start(); runCurrent()
        h.onRead = { RpcResponse(401, "<html>proxy", "text/html") }
        state.foreground(); runCurrent()
        assertEquals(PersonalReadProblem.WebPage, state.problem)
        assertEquals(1, state.rows.size)
        assertEquals(PersonalAuthority.PrivateRepositories, h.runtime.view.value.account!!.authority)
        h.onRead = { RpcResponse(403, """{"error":{"code":"forbidden","message":"fixed","retryAfterSeconds":null}}""", "application/json") }
        state.foreground(); runCurrent()
        assertEquals(PersonalReadProblem.Forbidden, state.problem)
        assertTrue(state.rows.isEmpty())
        browse.close(); h.runtime.close()
    }

    @Test
    fun aVerifiedPermissionDowngradeClearsAllPrivateRouteInputsBeforeThePublicProfilePublishes() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openRepositories()
        val route = h.navigation.stack.last() as Screen.PersonalRepositories
        val state = browse.repositories(route)
        state.start(); runCurrent()
        val old = state.rows.single()
        h.reduced = true
        h.runtime.restore(); runCurrent()
        assertFalse(browse.current)
        assertTrue(state.rows.isEmpty())
        assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
        assertEquals(PersonalAuthority.PublicRead, h.runtime.view.value.account!!.authority)
        browse.openRepository(old)
        assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
        val replacement = h.browse()
        replacement.foreground(true); replacement.openRepositories()
        val fresh = h.navigation.stack.last() as Screen.PersonalRepositories
        replacement.repositories(fresh).start(); runCurrent()
        assertFalse(replacement.repositories(fresh).rows.single().private)
        browse.close()
        assertSame(fresh, h.navigation.stack.last())
        replacement.close(); h.runtime.close()
    }

    @Test
    fun sheetNavigationDismissesItsOwnerFirstAndCannotRunAgainAfterRetirement() = runTest {
        val h = Harness(this)
        h.verify()
        val browse = h.browse()
        browse.foreground(true); runCurrent()
        var dismissed = false
        val owner = PersonalAccountSheetOwner(null) { dismissed = true }
        owner.dismissThen { assertTrue(dismissed); browse.openRepositories() }
        assertTrue(h.navigation.stack.last() is Screen.PersonalRepositories)
        val size = h.navigation.stack.size
        owner.dismissThen { error("An old sheet navigated twice") }
        assertEquals(size, h.navigation.stack.size)
        val retiring = PersonalAccountSheetOwner(null) { h.entry.renewExplore() }
        retiring.dismissThen { browse.openPulls() }
        assertFalse(browse.current)
        assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
        h.runtime.close()
    }

    @Test
    fun restoreRejectionRevokesPrivatePagesEvenWhenSecureRetirementCannotWrite() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openPulls()
        val route = h.navigation.stack.last() as Screen.PersonalPulls
        val state = browse.pulls(route)
        state.start(); runCurrent()
        val pull = state.rows.single()
        val public = h.runtime.personalReads()!!
        val private = h.runtime.accountReads()!!
        var readsDuringWrite = true
        var rowsDuringWrite = true
        var profileDuringWrite = true
        h.onWrite = {
            readsDuringWrite = public.current || private.current || h.runtime.personalReads() != null || h.runtime.accountReads() != null
            rowsDuringWrite = state.rows.isNotEmpty()
            profileDuringWrite = h.runtime.view.value.account != null
        }
        h.rejectWrites = true
        h.onSession = { RpcResponse(401, """{"error":{"kind":"reconnect","message":"fixed"}}""", "application/json") }
        h.runtime.restore(); runCurrent()
        assertNull(h.runtime.view.value.account, "A failed durable cleanup cannot keep a rejected profile verified")
        assertTrue(h.runtime.view.value.saved, "Failed storage must retain cleanup/reverification credentials")
        assertFalse(public.current); assertFalse(private.current)
        assertNull(h.runtime.personalReads()); assertNull(h.runtime.accountReads())
        assertFalse(readsDuringWrite); assertFalse(rowsDuringWrite); assertFalse(profileDuringWrite)
        assertTrue(state.rows.isEmpty())
        assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
        state.open(pull) { error("A rejected private row remained usable") }
        browse.close(); h.runtime.close()
    }

    @Test
    fun rejectionOpensTheBoundAccountFlowAfterPurgingWithoutRetryingTheOldBearer() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openPulls()
        val route = h.navigation.stack.last() as Screen.PersonalPulls
        val state = browse.pulls(route)
        state.start(); runCurrent()
        val oldReader = h.runtime.accountReads()!!
        var signIn: PersonalAccountRuntime.SignIn? = null
        h.onRecovery = { problem ->
            assertEquals(PersonalAuthProblem.Reconnect, problem)
            assertTrue(state.rows.isEmpty())
            assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
            assertNull(h.runtime.view.value.account)
            assertFalse(oldReader.current)
            signIn = h.runtime.openSignIn(backgroundScope) { }
        }
        h.onRead = { RpcResponse(401, """{"error":{"code":"reconnect","message":"fixed","retryAfterSeconds":null}}""", "application/json") }
        state.foreground(); runCurrent()
        assertEquals(listOf(PersonalAuthProblem.Reconnect), h.recoveries)
        assertTrue(signIn != null, "The bound account action can open the real shared sign-in controller")
        val reads = h.reads().size
        state.retry(); runCurrent()
        assertEquals(reads, h.reads().size, "The rejected page cannot retry its old bearer")
        h.onRead = null
        signIn!!.controller.start(); runCurrent()
        assertTrue(h.runtime.personalReads()?.current == true, "Recovery must securely promote and verify a fresh read capability")
        assertNull(h.runtime.view.value.problem)
        assertEquals(PersonalAuthority.PublicRead, h.runtime.view.value.account!!.authority)
        assertNull(h.runtime.accountReads(), "A new public sign-in cannot resurrect the old private grant")
        browse.close(); h.runtime.close()
    }

    @Test
    fun genericHttpRejectionRetainsKnownPrivateRowsAndUsesRetryWithoutAccountRecovery() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openPulls()
        val route = h.navigation.stack.last() as Screen.PersonalPulls
        val state = browse.pulls(route)
        state.start(); runCurrent()
        val oldPull = state.rows.single()
        h.onRead = { RpcResponse(401, "{}", "application/json") }
        state.foreground(); runCurrent()
        assertEquals(PersonalReadProblem.HttpRejected, state.problem)
        assertSame(oldPull, state.rows.single())
        assertTrue(browse.current)
        assertTrue(h.recoveries.isEmpty())
        h.onRead = null
        state.retry(); runCurrent()
        assertNull(state.problem)
        assertEquals(7L, state.rows.single().number)
        browse.close(); h.runtime.close()
    }

    @Test
    fun aTypedSessionRejectionPurgesEveryPrivateRelationAndRepositoryPage() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openRepositories()
        val repositoriesRoute = h.navigation.stack.last() as Screen.PersonalRepositories
        val repositories = browse.repositories(repositoriesRoute)
        repositories.start(); runCurrent()
        val repository = repositories.rows.single()
        browse.openPulls()
        val route = h.navigation.stack.last() as Screen.PersonalPulls
        val waiting = browse.pulls(route)
        waiting.start(); runCurrent()
        val oldPull = waiting.rows.single()
        browse.selectRelation(route, PersonalPullRelation.Authored)
        val authored = browse.pulls(route)
        authored.start(); runCurrent()
        h.onRead = { RpcResponse(401, """{"error":{"code":"unauthorized","message":"fixed","retryAfterSeconds":null}}""", "application/json") }
        authored.foreground(); runCurrent()
        assertTrue(waiting.rows.isEmpty(), "Account-wide rejection also revokes a cached inactive relation")
        assertTrue(authored.rows.isEmpty()); assertTrue(repositories.rows.isEmpty())
        assertFalse(browse.current)
        assertNull(h.runtime.view.value.account)
        assertNull(h.runtime.personalReads()); assertNull(h.runtime.accountReads())
        assertEquals(listOf(Screen.Explore), h.navigation.stack.toList())
        waiting.open(oldPull) { error("An inactive rejected relation opened") }
        repositories.open(repository) { error("A rejected repository selection opened") }
        browse.close(); h.runtime.close()
    }

    @Test
    fun aPoppedRoutesRendererLeaseCannotReviveTheSameObjectOrItsPrivateRows() = runTest {
        val h = Harness(this)
        h.verify(private = true)
        val browse = h.browse()
        browse.foreground(true); browse.openRepositories()
        val repositoriesRoute = h.navigation.stack.last() as Screen.PersonalRepositories
        val repositories = browse.repositories(repositoriesRoute)
        repositories.start(); runCurrent()
        browse.openRepository(repositories.rows.single())
        val route = h.navigation.stack.last() as Screen.PersonalRepo
        val state = browse.pulls(route)
        state.start(); runCurrent()
        val oldPull = state.rows.single()
        browse.retain(route)
        h.navigation.nav.back(); browse.keepRoutes()
        assertTrue(state.rows.isEmpty(), "Animation retention cannot retain popped read authority")
        h.navigation.stack.add(route)
        assertFalse(browse.admits(route), "Reinserting the exact popped object cannot restore its admission")
        browse.runFrom(route) { error("A popped renderer callback revived") }
        state.open(oldPull) { error("A popped private selection revived") }
        val reads = h.reads().size
        state.start(); state.retry(); runCurrent()
        assertEquals(reads, h.reads().size)
        browse.release(route)
        browse.close(); h.runtime.close()
    }

    internal class Harness(private val scope: TestScope) {
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val entry = NativeEntryState(false)
        val requests = mutableListOf<RpcRequest>()
        private val origin = PersonalAuthOrigin.parse("https://accounts.example")
        private val publicToken = "pully_" + "A".repeat(43)
        private val privateToken = "pully_" + "B".repeat(42) + "A"
        private var upgrade = false
        var reduced = false
        var paginated = false
        var version = 0
        var rejectWrites = false
        var onWrite: (() -> Unit)? = null
        var onSession: (() -> RpcResponse)? = null
        var onStart: (suspend (RpcResponse) -> RpcResponse)? = null
        var onStatus: (suspend () -> RpcResponse)? = null
        var onClaim: (suspend (RpcResponse) -> RpcResponse)? = null
        var onRecovery: ((PersonalAuthProblem) -> Unit)? = null
        val recoveries = mutableListOf<PersonalAuthProblem>()
        var onRead: (suspend (RpcRequest) -> RpcResponse)? = null
        var onWorkspace: (suspend (RpcRequest) -> RpcResponse)? = null
        private val storage = object : PersonalSessionBlobStorage {
            private var bytes: ByteArray? = null
            override fun read(): ByteArray? = bytes?.copyOf()
            override fun write(contents: ByteArray) { onWrite?.invoke(); check(!rejectWrites); bytes = contents.copyOf() }
        }
        private val vault = PersonalSessionVault(storage)
        private val id = "A".repeat(43)
        private fun status(private: Boolean, provisional: Boolean = false) = """{"accountId":"account-42","githubId":42,"login":"reader","authority":"${if (private) "privateRepositories" else "publicRead"}","sessionExpiresAt":${if (provisional) 1800 else 3600},"accessExpiresAt":null,"refreshAvailable":false}"""
        private fun owner(private: Boolean) = """"accountId":"account-42","githubId":42,"authority":"${if (private) "privateRepositories" else "publicRead"}""""
        private fun repository(private: Boolean) = """{${if (private) "\"private\":true," else ""}"id":42,"owner":"acme","name":"editor","fullName":"acme/editor","description":null,"stars":0,"language":null,"url":"https://github.com/acme/editor"}"""
        private fun pull(number: Int) = """"number":$number,"title":"Review $number","url":"https://github.com/acme/editor/pull/$number","author":"reader","draft":false,"createdAt":"2026-10-22","updatedAt":"2026-10-22""""
        fun pullPage(private: Boolean, page: Int = 1, repositoryPage: Boolean = false, relation: String = "reviewRequested"): String {
            val numbers = if (!paginated) listOf(7 + version) else if (page == 1) (1..20).map { it + version } else listOf(21 + version)
            val rows = numbers.joinToString(",") { number -> "{${if (repositoryPage) "" else "\"repository\":${repository(private)},"}${pull(number)}}" }
            return """{${owner(private)},${if (repositoryPage) "\"repository\":${repository(private)}" else "\"relation\":\"$relation\""},"pulls":[$rows],"nextPage":${if (paginated && page == 1) 2 else "null"},"incomplete":false}"""
        }
        fun defaultRead(request: RpcRequest): RpcResponse {
            val private = "/account/" in request.url && !request.url.endsWith("/capabilities")
            return when {
                request.url.endsWith("/account/capabilities") -> {
                    val enabled = request.headers["Authorization"] == "Bearer $privateToken" && !reduced
                    json("""{${owner(enabled)},"privateRepositories":{"consentAvailable":true,"readEnabled":$enabled}}""")
                }
                "/pulls?" in request.url -> {
                    val relation = request.url.substringAfter("relation=", "reviewRequested").substringBefore('&')
                    json(pullPage(private, if (request.url.endsWith("page=2")) 2 else 1, "/repositories/" in request.url, relation))
                }
                else -> json("""{${owner(private)},"repositories":[${repository(private)}],"nextPage":null,"incomplete":false}""")
            }
        }
        val runtime = PersonalAccountRuntime(origin, vault, scope.backgroundScope, scope.backgroundScope,
            { 1200 + scope.testScheduler.currentTime / 1000 }, { true }, {}, RpcTransport { request ->
                requests += request
                when {
                    "/workspace/" in request.url -> requireNotNull(onWorkspace).invoke(request)
                    "/v1/github/" in request.url && !request.url.endsWith("/disconnect") -> onRead?.invoke(request) ?: defaultRead(request)
                    request.url.endsWith("/capabilities") -> json("""{"github":{"enabled":true}}""")
                    request.url.endsWith("/start") -> {
                        upgrade = request.url.endsWith("/repositories/start") || request.url.endsWith("/connect/start")
                        val authorize = "https://github.com/login/oauth/authorize?client_id=pully-test&redirect_uri=https%3A%2F%2Faccounts.example%2Fapi%2Fpersonal%2Fv1%2Fauth%2Fgithub%2Fcallback&response_type=code&state=$id&code_challenge=$id&code_challenge_method=S256&scope=${if (upgrade) "repo+offline_access" else "offline_access"}&prompt=select_account"
                        val response = json("""{"attemptId":"$id","authorizeUrl":"$authorize","expiresAt":1800,"pollAfterMs":2000}""")
                        onStart?.invoke(response) ?: response
                    }
                    request.url.endsWith("/status") -> onStatus?.invoke() ?: json("""{"phase":"ready"}""")
                    request.url.endsWith("/claim") -> {
                        val response = json("""{"session":"${if (upgrade) privateToken else publicToken}","status":${status(upgrade, true)}}""")
                        onClaim?.invoke(response) ?: response
                    }
                    request.url.endsWith("/session") -> onSession?.invoke() ?: json(status(request.headers["Authorization"] == "Bearer $privateToken" && !reduced))
                    request.url.endsWith("/cancel") -> json("""{"cancelled":true}""")
                    else -> json("true")
                }
            })
        fun verify(private: Boolean = false) {
            runtime.openSignIn(scope.backgroundScope) {}!!.controller.start(); scope.runCurrent()
            if (private) { runtime.openRepositoryConsent(scope.backgroundScope) {}!!.controller.start(); scope.runCurrent() }
            assertEquals(if (private) PersonalAuthority.PrivateRepositories else PersonalAuthority.PublicRead, runtime.view.value.account!!.authority)
        }
        fun browse() = PersonalBrowseSession(runtime, scope.backgroundScope, entry.guestSource()!!, navigation) {
            recoveries += it
            onRecovery?.invoke(it)
        }
        fun reads() = requests.filter { "/api/personal/v1/github/" in it.url }
        fun json(body: String) = RpcResponse(200, body, "application/json")
    }
}
