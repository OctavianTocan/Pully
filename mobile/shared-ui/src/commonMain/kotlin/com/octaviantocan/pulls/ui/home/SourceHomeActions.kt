package com.octaviantocan.pulls.ui.home

/** Retained private rows/readers cannot send old-owner input to native services after credential replacement. */
internal class SourceHomeActions(private val native: NativeHomeActions, private val current: () -> Boolean) : NativeHomeActions, NativeBackReadiness {
    override fun isSharedBackActive() = current() && (native as? NativeBackReadiness)?.isSharedBackActive() == true
    override fun copyText(text: String) { if (current()) native.copyText(text) }
    override fun openOnGithub(url: String) { if (current()) native.openOnGithub(url) }
    override fun openInBrowser(url: String) { if (current()) native.openInBrowser(url) }
    override fun observeLifecycle(observer: HomeLifecycleObserver): HomeLifecycleSubscription = native.observeLifecycle(object : HomeLifecycleObserver {
        override fun started() { if (current()) observer.started() }
        override fun resumed() { if (current()) observer.resumed() }
        override fun stopped() { if (current()) observer.stopped() }
    })
    override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver): HomeLifecycleSubscription = native.observeBack(rightEdge, object : HomeBackObserver {
        override fun enabled() = current() && observer.enabled()
        override fun began() { if (current()) observer.began() }
        override fun changed(progress: Float) { if (current()) observer.changed(progress) }
        override fun cancelled() = observer.cancelled()
        override fun committed() { if (current()) observer.committed() else observer.cancelled() }
    })
}
