package com.octaviantocan.pulls.ui.design

import platform.Foundation.NSRecursiveLock

internal actual class WipeGuard actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> locked(action: () -> T): T {
        lock.lock()
        try { return action() } finally { lock.unlock() }
    }
}
