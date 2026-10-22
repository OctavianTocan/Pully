package com.octaviantocan.pulls.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Native scene activity and current account verification jointly authorize disposable reads. */
class AccountObservationOwner(
    private val scope: CoroutineScope,
    private val verify: suspend (refresh: Boolean) -> Boolean,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
) {
    private var closed = false
    private var foreground = false
    private var ready = false
    private var generation = 0L
    private var pending: Job? = null

    init { pause() }

    fun resumed() {
        if (closed) return
        foreground = true
        check(refresh = true)
    }

    fun stopped() {
        foreground = false
        reset()
    }

    fun sourceChanged() {
        if (closed) return
        if (foreground) check(refresh = false) else reset()
    }

    /** A user retry/sign-in can finish while the same native scene remains in the foreground. */
    fun settled() {
        if (!closed && foreground && !ready) check(refresh = false)
    }

    fun close() {
        if (closed) return
        closed = true
        foreground = false
        reset()
    }

    private fun reset(): Long {
        val opening = ++generation
        ready = false
        val previous = pending
        pending = null
        pause()
        previous?.cancel()
        return opening
    }

    private fun check(refresh: Boolean) {
        val opening = reset()
        if (closed || !foreground || generation != opening) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val verified = verify(refresh)
            currentCoroutineContext().ensureActive()
            if (verified && !closed && foreground && generation == opening) {
                ready = true
                resume()
            }
        }
        pending = next
        next.start()
    }
}
