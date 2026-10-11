package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Owner: diff worker. Per-page UI state of the diff body (collapsed groups and files,
 * selections, drafts, highlight spans, loaded full files...).
 */
class DiffUi(val session: ReviewSession) {
    /** Items [diffBodyItems] emitted last time, so focus can scroll: index = total - bodyCount + local. */
    var bodyCount: Int = 0
}

@Composable
fun rememberDiffUi(session: ReviewSession): DiffUi = remember(session) { DiffUi(session) }

/**
 * Owner: diff worker. Ports `DiffGroup.vue`, `FileTree.vue`, `FileDiff.vue` and the review
 * threads. MUST be the last call in the page's LazyColumn (it ends with the "end of diff" footer).
 */
fun LazyListScope.diffBodyItems(session: ReviewSession, ui: DiffUi) {
    ui.bodyCount = 0
}

/** Owner: diff worker. Scrolls to `session.focus` / `session.focusGroupKey` when they change. */
@Composable
fun DiffFocusEffect(session: ReviewSession, ui: DiffUi, listState: LazyListState) {}
