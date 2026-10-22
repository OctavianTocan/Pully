package com.octaviantocan.pulls.data

import platform.Foundation.NSRecursiveLock

internal actual class AnalyticsLock {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }
}
