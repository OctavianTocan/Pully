package com.octaviantocan.pulls.ui.home

/** Thin native services: all screen layout/state and the navigation stack remain in Compose. */
interface NativeHomeActions {
    fun copyText(text: String)
    /** Opens externally, bypassing the app's own incoming-link filter. */
    fun openOnGithub(url: String)
    /** Explicit browser presentation bypasses app/universal-link capture. */
    fun openInBrowser(url: String)
    fun observeLifecycle(observer: HomeLifecycleObserver): HomeLifecycleSubscription
    /** Physical leading edge is chosen by shared layout direction, not the device language. */
    fun observeBack(rightEdge: Boolean, observer: HomeBackObserver): HomeLifecycleSubscription
}

/** Native dialog inputs must consult the host's same scene/root/presentation authority. */
interface NativeBackReadiness {
    fun isSharedBackActive(): Boolean
}

interface HomeLifecycleObserver {
    fun started()
    fun resumed()
    fun stopped()
}

interface HomeLifecycleSubscription { fun cancel() }

/** Native recognition drives the shared page motion; only committed() pops the one Compose stack. */
interface HomeBackObserver {
    fun enabled(): Boolean
    fun began()
    fun changed(progress: Float)
    fun cancelled()
    fun committed()
}
