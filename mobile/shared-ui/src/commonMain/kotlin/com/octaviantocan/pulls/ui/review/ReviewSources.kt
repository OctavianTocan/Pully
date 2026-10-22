package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.TourSource

/**
 * A tour source or a mention: a line the pull request changed opens in the diff; a file it did not touch opens in the
 * app's own viewer on a sheet, read at the head commit, never on GitHub ("In the code rows still open GitHub", recording
 * of 16 Oct 12:10, 10:01 to 10:07, N20).
 */
fun openSource(session: ReviewSession, source: TourSource, inSheet: Boolean = false) {
    // A kept tour shows before the diff: the place opens once the diff is in.
    if (session.opened == null) {
        session.waitingSource = source
        session.waitingInSheet = inSheet
        return
    }
    if (session.files.any { it.path == source.path }) {
        // From the Tour a changed file opens on a sheet, alone, and the Tour stays under it ("open in the same sort of diff
        // style but in a bottom sheet, and it's just that one file", recording 15 Oct 15:37, 14:53 to 15:16; L28).
        if (inSheet) session.sourceSheet = Focus(source.path, source.line) else session.focus = Focus(source.path, source.line)
        return
    }
    session.sourceSheet = Focus(source.path, source.line)
}
