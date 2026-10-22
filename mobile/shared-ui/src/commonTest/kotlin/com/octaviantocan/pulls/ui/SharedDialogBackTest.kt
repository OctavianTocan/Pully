package com.octaviantocan.pulls.ui

import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.home.homeBackObserver
import kotlin.test.*

/** Real framework dispatcher/input, including the pinned dialog click-only handler's behavior. */
class SharedDialogBackTest {
    @Test
    fun frameworkProgressClosesTheSharedSheetAtTheRootWithoutPoppingTheRoute() {
        val navigation = AppNavigation()
        val motion = PageMotion().apply { onScreen = Screen.Home }
        val shared = SharedBackDispatcher()
        var dismissals = 0
        var clicks = 0
        var sheet = true
        shared.register({ sheet }) { dismissals++; sheet = false }
        val native = homeBackObserver(motion, shared, false, { navigation.stack.size > 1 }, { true }, navigation.nav.back) {}
        assertTrue(native.enabled()) // There is no route Back, but the real modal owns Back.
        val framework = NavigationEventDispatcher()
        val input = Input()
        val clickOnly = ClickOnly { clicks++ }
        val bridge = SharedDialogNavigationHandler(shared) { true }
        framework.addInput(input)
        framework.addHandler(bridge, NavigationEventDispatcher.PRIORITY_OVERLAY)
        // Even a later default-priority registration cannot steal the modal's progress.
        framework.addHandler(clickOnly)
        try {
            input.start(); input.progress(0.8f); input.complete()
            assertEquals(1, dismissals)
            assertEquals(0, clicks)
            assertEquals(listOf(Screen.Home), navigation.stack.toList())
            assertFalse(native.enabled())
        } finally { bridge.close(); framework.dispose() }
    }

    @Test
    fun withoutTheBridgeThePinnedClickOnlyHandlerConsumesASwipeWithoutDismissing() {
        val framework = NavigationEventDispatcher()
        val input = Input()
        var dismissed = 0
        framework.addInput(input)
        framework.addHandler(ClickOnly { dismissed++ })
        try {
            input.start(); input.progress(0.8f); input.complete()
            assertEquals(0, dismissed)
            input.click()
            assertEquals(1, dismissed)
        } finally { framework.dispose() }
    }

    @Test
    fun activeEditConsumesBeforeItsSheetAndACancelledSwipeDismissesNeither() {
        val shared = SharedBackDispatcher()
        var editing = true
        var edits = 0
        var dismissals = 0
        var cancellations = 0
        shared.register({ true }) { dismissals++ }
        shared.register({ editing }, object : SharedBackGesture {
            override fun began() {}
            override fun changed(progress: Float) {}
            override fun cancelled() { cancellations++ }
        }) { editing = false; edits++ }
        val framework = NavigationEventDispatcher()
        val input = Input()
        val bridge = SharedDialogNavigationHandler(shared) { true }
        framework.addInput(input); framework.addHandler(bridge, NavigationEventDispatcher.PRIORITY_OVERLAY)
        try {
            input.start(); input.progress(0.3f); input.cancel()
            assertTrue(editing)
            assertEquals(1, cancellations)
            assertEquals(0, dismissals)
            input.start(); input.progress(0.8f); input.complete()
            assertFalse(editing)
            assertEquals(1, edits)
            assertEquals(0, dismissals)
            input.start(); input.progress(0.8f); input.complete()
            assertEquals(1, dismissals)
        } finally { bridge.close(); framework.dispose() }
    }

    @Test
    fun removingOrCoveringTheCapturedConsumerCannotDismissItsReplacement() {
        for (cover in listOf(false, true)) {
            val shared = SharedBackDispatcher()
            var old = 0
            var replacement = 0
            val previous = shared.register({ true }) { old++ }
            val framework = NavigationEventDispatcher()
            val input = Input()
            val bridge = SharedDialogNavigationHandler(shared) { true }
            framework.addInput(input); framework.addHandler(bridge, NavigationEventDispatcher.PRIORITY_OVERLAY)
            try {
                input.start(); input.progress(0.4f)
                if (!cover) shared.remove(previous)
                shared.register({ true }) { replacement++ }
                input.progress(0.9f); input.complete()
                assertEquals(0, old)
                assertEquals(0, replacement)
                input.click()
                assertEquals(1, replacement)
            } finally { bridge.close(); framework.dispose() }
        }
    }

