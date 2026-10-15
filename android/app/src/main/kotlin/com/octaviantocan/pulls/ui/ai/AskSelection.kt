package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.core.FileLinks

/*
 * Port of `components/ask/selection.ts`, plus the bits of `critique-store.ts` and core's
 * `critique.ts` the AI review panel needs on the phone. Pure Kotlin, unit-tested.
 */

/** `AskSelection`: lines on one side of a file. `side` is `additions` or `deletions`. */
data class AskSelection(val path: String, val side: String, val startLine: Int, val endLine: Int)

/** A gutter selection as something to ask about: the range on its end side, never starting after its end. */
fun askSelectionOf(path: String, side: String, line: Int, startLine: Int?): AskSelection =
    AskSelection(path, side, if (startLine != null) minOf(startLine, line) else line, line)

private fun lineAnchor(selection: AskSelection): String =
    if (selection.startLine == selection.endLine) "L${selection.endLine}" else "L${selection.startLine}-L${selection.endLine}"

/** The lines as words, whichever end comes first: `line 12` or `lines 10–12` in a sentence, `Line 12` or `Lines 10–12` as a [label]. */
fun linesPhrase(startLine: Int, endLine: Int, label: Boolean = false): String {
    val first = minOf(startLine, endLine)
    val last = maxOf(startLine, endLine)
    val word = if (label) "Line" else "line"
    return if (first == last) "$word $last" else "${word}s $first–$last"
}

/** `path:L10` or `path:L10-L20`. */
fun lineRangeLabel(selection: AskSelection): String = "${selection.path}:${lineAnchor(selection)}"

/**
 * A GitHub link to the selected lines at the commit they were selected in: the head for
 * added and context lines, the base (under the old path) for removed ones. Null outside
 * GitHub or without that commit.
 */
fun githubBlobUrl(owner: String?, repo: String?, refKind: String, headSha: String, baseSha: String, path: String, previousPath: String?, selection: AskSelection): String? {
    if (owner == null || repo == null || refKind == "local" || refKind == "paste") return null
    val isOld = selection.side == "deletions"
    val sha = if (isOld) baseSha else headSha
    if (sha.isBlank()) return null
    val filePath = if (isOld) previousPath ?: path else path
    return FileLinks.blob(owner, repo, sha, filePath) + "#" + lineAnchor(selection)
}

/** Web `newThreadId`: unique across reloads, since the server keeps threads by id. */
private var nextThreadId = 0

fun newAskThreadId(now: Long = System.currentTimeMillis()): String = "ask-${now.toString(36)}-${(++nextThreadId).toString(36)}"

/** `critiqueVariant`: the cache variant a critique is saved under. */
fun critiqueVariant(headSha: String?, engine: String, model: String, effort: String?, lens: String?): String =
    listOf(headSha.orEmpty(), engine, model, effort.orEmpty(), lens.orEmpty()).joinToString(":")

/** Core `critiqueReviewBody`: the review body posted above a critique's inline comments. */
fun critiqueReviewBody(summary: String, engine: String, model: String?, lens: String?): String {
    val by = listOf(engine, model.orEmpty()).filter { it.isNotEmpty() }.joinToString("/")
    val through = if (!lens.isNullOrEmpty()) ", through the $lens lens" else ""
    return "$summary\n\n<sub>Reviewed with $by$through on pulls.review.</sub>"
}

val SEVERITIES = listOf("bug", "risk", "nit")
