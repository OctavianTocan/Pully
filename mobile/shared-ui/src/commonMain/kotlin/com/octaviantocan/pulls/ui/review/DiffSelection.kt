package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.ReviewFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The actual row handlers retain the file and view which issued their pointer/click callbacks. */
internal class DiffLineSelection(
    private val ui: DiffUi,
    private val file: ReviewFile,
    private val owner: DiffFileRead?,
    private val side: String,
    private val number: Int?,
) {
    private var y = 0f
    private var dragging: DraftTarget? = null
    private fun current(): Boolean = !file.isBinary && number != null && ui.isCurrentFileRead(owner, file)

    fun position(y: Float) {
        if (current()) ui.gutterY["${file.path}|$side|$number"] = y
    }

    /** A tap selects one line, toggles it off, or finishes the existing range. */
    fun tap() {
        if (!current()) return
        val n = number ?: return
        val start = ui.selectionStart[file.path]
        if (start != null && start.side == side) {
            ui.selectionStart.remove(file.path)
            ui.drafts[file.path] = selectRange(file.path, side, start.line, n)
            return
        }
        val draft = ui.drafts[file.path]
        if (draft != null && draft.side == side && draft.line == n && draft.startLine == null) {
            ui.drafts.remove(file.path)
            return
        }
        ui.mark = null
        ui.drafts[file.path] = DraftTarget(file.path, side, n)
    }

    /** Holding code keeps a containing selection and opens Ask, Copy and Copy GitHub link. */
    fun longPress() {
        if (!current()) return
        val n = number ?: return
        ui.selectionStart.remove(file.path)
        val draft = ui.drafts[file.path]?.takeIf { it.side == side && n in it.range } ?: DraftTarget(file.path, side, n)
        ui.mark = null
        ui.drafts[file.path] = draft
        ui.lineActions = draft
    }

    fun beginDrag() {
        if (!current()) return
        y = ui.gutterY["${file.path}|$side|$number"] ?: 0f
        ui.drafts.remove(file.path)
        dragging = DraftTarget(file.path, side, number ?: return)
        ui.dragSel = dragging
    }

    fun dragBy(amount: Float): Boolean {
        if (!current() || dragging == null || ui.dragSel !== dragging) return false
        y += amount
        val to = ui.lineAt(file.path, side, y) ?: return true
        dragging = selectRange(file.path, side, number ?: return false, to)
        ui.dragSel = dragging
        return true
    }

    fun endDrag() {
        if (!current() || dragging == null || ui.dragSel !== dragging) return
        ui.drafts[file.path] = dragging ?: return
        ui.dragSel = null
        dragging = null
    }

    fun cancelDrag() {
        if (!current() || dragging == null || ui.dragSel !== dragging) return
        ui.dragSel = null
        dragging = null
    }
}

/** The issued file token changes for an equal replacement object while staying stable during a gesture. */
@Composable
internal fun rememberDiffLineSelection(ui: DiffUi, file: ReviewFile, side: String, number: Int?): DiffLineSelection {
    val owner = ui.captureFileRead(file)
    return remember(ui, owner, side, number) { DiffLineSelection(ui, file, owner, side, number) }
}
