package com.octaviantocan.pulls.sound

import kotlin.time.TimeSource

/** One serial synthesis worker; each native host owns its lifetime and thread. */
fun interface CueWorker {
    fun execute(action: () -> Unit)
}

internal expect class CueGuard() {
    fun <T> locked(action: () -> T): T
}

internal expect class CueCounter(value: Int = 0) {
    fun get(): Int
    fun incrementAndGet(): Int
    fun decrementAndGet(): Int
}

internal expect class CueQueue<T>() {
    fun add(value: T)
    fun poll(): T?
    fun isNotEmpty(): Boolean
}

private val cueClockOrigin = TimeSource.Monotonic.markNow()
internal fun cueNowMillis(): Double = cueClockOrigin.elapsedNow().inWholeNanoseconds / 1_000_000.0
