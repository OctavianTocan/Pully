package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.CritiqueFinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class FindingFocusActionTest {
    @Test
    fun theActualFindingClickRejectsHeadAbaAndRevokedOrDisposedReading() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var canRead = true
        val finding = focusFixture()
        val action = FindingFocusAction(h.page, finding) { canRead }
        action.open()
        val focus = assertNotNull(h.page.focus)
        assertEquals(finding.path, focus.path)
        assertEquals(finding.line, focus.line)
        assertEquals(finding.side, focus.side)
        assertEquals(finding.id, focus.findingId)
        h.page.focus = null
        canRead = false; action.open(); assertNull(h.page.focus)
        canRead = true
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        action.open(); assertNull(h.page.focus)
        val current = FindingFocusAction(h.page, finding) { true }
        current.close(); current.open(); assertNull(h.page.focus)
        h.page.detach()
    }
}

internal fun focusFixture() = CritiqueFinding("finding-1", "src/file.kt", "additions", 7, null, "warning", "Check this branch", "A saved finding", null)
