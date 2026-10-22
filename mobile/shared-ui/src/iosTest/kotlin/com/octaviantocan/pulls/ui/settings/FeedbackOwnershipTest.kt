package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedbackOwnershipTest {
    @Test
    fun actualFormOwnerReplacementDropsPrivateFormStateButDismissalRetainsTheDraft() = runTest {
        val settings = AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
            override fun touched(name: String) = Unit
            override fun settingsChanged(key: String, value: String) = Unit
            override fun usageStatsEnabled(enabled: Boolean) = Unit
        }) { "en" }
        fun services(screen: String) = SourceFeedbackServices(
            Repo({ RepoConfiguration("https://feedback.example") }, RpcTransport { error("This owner-only case must not send RPC") }),
            settings, FeedbackMetadata("0.58", 75, "Apple", "iPhone", "iOS 18", ios = "18"),
            { screen }, { null }, { true },
        )
        val first = services("review")
        val second = services("home")
        val mounted = mutableStateOf(true)
        val owner = mutableStateOf<FeedbackServices>(first)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        lateinit var form: FeedbackFormState
        try {
            composition.setContent { if (mounted.value) FeedbackPageOwner(owner.value) { form = rememberFeedbackFormState(owner.value) } }
            runCurrent()
            first.ui.text = "Private draft"
            form.busy = true
            form.problem = "Private error"
            val original = form
            mounted.value = false
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(1L); runCurrent()
            assertEquals("Private draft", first.ui.text)
            mounted.value = true
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(2L); runCurrent()
            assertNotSame(original, form)
            assertEquals("Private draft", first.ui.text)
            assertEquals(false, form.busy)
            val reopened = form
            first.close() // The real source boundary clears synchronously, before the next frame.
            assertEquals("", first.ui.text)
            assertNull(first.ui.sent)
            owner.value = second
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(3L); runCurrent()
            assertNotSame(reopened, form)
            assertEquals("home", form.screen)
            assertNull(form.problem)
            assertEquals(false, form.busy)
        } finally { composition.dispose(); recomposer.close(); runner.cancel() }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
