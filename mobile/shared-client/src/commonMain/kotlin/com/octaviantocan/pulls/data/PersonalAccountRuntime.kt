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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

data class PersonalAccountView(
    val account: PersonalAccountStatus? = null,
    val problem: PersonalAuthProblem? = null,
    val checking: Boolean = false,
    val saved: Boolean = false,
    val workspaceRevision: Long = 0,
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
    private val workspaceHeaders: (String) -> Map<String, String> = { emptyMap() },
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var revision = 0L
    private var restoreRevision = 0L
    private var closed = false
    private var inForeground = true
    private var restoring: Job? = null
    private var signIn: SignIn? = null
    private var reader: PersonalReadClient? = null
    private var accountReader: PersonalAccountReadClient? = null
    private var verified: StoredPersonalSession? = null
    private var workspace: PersonalWorkspace? = null
    private var presentation: PersonalWorkspaceOwner? = null
    private var workspaceRevision = 0L
    private class ReadRetirement(val retire: () -> Unit, val recover: ((PersonalAuthProblem) -> Unit)?)
    private val readRetirements = mutableListOf<ReadRetirement>()
    private val readRequests = Semaphore(3)
    private val client = origin?.let { PersonalAuthClient(it, transport, { revision }) }
    private val mutableView = MutableStateFlow(PersonalAccountView())
    val view: StateFlow<PersonalAccountView> = mutableView.asStateFlow()
    val configured: Boolean get() = client != null

    /** A saved profile or public-only grant can never mount native private services. */
    fun workspace(): PersonalWorkspace? {
        if (!inForeground) return null
        workspace?.takeIf { it.current }?.let { return it }
        val client = client ?: return null
        val (record, status) = verifiedRecord() ?: return null
        if (status.authority != PersonalAuthority.PrivateRepositories) return null
        val openingRevision = revision
        val openingWorkspace = ++workspaceRevision
        val server = "${client.origin.base}/workspace"
        return PersonalWorkspace(status.accountId, status.githubId, status.login, server, openingWorkspace,
            workspaceHeaders(client.origin.base) + ("Authorization" to "Bearer ${record.token}"), transport,
            owns = {
                current() && inForeground && revision == openingRevision && workspaceRevision == openingWorkspace &&
                    verifiedRecord()?.let { (saved, account) -> saved.same(record) && account.authority == PersonalAuthority.PrivateRepositories } == true
            }, rejected = { if (current() && revision == openingRevision) rejectAccount(record, PersonalAuthProblem.Unauthorized) },
        ).also { workspace = it }
    }

    /** Stable presentation identity, never a foreground dispatch grant or a mutable account selector. */
    fun workspaceOwner(): PersonalWorkspaceOwner? {
        presentation?.takeIf { it.current }?.let { return it }
        val first = workspace() ?: return null
        val (record, _) = verifiedRecord() ?: return null
        val openingRevision = revision
        return PersonalWorkspaceOwner(first, owns = {
            current() && revision == openingRevision && verifiedRecord()?.let { (saved, account) ->
                saved.same(record) && account.authority == PersonalAuthority.PrivateRepositories
            } == true
        }, acquire = { workspace() }).also { presentation = it }
    }

    private fun retireWorkspace() {
        workspace?.retire()
        workspace = null
        workspaceRevision++
    }

    /**
     * Whether a saved private-repository login waits to be verified by [restore]: a host shows the signed-in side's
     * place for it instead of Explore. Reads storage only; it is never a capability and mounts nothing.
     *
     * @returns true when an accepted private-repository record is saved for this origin.
     */
    fun savedPrivateLogin(): Boolean {
        if (!current()) return false
        val client = client ?: return false
        return try { vault.accepted(client.origin)?.authority == PersonalAuthority.PrivateRepositories } catch (_: Exception) { false }
    }

    /** Native admission probes storage without restoring a profile, writing a session or making a request. */
    fun verifyStorage(): Boolean {
        if (!current() || !configured) return false
        return try { vault.verifyStorage(); current() } catch (_: Exception) { false }
    }

    /** Saved and provisional credentials have no read authority before fresh account verification. */
    fun personalReads(): PersonalReadClient? {
        val client = client ?: return null
        if (!current()) return null
        reader?.takeIf { it.current }?.let { return it }
        val (record, account) = verifiedRecord() ?: return null
        val openingRevision = revision
        return PersonalReadClient(client.origin, record, transport, {
            current() && revision == openingRevision && verifiedRecord()?.let { (saved, status) ->
                saved.same(record) && status.authority == account.authority
            } == true
        }, readRequests, account.authority, { rejectRead(record, openingRevision, it) }).also { reader = it }
    }

    /** Private pages require the separately confirmed repository-consent session. */
    fun accountReads(): PersonalAccountReadClient? {
        val client = client ?: return null
        accountReader?.takeIf { it.current }?.let { return it }
        val (record, account) = verifiedRecord() ?: return null
        if (account.authority != PersonalAuthority.PrivateRepositories) return null
        val openingRevision = revision
        return PersonalAccountReadClient(client.origin, record, transport, {
            current() && revision == openingRevision && verifiedRecord()?.let { (saved, status) ->
                saved.same(record) && status.authority == PersonalAuthority.PrivateRepositories
            } == true
        }, readRequests, { rejectRead(record, openingRevision, it) }).also { accountReader = it }
    }

    /** Native/UI owners clear retained page data synchronously before an account capability changes. */
    fun onReadsRetired(listener: () -> Unit): () -> Unit = onReadsRetired(listener, null)

    /** Terminal rejection recovery runs after all read owners are cleared, under the same current source. */
    fun onReadsRetired(listener: () -> Unit, onAccountRejected: ((PersonalAuthProblem) -> Unit)?): () -> Unit {
        if (!current()) { listener(); return {} }
        val owner = ReadRetirement(listener, onAccountRejected)
        readRetirements.add(owner)
        return { readRetirements.remove(owner) }
    }

    private fun retireReads(): List<(PersonalAuthProblem) -> Unit> {
        presentation?.retire()
        presentation = null
        retireWorkspace()
        reader = null
        accountReader = null
        val owners = readRetirements.toList()
        owners.forEach { runCatching { it.retire() } }
        return owners.mapNotNull { it.recover }
    }

    private fun rejectRead(record: StoredPersonalSession, openingRevision: Long, problem: PersonalReadProblem) {
        if (!current() || revision != openingRevision || verified?.same(record) != true) return
        val rejection = when (problem) {
            PersonalReadProblem.Unauthorized -> PersonalAuthProblem.Unauthorized
            PersonalReadProblem.Reconnect -> PersonalAuthProblem.Reconnect
            else -> return
        }
        rejectAccount(record, rejection)
    }

    private fun rejectAccount(record: StoredPersonalSession, problem: PersonalAuthProblem) {
        verified = null
        val rejection = ++revision
        val verification = ++restoreRevision
        restoring?.cancel(); restoring = null
        signIn?.close()
        val recovery = retireReads()
        fun ownsRejection() = current() && revision == rejection && restoreRevision == verification && verified == null
        if (!ownsRejection()) return
        mutableView.value = PersonalAccountView(problem = problem, saved = true)
        if (!ownsRejection()) return
        var saved = true
        try {
            vault.retireAccepted(record)
            saved = false
            recoverPending()
        } catch (_: Exception) { }
        if (!ownsRejection()) return
        mutableView.value = PersonalAccountView(problem = problem, saved = saved)
        recovery.forEach { action ->
            if (ownsRejection()) runCatching { action(problem) }
        }
    }

    private fun verifiedRecord(): Pair<StoredPersonalSession, PersonalAccountStatus>? {
        val client = client ?: return null
        if (!current()) return null
        val status = mutableView.value.account ?: return null
        val record = try { vault.accepted(client.origin) } catch (_: Exception) { null } ?: return null
        if (verified?.same(record) != true) return null
        if (status.accountId != record.accountId || status.githubId != record.githubId ||
            status.authority == PersonalAuthority.PrivateRepositories && record.authority != PersonalAuthority.PrivateRepositories) return null
        return record to status
    }

    /** Restored accepted credentials are verified before they become a visible personal account. */
    fun restore() {
        if (!current() || restoring?.isActive == true || signIn?.inProgress == true) return
        val client = client ?: return
        val source = client.source()
        val attempt = ++restoreRevision
        var checkingRecord: StoredPersonalSession? = null
        restoring = scope.launch {
            try {
                recoverPending()
                val record = vault.accepted(client.origin)
                if (record == null) { mutableView.value = PersonalAccountView(); return@launch }
                checkingRecord = record
                mutableView.value = mutableView.value.copy(checking = true, saved = true, problem = null)
                val status = client.account(record.token, source)
                requireCurrent(attempt)
                if (vault.accepted(client.origin)?.same(record) != true)
                    throw CancellationException("The restored personal credential changed.")
                if (status.accountId != record.accountId || status.githubId != record.githubId ||
                    status.authority == PersonalAuthority.PrivateRepositories && record.authority != PersonalAuthority.PrivateRepositories)
                    throw PersonalAuthError(PersonalAuthProblem.Reconnect)
                if (verified?.same(record) != true || mutableView.value.account?.authority != status.authority) retireReads()
                requireCurrent(attempt)
                verified = record
                mutableView.value = PersonalAccountView(account = status, saved = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current() && restoreRevision == attempt) {
                    if (checkingRecord != null && try { vault.accepted(client.origin)?.same(checkingRecord) != true } catch (_: Exception) { true })
                        return@launch
                    val problem = (error as? PersonalAuthError)?.problem ?: PersonalAuthProblem.Unavailable
                    val record = checkingRecord
                    if (record != null && problem in setOf(PersonalAuthProblem.Unauthorized, PersonalAuthProblem.Expired, PersonalAuthProblem.Reconnect))
                        rejectAccount(record, problem)
                    else mutableView.value = mutableView.value.copy(problem = problem, checking = false)
                }
            } finally { if (restoreRevision == attempt) restoring = null }
        }
    }

    /** Native lifecycle callbacks deliver this before publishing any deferred UI state. */
    fun foreground(active: Boolean) {
        if (!current()) return
        if (inForeground != active) {
            inForeground = active
            if (!active) retireWorkspace() else workspaceRevision++
            mutableView.value = mutableView.value.copy(workspaceRevision = workspaceRevision)
        }
        signIn?.controller?.foreground(active)
    }

    fun openSignIn(parentScope: CoroutineScope, openBrowser: (String) -> Unit): SignIn? {
        return openSignIn(parentScope, openBrowser, PersonalSignInPurpose.Public)
    }

    /** Normal account entry includes repository consent; older public sessions upgrade as the same account. */
    fun openConnect(parentScope: CoroutineScope, openBrowser: (String) -> Unit): SignIn? {
        if (verifiedRecord()?.second?.authority == PersonalAuthority.PublicRead)
            return openRepositoryConsent(parentScope, openBrowser)
        return openSignIn(parentScope, openBrowser, PersonalSignInPurpose.Connect)
    }

    private fun openSignIn(parentScope: CoroutineScope, openBrowser: (String) -> Unit, purpose: PersonalSignInPurpose): SignIn? {
        val client = client ?: return null
        if (!current()) return null
        signIn?.close()
        return SignIn(client, parentScope, openBrowser, purpose).also {
            signIn = it
            it.controller.foreground(inForeground)
        }
    }

    /** Opening this action captures the accepted account; form/route data cannot choose the subject. */
    fun openRepositoryConsent(parentScope: CoroutineScope, openBrowser: (String) -> Unit): SignIn? {
        val (record, _) = verifiedRecord() ?: return null
        return openSignIn(parentScope, openBrowser, PersonalSignInPurpose.Repositories(record))
    }

    inner class SignIn internal constructor(client: PersonalAuthClient, parentScope: CoroutineScope, openBrowser: (String) -> Unit,
        private val purpose: PersonalSignInPurpose = PersonalSignInPurpose.Public) {
        private val staging = vault.openStaging()
        private var disposed = false
        val controller = PersonalSignInController(client, parentScope, cleanupScope, staging, nowSeconds,
            ::owned, openBrowser, ::accept, purpose = purpose)
        val repositoryConsent: Boolean get() = purpose is PersonalSignInPurpose.Repositories
        private fun owned(): Boolean = !disposed && current() && (purpose !is PersonalSignInPurpose.Repositories ||
            verifiedRecord()?.first?.same(purpose.session) == true)
        internal val inProgress: Boolean get() = owned() && controller.state.value.let {
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
            verified = null
            revision++
            retireReads()
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
        verified = null
        revision++
        retireReads()
        readRetirements.clear()
        restoreRevision++
        signIn?.close()
        mutableView.value = PersonalAccountView()
        lifetime.cancel()
        recoverPending()
    }

    private fun accept(session: PersonalSession) {
        check(current())
        vault.promote(session)
        verified = vault.accepted(requireNotNull(client).origin)
        revision++
        retireReads()
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
