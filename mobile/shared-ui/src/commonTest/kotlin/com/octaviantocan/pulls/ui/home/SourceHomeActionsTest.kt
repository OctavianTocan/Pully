package com.octaviantocan.pulls.ui.home

import kotlin.test.*

class SourceHomeActionsTest {
    @Test
    fun retainedPrivateReaderActionsCannotReachNativeServicesAfterSourceRevocation() {
        var current = true
        val calls = mutableListOf<String>()
        var lifecycle: HomeLifecycleObserver? = null
        val native = object : NativeHomeActions {
            override fun copyText(text: String) { calls += "copy:$text" }
            override fun openOnGithub(url: String) { calls += "github:$url" }
            override fun openInBrowser(url: String) { calls += "browser:$url" }
            override fun observeLifecycle(observer: HomeLifecycleObserver): HomeLifecycleSubscription {
                lifecycle = observer
                observer.started()
                return object : HomeLifecycleSubscription { override fun cancel() { calls += "unsubscribe" } }
            }
            override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver): HomeLifecycleSubscription = error("Only the root installs the native gesture")
        }
        val source = SourceHomeActions(native) { current }
        var starts = 0
        val subscription = source.observeLifecycle(object : HomeLifecycleObserver {
            override fun started() { starts++ }
            override fun resumed() = Unit
            override fun stopped() = Unit
        })
        assertEquals(1, starts)
        source.copyText("private path")
        source.openOnGithub("https://github.com/owner/repo/pull/1")
        source.openInBrowser("https://github.com/owner/repo/pull/1")
        assertEquals(3, calls.size)
        current = false
        source.copyText("retired secret")
        source.openOnGithub("https://github.com/retired/repo/pull/1")
        source.openInBrowser("https://github.com/retired/repo/pull/1")
        lifecycle!!.started()
        assertEquals(1, starts)
        assertEquals(3, calls.size)
        subscription.cancel()
        assertEquals("unsubscribe", calls.last())
    }
}
