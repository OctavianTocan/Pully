package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.codeScrollAfterZoom
import com.octaviantocan.pulls.core.rowTopAfterZoom
import com.octaviantocan.pulls.data.ReviewFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Holds the code under the fingers while a pinch lays it out at each new size (rule 93's pinch): as the pinch starts it
 * reads the row and the point of it under the fingers, and after each size it scrolls the list so that point is under
 * them again, and the code sideways so the same character is too. A row of code grows with the zoom; a note or a thread
 * keeps its size. Wrapped code has no sideways scroll to fix. Each step is reckoned from the start, so a step that comes
 * before the last layout still lands right.
 */
class KeepUnderFingers(val state: LazyListState, val ui: DiffUi, val file: ReviewFile, val density: Density) : PinchKeeper {
    private var owner: DiffFileRead? = null
    private var row = -1
    private var within = 0f
    private var grows = false
    private var before = 0
    private var at = Offset.Zero
    private var scroll: HScroll? = null
    private var offset = 0f
    private var reach = 0f
    private var cellStart = 0f

    override fun begin(at: Offset) {
        owner = ui.captureFileRead(file)
        if (owner == null) return
        this.at = at
        val info = state.layoutInfo
        before = info.beforeContentPadding
        val y = at.y - before
        val hit = info.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
        row = hit?.index ?: -1
        within = hit?.let { y - it.offset } ?: 0f
        grows = hit?.contentType == "line" || hit?.contentType == "split"
        scroll = null
        if (ui.session.settings.codeWrap || file.isBinary) return
        // In split view each half scrolls on its own and the right one starts past the 2dp between them.
        val width = info.viewportSize.width.toFloat()
        val split = effectiveSplit(ui.session.settings.layout, file, ui.widthDp)
        val right = split && at.x >= width / 2f
        val half = with(density) { 2.dp.toPx() }
        cellStart = if (right) (width - half) / 2f + half else 0f
        scroll = ui.scrolls[file.path to if (!split) 0 else if (right) 2 else 1]
        offset = scroll?.offset ?: 0f
        reach = scroll?.max ?: 0f
    }

    override fun step(from: Float, to: Float) {
        if (!ui.isCurrentFileRead(owner, file)) return
        val scale = to / from
        if (row >= 0) state.requestScrollToItem(row, -rowTopAfterZoom(at.y - before, within, scale, grows).roundToInt())
        val s = scroll ?: return
        // The widest line grows and shrinks with the text, so its reach is worked out at the new size and the scroll kept
        // inside it: zooming out at a line's far end must not leave blank space where the code was. Lines measured
        // later can still only lengthen it.
        val wide = reach + s.viewport - 16
        s.max = (wide * scale - s.viewport + 16).coerceAtLeast(0f)
        val digits = lineDigits(ui, file)
        fun codeStart(zoom: Float) = cellStart + with(density) { (gutterWidth(digits, zoom) + 2.dp).toPx() }
        s.offset = codeScrollAfterZoom(offset, codeStart(from), codeStart(to), at.x, at.x, scale).coerceIn(0f, s.max)
    }
}

/**
 * Scrolls [listState] to [focus]'s line in [file] and marks it; [onDone] takes the request once it is met. The line lands
 * near the top with a few lines above it, and the code is scrolled back to its left edge, so the line opens whole
 * ("lands at the top of the file, cut off on the left", recording of 16 Oct 12:10, 10:11 to 10:13, N21). A full file
 * that is slow to come is waited for, and a line the rows do not hold lands on the nearest one they do.
 */
@Composable
fun DiffFocusOn(focus: Focus?, ui: DiffUi, file: ReviewFile, listState: LazyListState, onDone: (Focus) -> Unit) {
    val owner = ui.captureFileRead(file)
    LaunchedEffect(focus, file, owner) {
        val read = owner ?: return@LaunchedEffect
        if (!ui.isCurrentFileRead(read, file)) return@LaunchedEffect
        val target = focus ?: return@LaunchedEffect
        if (target.path != file.path) return@LaunchedEffect
        // Whatever the code was scrolled sideways to before, the place opens at the start of its lines.
        ui.scrolls.forEach { (key, scroll) -> if (key.first == file.path) scroll.offset = 0f }
        if (target.line <= 0) {
            listState.scrollToItem(0)
            if (ui.isCurrentFileRead(read, file)) onDone(target)
            return@LaunchedEffect
        }
        ui.mark = LineMark(file.path, target.side, target.line, target.findingId)
        val anchor = "${file.path}|${target.side}|${target.line}"
        delay(48)
        if (!ui.isCurrentFileRead(read, file)) return@LaunchedEffect
        if (ui.lineKey[file.path]?.get(anchor) == null && !file.isBinary) {
            // The line sits in an unmodified gap: load the full file and open every gap.
            ui.loadFullFile(file)
            withTimeoutOrNull(FULL_FILE_WAIT_MS) { snapshotFlow { ui.fullLoading[file.path] == true }.first { !it } }
            if (!ui.isCurrentFileRead(read, file)) return@LaunchedEffect
            if (ui.fullFiles[file.path] != null) ui.expandedGaps[file.path] = null
        }
        var key: String? = null
        repeat(90) { if (key == null) { key = ui.lineKey[file.path]?.get(anchor); if (key == null) delay(16) } }
        if (!ui.isCurrentFileRead(read, file)) return@LaunchedEffect
        // Not a line the rows hold (past the end, or on a side the view does not show): the nearest one that is.
        if (key == null) key = nearestLineKey(ui.lineKey[file.path].orEmpty(), "${file.path}|${target.side}|", target.line)
        val at = key?.let { ui.indexOf[file.path]?.get(it) }
        if (at != null) listState.animateScrollToItem(at, scrollOffset = -240)
        if (ui.isCurrentFileRead(read, file)) onDone(target)
    }
    // Consuming the request changes the key above and cancels that effect, so the mark fades on its own timer.
    val mark = ui.mark
    LaunchedEffect(mark, owner) {
        if (mark == null) return@LaunchedEffect
        delay(2500)
        if (ui.isCurrentFileRead(owner, file) && ui.mark == mark) ui.mark = null
    }
}

/** How long a focus waits for a full file it needs before it settles for the rows it has. */
private const val FULL_FILE_WAIT_MS = 15_000L
