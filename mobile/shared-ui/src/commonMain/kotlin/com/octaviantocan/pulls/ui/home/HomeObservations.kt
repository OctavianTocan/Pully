package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Source-owned read authority. Native hosts resume it after current account verification. */
class HomeObservations(initialReady: Boolean = true) {
    private class Observer(val changed: (Boolean) -> Unit)
    private val observers = mutableSetOf<Observer>()
    private val reads = mutableSetOf<Job>()
    private var closed = false
    var ready by mutableStateOf(initialReady)
        private set
    var generation by mutableLongStateOf(0L)
        private set

    fun pause() {
        if (!ready) return
        ready = false
        generation++
        val previous = reads.toList()
        reads.clear()
        previous.forEach { it.cancel() }
        publish()
    }

    fun resume() {
        if (closed || ready) return
        generation++
        ready = true
        publish()
    }

    fun close() {
        if (closed) return
        closed = true
        pause()
        observers.clear()
    }

    /** Synchronously reports current read eligibility; cancellation removes exactly this listener. */
    fun observe(changed: (Boolean) -> Unit): HomeLifecycleSubscription {
        val observer = Observer(changed)
        if (!closed) observers += observer
        changed(!closed && ready)
        return object : HomeLifecycleSubscription {
            override fun cancel() { observers -= observer }
        }
    }

    /** Only reads belong here. Accepted server writes keep their separate source lifetime. */
    suspend fun <T> read(block: suspend () -> T): T {
        val opening = generation
        requireReady(opening)
        return coroutineScope {
            val request = async(start = CoroutineStart.LAZY) { block() }
            reads += request
            try {
                requireReady(opening)
                request.start()
                val result = request.await()
                currentCoroutineContext().ensureActive()
                requireReady(opening)
                result
            } finally {
                reads -= request
            }
        }
    }

    private fun requireReady(opening: Long) {
        if (closed || !ready || generation != opening) throw CancellationException("Home observation paused.")
    }

    private fun publish() {
        val opening = generation
        val active = !closed && ready
        for (observer in observers.toList()) {
            if (generation != opening) return
            if (observer in observers) observer.changed(active)
        }
    }
}

/** A suspended observer restarts for the latest drawn selection after current verification settles. */
@Composable
internal fun HomeReadEffect(observations: HomeObservations, vararg keys: Any?, read: suspend () -> Unit) {
    LaunchedEffect(observations, observations.generation, observations.ready, *keys) {
        if (observations.ready) observations.read(read)
    }
}