    @Test
    fun disposedBridgeCannotCompleteItsOldSheetAndReleasesCapturedProgress() {
        val shared = SharedBackDispatcher()
        var dismissals = 0
        var cancelled = 0
        shared.register({ true }, object : SharedBackGesture {
            override fun began() {}
            override fun changed(progress: Float) {}
            override fun cancelled() { cancelled++ }
        }) { dismissals++ }
        val framework = NavigationEventDispatcher()
        val input = Input()
        val bridge = SharedDialogNavigationHandler(shared) { true }
        framework.addInput(input); framework.addHandler(bridge, NavigationEventDispatcher.PRIORITY_OVERLAY)
        try {
            input.start(); input.progress(0.5f)
            bridge.close(); bridge.remove()
            input.complete()
            assertEquals(0, dismissals)
            assertEquals(1, cancelled)
        } finally { framework.dispose() }
    }

    @Test
    fun frameworkInputSerializesOverlappingModalInputsToOneCapturedOwner() {
        val shared = SharedBackDispatcher()
        var dismissed = 0
        shared.register({ true }) { dismissed++ }
        val framework = NavigationEventDispatcher()
        val first = Input()
        val other = Input()
        val bridge = SharedDialogNavigationHandler(shared) { true }
        framework.addInput(first); framework.addInput(other)
        framework.addHandler(bridge, NavigationEventDispatcher.PRIORITY_OVERLAY)
        try {
            first.start(); other.start()
            other.progress(0.8f); other.complete()
            assertEquals(0, dismissed)
            first.progress(0.8f); first.complete()
            assertEquals(1, dismissed)
        } finally { bridge.close(); framework.dispose() }
    }

    @Test
    fun nativePresentationOrSceneRevocationCannotResumeTheOldGestureWhenAuthorityReturns() {
        val shared = SharedBackDispatcher()
        var dismissals = 0
        var cancellations = 0
        var eligible = true
        shared.register({ true }, object : SharedBackGesture {
            override fun began() {}
            override fun changed(progress: Float) {}
            override fun cancelled() { cancellations++ }
        }) { dismissals++ }
        val framework = NavigationEventDispatcher()
        val input = Input()
        val bridge = SharedDialogNavigationHandler(shared) { eligible }
        framework.addInput(input); framework.addHandler(bridge, NavigationEventDispatcher.PRIORITY_OVERLAY)
        try {
            input.start(); input.progress(0.3f)
            eligible = false
            input.progress(0.5f)
            eligible = true
            input.complete()
            assertEquals(0, dismissals)
            assertEquals(1, cancellations)
            eligible = false
            input.start()
            eligible = true
            input.complete()
            assertEquals(0, dismissals)
            input.click()
            assertEquals(1, dismissals)
        } finally { bridge.close(); framework.dispose() }
    }

    private class Input : NavigationEventInput() {
        fun start() = dispatchOnBackStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT))
        fun progress(value: Float) = dispatchOnBackProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = value))
        fun complete() = dispatchOnBackCompleted()
        fun cancel() = dispatchOnBackCancelled()
        fun click() = dispatchOnBackCompleted()
    }

    /** Exact behavior documented in CMP1.12.2 OnBackClickEventHandler; no actual UI success claim. */
    private class ClickOnly(private val dismiss: () -> Unit) :
        NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, isBackEnabled = true) {
        private var progressed = false
        override fun onBackProgressed(event: NavigationEvent) { progressed = true }
        override fun onBackCompleted() { if (!progressed) dismiss(); progressed = false }
        override fun onBackCancelled() { progressed = false }
    }
}
