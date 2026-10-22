package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Mounts the production Tour input owner without replacing its state with a test-only model. */
@OptIn(ExperimentalCoroutinesApi::class)
class TourQuestionOwnershipTest {
    @Test
    fun typingSurvivesOrdinaryFramesButNeverAnotherSessionOrHeadAba() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var page by mutableStateOf(h.page)
        var field: TourQuestionState? = null
        var rendered = ""
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        var nanos = 0L
        suspend fun frame() {
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(++nanos); runCurrent()
        }
        fun reopen() = ReviewSession(h.page.target, h.page.repo, backgroundScope, h.page.settings,
            cache = h.cache, effects = NoSessionEffects)
        val reopened = mutableListOf<ReviewSession>()
        try {
            composition.setContent {
                val actual = rememberTourQuestionState(page, page.captureTourRead())
                field = actual
                rendered = actual.text
            }
            runCurrent(); frame()
            val first = assertNotNull(field)
            first.text = "Question about this tour"; first.focused = true
            frame()
            assertSame(first, field)
            assertEquals("Question about this tour", rendered)
            h.head = "head-b"; h.page.refresh(); runCurrent(); frame()
            assertNotSame(first, field)
            assertEquals("", rendered)
            val middle = assertNotNull(field)
            middle.text = "Different head"; frame()
            h.head = "head-a"; h.page.refresh(); runCurrent(); frame()
            assertNotSame(first, field); assertNotSame(middle, field)
            assertEquals("", rendered)
            val a = reopen().also { reopened += it }
            val b = reopen().also { reopened += it }
            assertEquals(a.captureView(), b.captureView())
            page = a; frame()
            val sameVersionOtherSession = assertNotNull(field)
            sameVersionOtherSession.text = "Private unsent draft"; frame()
            assertEquals("Private unsent draft", rendered)
            page = b; frame()
            assertNotSame(sameVersionOtherSession, field)
            assertEquals("", rendered)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel()
            h.page.detach(); reopened.forEach { it.detach() }
        }
    }
}
