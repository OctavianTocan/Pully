package com.octaviantocan.pulls.data

import platform.Foundation.*

internal actual class PullSizeGuard actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> locked(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }
}
