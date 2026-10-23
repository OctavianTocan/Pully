package com.octaviantocan.pulls.sound

import platform.Foundation.NSRecursiveLock

internal actual class CueGuard actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> locked(action: () -> T): T {
        lock.lock()
        try { return action() } finally { lock.unlock() }
    }
}

internal actual class CueCounter actual constructor(value: Int) {
    private val guard = CueGuard()
    private var value = value
    actual fun get(): Int = guard.locked { value }
    actual fun incrementAndGet(): Int = guard.locked { ++value }
    actual fun decrementAndGet(): Int = guard.locked { --value }
}

internal actual class CueQueue<T> actual constructor() {
    private val guard = CueGuard()
    private val queue = ArrayDeque<T>()
    actual fun add(value: T) { guard.locked { queue.addLast(value) } }
    actual fun poll(): T? = guard.locked { queue.removeFirstOrNull() }
    actual fun isNotEmpty(): Boolean = guard.locked { queue.isNotEmpty() }
}
