package com.octaviantocan.pulls.sound

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

internal actual class CueGuard actual constructor() {
    private val monitor = Any()
    actual fun <T> locked(action: () -> T): T = synchronized(monitor, action)
}

internal actual class CueCounter actual constructor(value: Int) {
    private val counter = AtomicInteger(value)
    actual fun get(): Int = counter.get()
    actual fun incrementAndGet(): Int = counter.incrementAndGet()
    actual fun decrementAndGet(): Int = counter.decrementAndGet()
}

internal actual class CueQueue<T> actual constructor() {
    private val queue = ConcurrentLinkedQueue<T>()
    actual fun add(value: T) { queue.add(value) }
    actual fun poll(): T? = queue.poll()
    actual fun isNotEmpty(): Boolean = queue.isNotEmpty()
}
