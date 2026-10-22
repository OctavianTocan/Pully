package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.home.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeRootBackEpochTest {
    @Test
    fun aCapturedNativeBackCannotPopTheReplacementSourceBeforeRecomposition() = runTest {
        for (sourceReset in listOf(false, true)) {
            var entryCurrent = true
            val navigation = AppNavigation()
            val root = PullyRootState(backgroundScope, { "all" }, navigation) { entryCurrent }
            val opening = root.generation
            val native = RetainingNative()
            // This is the same production adapter and opening-generation closure installed by PullyRootScreen.
            val owned = SourceHomeActions(native) { root.current && root.generation == opening }
            navigation.nav.openRepo("old", "private")
            val motion = PageMotion().apply { onScreen = navigation.stack.last() }
            var returned = 0
            val observer = homeBackObserver(motion, SharedBackDispatcher(), false, { true }, { true }, navigation.nav.back) { returned++ }
            val subscription = owned.observeBack(false, observer)
            val retained = native.observer!!
            retained.began(); retained.changed(0.7f)
            if (sourceReset) root.invalidate() else {
                entryCurrent = false
                navigation.reset(Screen.Explore)
            }
            assertSame(navigation, root.navigation)
            // A new owner can already have a destination while the old composition still exists.
            navigation.stack.add(Screen.Repo("replacement", "repository"))
            val replacement = navigation.stack.toList()
            assertFalse(retained.enabled())
            retained.changed(0.9f)
            retained.committed()
            assertEquals(replacement, navigation.stack.toList())
            assertEquals(1, returned)
            subscription.cancel()
            root.close()
        }
    }

    @Test
    fun anOrdinaryCurrentGestureStillPopsTheSameSuppliedNavigation() = runTest {
        val navigation = AppNavigation()
        val root = PullyRootState(backgroundScope, { "all" }, navigation) { true }
        val opening = root.generation
        val native = RetainingNative()
        val owned = SourceHomeActions(native) { root.current && root.generation == opening }
        navigation.nav.openRepo("current", "repository")
        val motion = PageMotion().apply { onScreen = navigation.stack.last() }
        val observer = homeBackObserver(motion, SharedBackDispatcher(), false, { true }, { true }, navigation.nav.back) { error("Current committed gesture must not cancel") }
        val subscription = owned.observeBack(false, observer)
        val retained = native.observer!!
        assertTrue(retained.enabled())
        retained.began(); retained.changed(0.8f); retained.committed()
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
        assertSame(navigation, root.navigation)
        subscription.cancel()
        root.close()
    }

    private class RetainingNative : NativeHomeActions {
        var observer: HomeBackObserver? = null
        override fun copyText(text: String) = error("No system action is requested")
        override fun openOnGithub(url: String) = error("No system action is requested")
        override fun openInBrowser(url: String) = error("No system action is requested")
        override fun observeLifecycle(observer: HomeLifecycleObserver): HomeLifecycleSubscription = error("No extra lifecycle observer is registered")
        override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver): HomeLifecycleSubscription {
            this.observer = observer
            return object : HomeLifecycleSubscription { override fun cancel() { this@RetainingNative.observer = null } }
        }
    }
}
