package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcSource
import com.octaviantocan.pulls.data.RpcObserver
import com.octaviantocan.pulls.data.RpcTimeoutException
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.data.ConnectionProblem
import com.octaviantocan.pulls.data.connectionProblem
import com.octaviantocan.pulls.data.ServerError
import com.octaviantocan.pulls.data.ServerErrorKind
import com.octaviantocan.pulls.data.SignInController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.selects.select
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class AccountPhase { Checking, SignIn, Ready, Problem }
/** [connection] says what kind of connection problem [problem] is, so the setup screen can offer its way out. */
class AccountState(
    val phase: AccountPhase, val email: String? = null, val googleClientId: String? = null, val problem: String? = null,
    val connection: ConnectionProblem? = null,
)

/**
 * What the shared root needs from the account that opened the private app: a server sign-in ([AccountRuntime]) or a
 * verified GitHub workspace ([WorkspaceRootAccount]).
 */
interface RootAccount {
    val repo: Repo
    /** Advances after old private routes/caches have been synchronously retired. */
    val sourceEpoch: Long
    val state: AccountState
    fun refresh()
    /** Named apart from [AccountStorage.retryStorage]: one Objective-C selector with two types renames the Swift host's. */
    fun retrySavedStorage()
    fun signInServices(): SignInServices
    suspend fun accessSignIn(title: String, hint: String): Boolean
    fun resetCredentials(expectedEpoch: Long)
}

