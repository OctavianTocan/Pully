package com.octaviantocan.pulls.ui.review

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Foreground reads have their own cancellable generation; accepted work keeps the session lifetime. */
internal class ReviewObservation(private val lifetime: CoroutineScope) {
    private enum class Phase { Active, Paused, Reconciling }
    private var phase = Phase.Active
    private val readySignal = MutableStateFlow(true)
    var generation = 0L
        private set
    var scope = newScope()
        private set
    val foreground: Boolean get() = phase != Phase.Paused
    val ready: Boolean get() = phase == Phase.Active

    private fun newScope() = CoroutineScope(lifetime.coroutineContext + SupervisorJob(lifetime.coroutineContext[Job]))
    fun current(expected: Long): Boolean = foreground && generation == expected

    fun pause() {
        if (!foreground) return
        phase = Phase.Paused
        readySignal.value = false
        generation++
        scope.cancel()
    }

    fun resume(): Boolean {
        if (foreground) return false
        generation++
        scope = newScope()
        phase = Phase.Reconciling
        return true
    }

    fun reconciled(expected: Long): Boolean {
        if (!current(expected) || ready) return false
        phase = Phase.Active
        readySignal.value = true
        return true
    }

    suspend fun awaitReady() {
        while (!ready) readySignal.first { it }
        currentCoroutineContext().ensureActive()
    }
}
