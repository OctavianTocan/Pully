package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PersonalSignInState {
    data object Idle : PersonalSignInState
    data object Starting : PersonalSignInState
    data class Waiting(val expiresAt: Long, val problem: PersonalAuthProblem? = null, val userCode: String? = null) : PersonalSignInState
    data object Finishing : PersonalSignInState
    data class Connected(val account: PersonalAccountStatus) : PersonalSignInState
    data class Failed(val problem: PersonalAuthProblem) : PersonalSignInState
}

/** Repository consent is minted by the verified runtime, never from route or form input. */
sealed class PersonalSignInPurpose {
    data object Public : PersonalSignInPurpose()
    data object Connect : PersonalSignInPurpose()
    internal class Repositories(val session: StoredPersonalSession) : PersonalSignInPurpose()
}

/**
 * A native UI owner calls this controller on its UI dispatcher. Normal browser background only pauses
 * polling; source/route retirement calls close synchronously. Cleanup uses a longer-lived host scope.
 */
class PersonalSignInController(
    private val client: PersonalAuthClient,
    parentScope: CoroutineScope,
    private val cleanupScope: CoroutineScope,
    private val staging: PersonalSessionStaging,
    private val nowSeconds: () -> Long,
    private val isCurrent: () -> Boolean,
    private val openBrowser: (String) -> Unit,
    private val accept: (PersonalSession) -> Unit,
    private val source: RpcSource = client.source(),
    private val purpose: PersonalSignInPurpose = PersonalSignInPurpose.Public,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow<PersonalSignInState>(PersonalSignInState.Idle)
    val state: StateFlow<PersonalSignInState> = mutableState.asStateFlow()
    private var pending: PersonalAuthAttempt? = null
    private var provisional: PersonalSession? = null
    private var browserOpened = false
    private var retired = false
    private var inForeground = true
    private var operation = 0L
    private var pollRevision = 0L
    private var claiming = false
    private var startJob: Job? = null
    private var pollJob: Job? = null
    private var expiryJob: Job? = null

    fun start(): Boolean {
        if (!current() || pending != null || provisional != null || startJob?.isActive == true) return false
        if (purpose != PersonalSignInPurpose.Public && !inForeground) return false
        val revision = ++operation
        mutableState.value = PersonalSignInState.Starting
        startJob = scope.launch {
            try {
                if (!client.enabled(source)) throw PersonalAuthError(PersonalAuthProblem.NotConfigured)
                requireCurrent(revision)
                val proof = PersonalAuthProof.create()
                val attempt = when (val action = purpose) {
                    PersonalSignInPurpose.Public -> client.start(proof, source)
                    PersonalSignInPurpose.Connect -> client.connect(proof, source)
                    is PersonalSignInPurpose.Repositories -> client.startRepositoryConsent(proof, action.session, source)
                }
                requireCurrent(revision)
                // The backend uses ten minutes. A local timer also bounds ownership if the wall clock changes.
                val seconds = (attempt.expiresAt - nowSeconds()).coerceAtMost(600)
                if (seconds <= 0) throw PersonalAuthError(PersonalAuthProblem.Expired)
                pending = attempt
                browserOpened = false
                mutableState.value = PersonalSignInState.Waiting(attempt.expiresAt, userCode = attempt.userCode)
                expiryJob = scope.launch {
                    delay(seconds * 1000)
                    if (current() && pending === attempt) fail(PersonalAuthProblem.Expired)
                }
                resumePolling()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: PersonalAuthError) { if (current() && operation == revision) fail(error.problem) }
            catch (_: Exception) { if (current() && operation == revision) fail(PersonalAuthProblem.Unavailable) }
            finally { if (operation == revision) startJob = null }
        }
        return true
    }

    /** Called by the actual native lifecycle, including the return from the system browser. */
    fun foreground(active: Boolean) {
        if (!current()) { close(); return }
        if (inForeground == active) return
        inForeground = active
        if (active) resumePolling()
        else if (!claiming) {
            pollRevision++
            pollJob?.cancel()
            pollJob = null
        }
    }

    fun retry(): Boolean = resumePolling()

    /** Device sign-in: opens github.com/login/device once the person has the code; polling carries on meanwhile. */
    fun openGitHub(): Boolean {
        val attempt = pending ?: return false
        if (!current() || attempt.userCode == null) return false
        try { openBrowser(attempt.authorizeUrl) }
        catch (_: Exception) { fail(PersonalAuthProblem.Unavailable); return false }
        return true
    }

    fun cancel() {
        clearPending()
        mutableState.value = PersonalSignInState.Idle
    }

    fun close() {
        if (retired) return
        retired = true
        clearPending()
        mutableState.value = PersonalSignInState.Idle
        lifetime.cancel()
    }

    private fun resumePolling(): Boolean {
        val attempt = pending ?: return false
        if (!current() || !inForeground || claiming || pollJob?.isActive == true) return false
        // A device code is copied first, so GitHub opens only when the person asks (openGitHub).
        if (!browserOpened && attempt.userCode == null) {
            browserOpened = true
            try { openBrowser(attempt.authorizeUrl) }
            catch (_: Exception) { fail(PersonalAuthProblem.Unavailable); return false }
        }
        // Opening the native browser can synchronously move the activity to background.
        if (!current() || !inForeground || pending !== attempt) return false
        val revision = ++pollRevision
        mutableState.value = PersonalSignInState.Waiting(attempt.expiresAt, userCode = attempt.userCode)
        pollJob = scope.launch {
            try {
                while (true) {
                    requirePending(attempt, revision)
                    val answer = try { client.status(attempt) }
                    catch (error: PersonalAuthError) {
                        requirePending(attempt, revision)
                        if (error.problem == PersonalAuthProblem.RateLimited) {
                            delay(attempt.pollAfterMs)
                            continue
                        }
                        if (error.problem in retryablePersonalProblems) {
                            mutableState.value = PersonalSignInState.Waiting(attempt.expiresAt, error.problem, attempt.userCode)
                            return@launch
                        }
                        throw error
                    }
                    requirePending(attempt, revision)
                    when (answer.phase) {
                        PersonalAttemptPhase.Pending -> delay(attempt.pollAfterMs)
                        PersonalAttemptPhase.Ready -> {
                            claiming = true
                            mutableState.value = PersonalSignInState.Finishing
                            requirePending(attempt, revision, foregroundRequired = false)
                            // Once begun, a bounded one-use claim finishes across normal browser background.
                            val session = client.claim(attempt)
                            requirePending(attempt, revision, foregroundRequired = false)
                            pending = null
                            expiryJob?.cancel()
                            expiryJob = null
                            provisional = session
                            staging.savePending(session)
                            requireProvisional(session, revision)
                            val confirmedStatus = client.account(session.token, source)
                            requireProvisional(session, revision)
                            if (confirmedStatus.accountId != session.status.accountId || confirmedStatus.githubId != session.status.githubId ||
                                confirmedStatus.authority != session.status.authority) throw PersonalAuthError(PersonalAuthProblem.Reconnect)
                            val confirmed = PersonalSession(session.token, confirmedStatus, session.origin)
                            requireProvisional(session, revision)
                            provisional = null
                            try {
                                accept(confirmed)
                                if (current()) mutableState.value = PersonalSignInState.Connected(confirmed.status)
                            } catch (error: Exception) {
                                retireSession(session)
                                if (error is CancellationException) throw error
                                if (current()) mutableState.value = PersonalSignInState.Failed(PersonalAuthProblem.Unavailable)
                            }
                            return@launch
                        }
                        PersonalAttemptPhase.Failed -> { fail(requireNotNull(answer.failure)); return@launch }
                        PersonalAttemptPhase.Cancelled -> { cancel(); return@launch }
                        PersonalAttemptPhase.Expired -> { fail(PersonalAuthProblem.Expired); return@launch }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: PersonalAuthError) {
                if (current() && pollRevision == revision && (pending === attempt || provisional != null)) fail(error.problem)
            }
            catch (_: Exception) {
                if (current() && pollRevision == revision && (pending === attempt || provisional != null)) fail(PersonalAuthProblem.Unavailable)
            }
            finally {
                if (pollRevision == revision) {
                    claiming = false
                    pollJob = null
                }
            }
        }
        return true
    }

    private fun fail(problem: PersonalAuthProblem) {
        clearPending()
        mutableState.value = PersonalSignInState.Failed(problem)
    }

    private fun clearPending() {
        val attempt = pending
        val session = provisional
        pending = null
        provisional = null
        operation++
        pollRevision++
        browserOpened = false
        claiming = false
        startJob?.cancel(); startJob = null
        pollJob?.cancel(); pollJob = null
        expiryJob?.cancel(); expiryJob = null
        if (attempt != null) cleanupScope.launch { runCatching { client.cancelRetired(attempt) } }
        if (session != null) retireSession(session)
    }

    private fun retireSession(session: PersonalSession) {
        cleanupScope.launch {
            runCatching {
                client.signOutRetired(session.token)
                staging.discardPending(session)
            }
        }
    }

    private fun current(): Boolean = !retired && lifetime.isActive && isCurrent() && client.isCurrent(source)
    private suspend fun requireCurrent(revision: Long) {
        currentCoroutineContext().ensureActive()
        if (!current() || operation != revision) throw CancellationException("The personal sign-in owner changed.")
    }
    private suspend fun requirePending(attempt: PersonalAuthAttempt, revision: Long, foregroundRequired: Boolean = true) {
        currentCoroutineContext().ensureActive()
        if (!current() || pending !== attempt || pollRevision != revision || foregroundRequired && !inForeground)
            throw CancellationException("The personal sign-in attempt changed.")
    }
    private suspend fun requireProvisional(session: PersonalSession, revision: Long) {
        currentCoroutineContext().ensureActive()
        if (!current() || provisional !== session || pollRevision != revision)
            throw CancellationException("The personal session confirmation owner changed.")
    }
}

private val retryablePersonalProblems = setOf(
    PersonalAuthProblem.Unavailable, PersonalAuthProblem.Unreachable, PersonalAuthProblem.Timeout,
    PersonalAuthProblem.InvalidResponse, PersonalAuthProblem.WebPage, PersonalAuthProblem.Redirect, PersonalAuthProblem.HttpRejected,
)
