package com.octaviantocan.pulls.ui.personal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.PersonalAuthProblem
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One public workspace owns the account sheet and its verified readers on the supplied stack. */
class PersonalBrowseMount(
    val account: PersonalAccountRuntime,
    parentScope: CoroutineScope,
    private val source: NativeEntryState.GuestSource,
    private val navigation: AppNavigation,
) {
    class Presentation internal constructor(val repositoryConsent: Boolean)

    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var active = true
    private var inForeground = false
    private var available = false
    private var pendingRecovery: PersonalAuthProblem? = null
    private var reading: PersonalBrowseSession? = null
    private var readsActive = false
    private var stopSource: (() -> Unit)? = null
    var session by mutableStateOf<PersonalBrowseSession?>(null)
        private set
    var presentation by mutableStateOf<Presentation?>(null)
        private set
    val current: Boolean get() = active && lifetime.isActive && source.current

    init {
        stopSource = source.onRetired(::close)
        if (current) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            account.view.collect { reconcile() }
        }
    }

    private fun reconcile() {
        if (!current) return
        if (account.personalReads() == null) {
            session?.close()
            session = null
        } else if (session?.current != true) {
            session?.close()
            session = PersonalBrowseSession(account, scope, source, navigation, ::recoverAccount)
        }
        updateReads()
    }

    /** Gate new work by actual lifecycle and admitted secure storage before deferred UI state. */
    fun foreground(active: Boolean, available: Boolean = active) {
        if (!current) return
        inForeground = active
        this.available = active && available
        account.foreground(this.available)
        if (!current) return
        showRecovery()
        updateReads()
    }

    /** A completed current native secure-store probe may enable the already-active workspace. */
    fun availability(available: Boolean) {
        if (!current) return
        this.available = inForeground && available
        account.foreground(this.available)
        if (!current) return
        showRecovery()
        updateReads()
    }

    fun openAccount() {
        if (!canPresent() || presentation != null) return
        reconcile()
        if (!canPresent()) return
        presentation = Presentation(false)
        updateReads()
    }

    fun dismiss(opening: Presentation) {
        if (!current || presentation !== opening) return
        presentation = null
        updateReads()
    }

    fun openPulls(opening: PersonalBrowseSession) {
        if (!owns(opening)) return
        activate(opening)
        opening.openPulls()
    }

    fun openRepositories(opening: PersonalBrowseSession) {
        if (!owns(opening)) return
        activate(opening)
        opening.openRepositories()
    }

    fun openRepositoryConsent(opening: PersonalBrowseSession) {
        if (!owns(opening) || presentation != null || opening.privateEnabled || opening.capabilities?.consentAvailable != true) return
        presentation = Presentation(true)
        updateReads()
    }

    private fun canPresent(): Boolean = current && inForeground && available && account.configured
    private fun owns(opening: PersonalBrowseSession): Boolean = canPresent() && session === opening && opening.current

    private fun activate(opening: PersonalBrowseSession) {
        reading = opening
        readsActive = true
        opening.foreground(true)
    }

    private fun recoverAccount(problem: PersonalAuthProblem) {
        if (!current) return
        session = null
        pendingRecovery = problem
        showRecovery()
        updateReads()
    }

    private fun showRecovery() {
        if (!canPresent() || pendingRecovery == null) return
        pendingRecovery = null
        presentation = Presentation(false)
    }

    fun keepRoutes() {
        if (!current) return
        session?.keepRoutes()
        updateReads()
    }

    private fun updateReads() {
        val next = session?.takeIf { it.current }
        val visible = next != null && canPresent() &&
            (presentation != null || navigation.stack.lastOrNull()?.let(next::admits) == true)
        if (reading !== next) {
            reading?.foreground(false)
            reading = next
            readsActive = false
        }
        if (readsActive != visible) {
            readsActive = visible
            next?.foreground(visible)
        }
    }

    fun close() {
        if (!active) return
        active = false
        stopSource?.invoke(); stopSource = null
        pendingRecovery = null
        presentation = null
        session?.close(); session = null
        reading = null
        readsActive = false
        scope.cancel()
    }
}
