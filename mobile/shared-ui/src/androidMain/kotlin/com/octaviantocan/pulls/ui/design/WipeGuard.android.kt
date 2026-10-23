package com.octaviantocan.pulls.ui.design

internal actual class WipeGuard actual constructor() {
    private val monitor = Any()
    actual fun <T> locked(action: () -> T): T = synchronized(monitor, action)
}
