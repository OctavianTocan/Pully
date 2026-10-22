package com.octaviantocan.pulls.ios

import com.octaviantocan.pulls.ui.home.HomeLifecycleSubscription

/** Required only by the personal-account mount; Home's started lifecycle keeps its existing meaning. */
interface NativePersonalForegroundActions {
    fun observePersonalForeground(observer: NativePersonalForegroundObserver): HomeLifecycleSubscription
}

interface NativePersonalForegroundObserver {
    fun changed(active: Boolean)
}
