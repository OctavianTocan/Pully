package com.octaviantocan.pulls.data

internal expect class PullSizeGuard() {
    fun <T> locked(block: () -> T): T
}
