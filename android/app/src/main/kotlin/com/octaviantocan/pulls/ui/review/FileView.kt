package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.DiffLine
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.bleed

/**
 * A file the pull request does not change, on the same sheet a changed file opens on: read at the head commit through
 * the same `file-content` path as "Load the whole file", drawn in the diff's code view as unchanged lines, scrolled to the
 * line and marking it. A link in AI text never leaves the app for GitHub ("In the code rows still open GitHub", recording
 * of 16 Oct 12:10, 10:01 to 10:07, N20). A file that does not come says why, with the arrow to try again.
 */
@Composable
internal fun FileViewSheet(session: ReviewSession, ui: DiffUi, place: Focus, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    var attempt by remember(place.path) { mutableIntStateOf(0) }
    var text by remember(place.path) { mutableStateOf<String?>(null) }
    var error by remember(place.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(place.path, attempt) {
        val key = session.key ?: return@LaunchedEffect
        error = null
        runCatching { session.repo.fullFile(key, place.path) }
            .onSuccess { full -> if (full.new != null) text = full.new else error = "This file is not in the repository at this commit." }
            .onFailure { error = it.message ?: it.toString() }
    }
    val file = remember(place.path, text) { text?.let { unchangedFile(place.path, it) } }
    val height = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    PullsSheet(onClose, place.path.substringAfterLast('/'), expandable = false) {
        Box(Modifier.fillMaxWidth().height(height).bleed(16.dp).clipToBounds().background(c.raised)) {
            when {
                file != null -> FileViewList(session, ui, file, place)
                error != null -> Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Couldn't load ${place.path.substringAfterLast('/')}", style = PullsType.row, color = c.text)
                    Text(error.orEmpty(), style = PullsType.sm, color = c.muted)
                    FloatIcon(Lu.redo, "Load it again", { attempt++ }, flat = true, iconSize = 22.dp, tint = c.muted)
                }
                else -> RowsSkeleton(Modifier.padding(16.dp), rows = 8)
            }
        }
    }
}

/** The file's rows, coloured by the diff's highlighter and scrolled to [place]'s line (the last one, past the end). */
@Composable
private fun FileViewList(session: ReviewSession, ui: DiffUi, file: ReviewFile, place: Focus) {
    val c = LocalPullsColors.current
    val listState = remember(file.path) { LazyListState() }
    val last = file.patchLines.size
    var request by remember(place) { mutableStateOf<Focus?>(place.copy(line = place.line.coerceAtMost(last), side = "additions")) }
    DiffFocusOn(request, ui, file, listState) { request = null }
    val dark = c.dark
    val texts = ui.lineTexts(file)
    LaunchedEffect(file.path, texts, dark) { ui.highlight(file, texts, dark) }
    LazyColumn(Modifier.fillMaxSize().pinchToZoomCode(session.settings), state = listState) {
        fileDiffItems(session, ui, file, tail = 24.dp)
    }
}

/** A file the change does not touch, as the diff's model holds one: every line unchanged, numbered the same on both sides. */
internal fun unchangedFile(path: String, text: String): ReviewFile {
    val lines = text.removeSuffix("\n").split('\n')
    return ReviewFile(
        path = path, status = "unchanged", additions = 0, deletions = 0,
        patchLines = lines.mapIndexed { i, line -> DiffLine(' ', line.removeSuffix("\r"), newLine = i + 1, oldLine = i + 1) },
    )
}
