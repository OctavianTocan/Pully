package com.octaviantocan.pulls.data

internal actual class PullSizeGuard actual constructor() {
    private val lock = Any()
    actual fun <T> locked(block: () -> T): T = synchronized(lock, block)
}