/** Existing account RPCs and native session verification, owned above individual routes. */
class AccountRuntime(
    private val storage: AccountStorage,
    transport: RpcTransport,
    private val scope: CoroutineScope,
    private val effort: () -> String,
    private val invalidated: () -> Unit,
    private val synchronizeSettings: suspend () -> Unit,
    rpcObserver: RpcObserver,
) : RootAccount {
    private var closed = false
    private var probe: Job? = null
    private var probeChanged = CompletableDeferred<Unit>()
    private var changing = false
    private var epoch = 0L
    private var probeRevision = 0L
    override val repo = Repo({ configuration(false) }, transport, rpcObserver)
    private val verificationRepo = Repo({ configuration(true) }, transport, rpcObserver)

    override var sourceEpoch by mutableStateOf(0L)
        private set
    override var state by mutableStateOf(AccountState(AccountPhase.Checking))
        private set
    var serverHasAccount by mutableStateOf<Boolean?>(null)
        private set

    private val subscription = storage.observe(object : AccountStorageObserver {
        override fun invalidating() {
            if (closed) return
            changing = true
            serverHasAccount = null
            epoch++
            notifyProbeChanged()
            probe?.cancel()
            invalidated()
            state = AccountState(AccountPhase.Checking)
            sourceEpoch = epoch
        }
        override fun changed() {
            if (closed) return
            changing = false
            refresh()
        }
    })

    private fun configuration(verification: Boolean): RepoConfiguration {
        val value = storage.connection(verification)
            ?: throw IllegalStateException("Pully could not open its saved credentials. Unlock the device and try again.")
        return RepoConfiguration(value.server, value.headers, value.engine, value.model, effort(), value.generation)
    }

    fun start() = refresh()

    /** Resume retries observation; an unreachable server never forgets a verified account. */
    override fun refresh() {
        if (closed || changing) return
        val previousProbe = probe
        val revision = ++probeRevision
        val openingEpoch = epoch
        val previous = state
        // Assign before starting: a synchronous host mutation can re-enter refresh().
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val stored = storage.account()
                stored.problem?.let { throw IllegalStateException(it) }
                val source = repo.source()
                val status = repo.accountStatus()
                requireOwner(openingEpoch, source)
                val ownershipChanged = serverHasAccount != null && serverHasAccount != status.hasAccount
                serverHasAccount = status.hasAccount
                if (ownershipChanged) {
                    // Android Account.noteServer also retires private data when this boundary changes.
                    epoch++
                    invalidated()
                    sourceEpoch = epoch
                    state = AccountState(AccountPhase.Checking)
                    refresh()
                    return@launch
                }
                if (stored.verificationRequired) {
                    val generation = configuration(true).generation
                    val email = try {
                        verificationRepo.accountEmail()
                    } catch (failure: ServerError) {
                        if (failure.kind != ServerErrorKind.Unauthorized) throw failure
                        null
                    }
                    requireOwner(openingEpoch, source)
                    checked(storage.confirmRestoredSession(email, generation))
                    return@launch // Its source change observer restarts us with the verified configuration.
                }
                if (status.hasAccount && stored.email != null) {
                    val email = try {
                        repo.accountEmail()
                    } catch (failure: ServerError) {
                        if (failure.kind != ServerErrorKind.Unauthorized) throw failure
                        null
                    }
                    requireOwner(openingEpoch, source)
                    if (email == null || !email.equals(stored.email, ignoreCase = true)) {
                        checked(storage.forgetSession())
                        return@launch
                    }
                    synchronizeSettings()
                    requireOwner(openingEpoch, source)
                }
                state = AccountState(
                    if (status.hasAccount && stored.email == null) AccountPhase.SignIn else AccountPhase.Ready,
                    stored.email,
                    status.googleClientId?.takeIf(storage::googleAvailable),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (currentCoroutineContext().isActive && !closed && openingEpoch == epoch && revision == probeRevision) {
                    state = AccountState(
                        if (previous.phase == AccountPhase.Ready) AccountPhase.Ready else AccountPhase.Problem,
                        storage.account().email,
                        previous.googleClientId,
                        failure.message ?: "Pully could not reach the server. Try again or check Server settings.",
                        // Saved-credential problems are not the server's; they keep the reset page.
                        if (failure is ServerError || failure is RpcTimeoutException || failure !is IllegalStateException) connectionProblem(failure) else null,
                    )
                }
            }
        }
        probe = next
        notifyProbeChanged()
        previousProbe?.cancel()
        next.start()
    }

    /** Resume observation only after the latest source's verification and settings sync settle. */
    suspend fun refreshAndWait(): Boolean {
        refresh()
        return awaitCurrentVerification()
    }

    /** Follows source replacement without waiting for an obsolete, uncooperative transport reply. */
    suspend fun awaitCurrentVerification(): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (closed) return false
            val expected = probe
            val changed = probeChanged
            if (changing || expected == null) {
                changed.await()
                continue
            }
            select<Unit> {
                expected.onJoin { }
                changed.onAwait { }
            }
            if (closed) return false
            if (changing || probe !== expected || !expected.isCompleted) continue
            return state.phase == AccountPhase.Ready && state.problem == null
        }
    }

    private fun notifyProbeChanged() {
        val previous = probeChanged
        probeChanged = CompletableDeferred()
        previous.complete(Unit)
    }

    /** A rendered sign-in page owns a fresh controller and unsaved credentials. */
    override fun signInServices(): SignInServices {
        val source = repo.source()
        val openingEpoch = epoch
        val config = configuration(false)
        return object : SignInServices {
            private var active = true
            private val controller = SignInController(repo, source, { active && current(openingEpoch, source) }) { token, email ->
                checked(storage.acceptSession(token, email, config.generation))
            }
            override val serverLabel = config.server.removePrefix("https://").removePrefix("http://").trimEnd('/')
            override val googleClientId = state.googleClientId
            override suspend fun password(email: String, password: String) = controller.password(email, password)
            override suspend fun google(clientId: String) {
                require(clientId == googleClientId) { "Google sign-in has not been configured for this iOS app." }
                controller.google { googleIdToken(clientId, config.generation) }
            }
            override fun close() { active = false }
        }
    }

    /**
     * Signs in to the server's Cloudflare Access in the host's page. True once its token is saved, when the storage
     * observers start a fresh check; false when the person closed the page.
     */
    override suspend fun accessSignIn(title: String, hint: String): Boolean {
        val generation = configuration(false).generation
        return suspendCancellableCoroutine { continuation ->
            val selection = storage.accessSignIn(title, hint, generation, object : AccessSignInObserver {
                override fun signedIn() { if (continuation.isActive) continuation.resume(true) }
                override fun ended(problem: String?) {
                    if (!continuation.isActive) return
                    if (problem == null) continuation.resume(false)
                    else continuation.resumeWithException(IllegalStateException(problem))
                }
            })
            continuation.invokeOnCancellation { selection.cancel() }
        }
    }

    /** Remote logout is best effort; local credentials are forgotten while offline too. */
    suspend fun signOut() {
        val openingEpoch = epoch
        val source = repo.source()
        try {
            repo.signOut()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        requireOwner(openingEpoch, source)
        checked(storage.forgetSession())
    }

    fun connection(): AccountConnection? = storage.connection(false)
    fun saveServer(server: String, accessId: String, accessSecret: String) = checked(storage.saveServer(server, accessId, accessSecret))
    fun saveAnalysis(engine: String, model: String) {
        val connection = configuration(false)
        checked(storage.saveAnalysis(engine, model, connection.generation))
    }
    override fun retrySavedStorage() { checked(storage.retryStorage()); refresh() }
    /** A confirmation belongs to the source that opened it, including when secure storage is unavailable. */
    override fun resetCredentials(expectedEpoch: Long) {
        if (closed || changing || expectedEpoch != epoch) throw CancellationException("The account source changed.")
        checked(storage.resetCredentials())
        refresh()
    }

    fun close() {
        if (closed) return
        closed = true
        epoch++
        notifyProbeChanged()
        probe?.cancel()
        subscription.cancel()
        invalidated()
    }

    private suspend fun googleIdToken(clientId: String, generation: Long): String? = suspendCancellableCoroutine { continuation ->
        val selection = storage.googleToken(clientId, generation, object : AccountTokenObserver {
            override fun completed(token: String?, problem: String?) {
                if (continuation.isActive) {
                    if (problem == null) continuation.resume(token)
                    else continuation.resumeWithException(IllegalStateException(problem))
                }
            }
        })
        continuation.invokeOnCancellation { selection.cancel() }
    }

    private fun current(openingEpoch: Long, source: RpcSource): Boolean =
        !closed && !changing && openingEpoch == epoch && repo.isCurrent(source)

    private suspend fun requireOwner(openingEpoch: Long, source: RpcSource) {
        currentCoroutineContext().ensureActive()
        if (!current(openingEpoch, source)) throw CancellationException("The account source changed.")
    }

    private fun checked(problem: String?) { if (problem != null) throw IllegalStateException(problem) }
}
