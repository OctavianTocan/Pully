package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class DiffSelectionTest {
    @Test
    fun actualRowHandlersToggleAndExtendSelectionsAndKeepTheContainingLongPressRange() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val row = DiffLineSelection(ui, file, ui.captureFileRead(file), "additions", 7)
        row.tap()
        assertEquals(DraftTarget(file.path, "additions", 7), ui.drafts[file.path])
        row.tap()
        assertNull(ui.drafts[file.path])
        ui.selectionStart[file.path] = DraftTarget(file.path, "additions", 3)
        row.tap()
        assertEquals(3..7, ui.drafts.getValue(file.path).range)
        row.longPress()
        assertEquals(3..7, ui.lineActions?.range)
        assertSame(ui.lineActions, ui.drafts[file.path])
        h.page.detach()
    }

    @Test
    fun equalFilesAtTheSameHeadReceiveNewStableDrawnFileTokens() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val ui = DiffUi(h.page)
        val first = h.page.files.single()
        val read = assertNotNull(ui.captureFileRead(first))
        assertSame(read, ui.captureFileRead(first))
        val view = h.page.captureView()
        h.changeOnlyReviewTitle()
        h.page.refresh(); runCurrent()
        val second = h.page.files.single()
        assertEquals(first, second)
        assertNotSame(first, second)
        assertEquals(view, h.page.captureView())
        val next = assertNotNull(ui.captureFileRead(second))
        assertNotSame(read, next)
        assertSame(next, ui.captureFileRead(second))
        assertFalse(ui.isCurrentFileRead(read))
        assertNull(ui.captureFileRead(first))
        h.page.detach()
    }

    @Test
    fun actualGutterDragSelectsItsRangeAndCancellationLeavesNoDraft() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val owner = ui.captureFileRead(file)
        val row = DiffLineSelection(ui, file, owner, "additions", 7)
        val previous = DiffLineSelection(ui, file, owner, "additions", 4)
        row.position(20f); previous.position(0f)
        row.beginDrag(); row.dragBy(-20f); row.endDrag()
        assertEquals(4..7, ui.drafts.getValue(file.path).range)
        row.beginDrag(); row.cancelDrag()
        assertNull(ui.dragSel)
        assertNull(ui.drafts[file.path])
        h.page.detach()
    }

    @Test
    fun retiredCallbacksCannotChangeTheNewHeadsDraftActionsGutterOrActiveDragAfterAba() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val oldFile = h.page.files.single()
        val ui = DiffUi(h.page)
        val old = DiffLineSelection(ui, oldFile, ui.captureFileRead(oldFile), "additions", 7)
        old.position(70f); old.beginDrag()
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        val file = h.page.files.single()
        val current = DiffLineSelection(ui, file, ui.captureFileRead(file), "additions", 3)
        assertNull(ui.captureFileRead(oldFile))
        current.position(30f); current.longPress(); current.beginDrag()
        val drag = ui.dragSel
        val actions = ui.lineActions
        val gutter = ui.gutterY.toMap()
        old.tap(); old.longPress(); old.position(900f); old.beginDrag()
        assertFalse(old.dragBy(100f))
        old.endDrag(); old.cancelDrag()
        assertSame(drag, ui.dragSel)
        assertSame(actions, ui.lineActions)
        assertEquals(gutter, ui.gutterY)
        assertNull(ui.drafts[file.path])
        current.endDrag()
        val draft = ui.drafts[file.path]
        old.tap(); old.longPress(); old.beginDrag(); old.cancelDrag()
        assertSame(draft, ui.drafts[file.path])
        h.page.detach()
    }
}

internal fun DiffHarness.changeOnlyReviewTitle() {
    handler = { name, args ->
        if (name == "github-pr") {
            val old = default(name, args).jsonObject
            val diff = old.getValue("diff").jsonObject
            val updated = buildJsonObject { diff.forEach { (key, value) -> put(key, value) }; put("title", "A metadata-only title update") }
            buildJsonObject { old.forEach { (key, value) -> put(key, value) }; put("diff", updated) }
        } else default(name, args)
    }
}

/** Mounts the same remember helper as LineCell on the actual Native Compose runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.assertRowHandlersRefreshForAnEqualFileWithoutChangingTheirHead() {
    val h = DiffHarness(backgroundScope)
    h.page.load(); runCurrent()
    val ui = DiffUi(h.page)
    var handler: DiffLineSelection? = null
    val clock = BroadcastFrameClock()
    val recomposer = Recomposer(coroutineContext + clock)
    val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    val composition = Composition(NoNodes(), recomposer)
    suspend fun frame(nanos: Long) {
        Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(nanos); runCurrent()
    }
    try {
        composition.setContent { handler = rememberDiffLineSelection(ui, h.page.files.single(), "additions", 7) }
        runCurrent(); frame(1L)
        val first = assertNotNull(handler)
        frame(2L); assertSame(first, handler)
        h.changeOnlyReviewTitle(); h.page.refresh(); runCurrent()
        frame(3L); frame(4L)
        val next = assertNotNull(handler)
        assertNotSame(first, next)
        first.tap(); assertNull(ui.drafts[h.page.files.single().path])
        next.tap(); assertEquals(7, ui.drafts[h.page.files.single().path]?.line)
        frame(5L); assertSame(next, handler)
    } finally {
        composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
    }
}
