package com.octaviantocan.pulls.data

internal actual class AnalyticsLock {
    private val lock = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(lock, block)
}
