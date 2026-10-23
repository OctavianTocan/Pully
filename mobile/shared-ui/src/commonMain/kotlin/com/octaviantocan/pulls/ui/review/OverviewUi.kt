package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Production reading-list state shared by Overview and the commits/files sheets; editor state stays in DiffUi. */
open class OverviewUi(val session: ReviewSession) {
    /** Where a long-press range of commits starts on the Commits sheet: the last commit tapped. */
    var commitAnchor: Int? = null

    /** Labels that need i18n outside composition; [DiffFocusEffect] keeps them current. */
    var uncategorizedLabel by mutableStateOf("Uncategorized")
    var uncategorizedSummary by mutableStateOf("Files not covered by the current analysis.")

    /** File-list sections the reader folded (true) or opened (false), by heading; an unset one follows [isFolded]'s default. */
    val folds = mutableStateMapOf<String, Boolean>()

    /** The Overview's long lists the reader opened past their first rows with "Show N more" (N5), by name; true once opened. */
    val shownAll = mutableStateMapOf<String, Boolean>()

}
