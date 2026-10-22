package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PersonalAccountView(
    val account: PersonalAccountStatus? = null,
    val problem: PersonalAuthProblem? = null,
    val checking: Boolean = false,
    val saved: Boolean = false,
)

/** One public-entry lifetime. Native hosts call all methods and storage on their UI dispatcher. */
class PersonalAccountRuntime(
    origin: PersonalAuthOrigin?,
    private val vault: PersonalSessionVault,
    parentScope: CoroutineScope,
    private val cleanupScope: CoroutineScope,
    private val nowSeconds: () -> Long,
    private val isCurrent: () -> Boolean,
    private val onIdentityChanged: () -> Unit,
    private val transport: RpcTransport = personalAuthTransport(),
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var revision = 0L
    private var restoreRevision = 0L
    private var closed = false
    private var inForeground = true
    private var restoring: Job? = null
    private var signIn: SignIn? = null
    private val client = origin?.let { PersonalAuthClient(it, transport, { revision }) }
    private val mutableView = MutableStateFlow(PersonalAccountView())
    val view: StateFlow<PersonalAccountView> = mutableView.asStateFlow()
    val configured: Boolean get() = client != null

    /** Restored accepted credentials are verified before they become a visible personal account. */
    fun restore() {
        if (!current() || restoring?.isActive == true || signIn?.inProgress == true) return
        val client = client ?: return
        val source = client.source()
        val attempt = ++restoreRevision
        restoring = scope.launch {
            try {
                recoverPending()
                val record = vault.accepted(client.origin)
                if (record == null) { mutableView.value = PersonalAccountView(); return@launch }
                mutableView.value = mutableView.value.copy(checking = true, saved = true, problem = null)
                val status = client.account(record.token, source)
                requireCurrent(attempt)
                if (status.accountId != record.accountId || status.githubId != record.githubId || status.authority != PersonalAuthority.PublicRead)
                    throw PersonalAuthError(PersonalAuthProblem.Reconnect)
                mutableView.value = PersonalAccountView(account = status, saved = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current() && restoreRevision == attempt) {
                    val problem = (error as? PersonalAuthError)?.problem ?: PersonalAuthProblem.Unavailable
                    var saved = mutableView.value.saved
                    var account = mutableView.value.account
                    if (problem in setOf(PersonalAuthProblem.Unauthorized, PersonalAuthProblem.Expired, PersonalAuthProblem.Reconnect)) {
                        try {
                            vault.accepted(client.origin)?.let(vault::retireAccepted)
                            saved = false
                            account = null
                            recoverPending()
                        } catch (_: Exception) { }
                    }
                    mutableView.value = PersonalAccountView(account = account, problem = problem, saved = saved)
                }
            } finally { if (restoreRevision == attempt) restoring = null }
        }
    }

    /** Native lifecycle callbacks deliver this before publishing any deferred UI state. */
    fun foreground(active: Boolean) {
        if (!current()) return
        inForeground = active
        signIn?.controller?.foreground(active)
    }

    fun openSignIn(parentScope: CoroutineScope, openBrowser: (String) -> Unit): SignIn? {
        val client = client ?: return null
        if (!current()) return null
        signIn?.close()
        return SignIn(client, parentScope, openBrowser).also {
            signIn = it
            it.controller.foreground(inForeground)
        }
    }

    inner class SignIn internal constructor(client: PersonalAuthClient, parentScope: CoroutineScope, openBrowser: (String) -> Unit) {
        private val staging = vault.openStaging()
        private var disposed = false
        val controller = PersonalSignInController(client, parentScope, cleanupScope, staging, nowSeconds,
            { !disposed && current() }, openBrowser, ::accept)
        internal val inProgress: Boolean get() = controller.state.value.let {
            it is PersonalSignInState.Starting || it is PersonalSignInState.Waiting || it is PersonalSignInState.Finishing
        }

        fun close() {
            if (disposed) return
            disposed = true
            controller.close()
            staging.close()
            if (signIn === this) signIn = null
        }
    }

    /** Keeps the captured bearer durably queued until server sign-out is confirmed. */
    fun signOut() {
        val client = client ?: return
        if (!current()) return
        try {
            vault.accepted(client.origin)?.let(vault::retireAccepted)
            revision++
            restoreRevision++
            restoring?.cancel(); restoring = null
            signIn?.close()
            mutableView.value = PersonalAccountView()
            recoverPending()
            onIdentityChanged()
        } catch (_: Exception) {
            if (current()) mutableView.value = mutableView.value.copy(problem = PersonalAuthProblem.Unavailable)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        revision++
        restoreRevision++
        signIn?.close()
        mutableView.value = PersonalAccountView()
        lifetime.cancel()
        recoverPending()
    }

    private fun accept(session: PersonalSession) {
        check(current())
        vault.promote(session)
        revision++
        restoreRevision++
        recoverPending()
        onIdentityChanged()
        if (current()) mutableView.value = PersonalAccountView(account = session.status, saved = true)
    }

    private fun recoverPending() {
        cleanupScope.launch {
            cleanupMutex.withLock {
                val records = try { vault.pending() } catch (_: Exception) { return@withLock }
                for (record in records) {
                    try {
                        val recovery = PersonalAuthClient(PersonalAuthOrigin.parse(record.origin), transport)
                        recovery.signOutRetired(record.token)
                        vault.discardPending(record)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
            }
        }
    }

    private fun current(): Boolean = !closed && lifetime.isActive && isCurrent()
    private suspend fun requireCurrent(attempt: Long) {
        currentCoroutineContext().ensureActive()
        if (!current() || restoreRevision != attempt) throw CancellationException("The personal account owner changed.")
    }

    companion object { private val cleanupMutex = Mutex() }
}
