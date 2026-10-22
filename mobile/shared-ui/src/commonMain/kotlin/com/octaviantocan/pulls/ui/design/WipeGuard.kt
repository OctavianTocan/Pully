package com.octaviantocan.pulls.ui.design

/** Registration and snapshot capture retain the original reentrant surface-list exclusion. */
internal expect class WipeGuard() {
    fun <T> locked(action: () -> T): T
}
