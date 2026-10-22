package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.SheetPlatform
import com.octaviantocan.pulls.ui.design.bleed

/**
 * One changed file's diff on a sheet, opened from a place the Tour names: the same rows, colours and threads as the Diff,
 * scrolled to the line and marking it, while the Tour stays under the sheet where it was ("I can stay on this page",
 * recording 15 Oct 15:37, 14:53 to 15:16; L28). The file's name is the sheet's title.
 */
@Composable
fun FileDiffSheet(session: ReviewSession, ui: DiffUi, place: Focus, onClose: () -> Unit, aiAnnotation: @Composable (ReviewSession, AiAnchor, String?) -> Unit = { page, anchor, highlighted -> AiAnnotationBlock(page, anchor, highlighted) }) {
    val c = LocalPullsColors.current
    val file = session.files.firstOrNull { it.path == place.path } ?: return
    val owner = ui.captureFileRead(file)
    val listState = remember(place.path, owner) { LazyListState() }
    var request by remember(place, owner) { mutableStateOf<Focus?>(place) }
    DiffFocusOn(request, ui, file, listState) { request = null }
    val dark = c.dark
    val texts = ui.lineTexts(file)
    LaunchedEffect(owner, file, texts, dark) { if (!file.isBinary) ui.highlight(file, texts, dark) }
    LaunchedEffect(owner, file) { if (file.patchOmitted) session.loadPatches(patchesAround(session.files, file.path)) }
    val height = SheetPlatform.screenHeight() * 0.72f
    val density = LocalDensity.current
    PullsSheet(onClose, file.path.substringAfterLast('/'), expandable = false) {
        Box(Modifier.fillMaxWidth().height(height).bleed(16.dp).clipToBounds().background(c.raised)) {
            LazyColumn(
                Modifier.fillMaxSize().pinchToZoomCode(session.settings, remember(listState, ui, owner, file, density) { KeepUnderFingers(listState, ui, file, density) }),
                state = listState,
            ) { fileDiffItems(session, ui, file, tail = 24.dp, aiAnnotation = aiAnnotation) }
        }
    }
}
