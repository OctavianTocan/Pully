package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.repositoryPath
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.Mentions
import com.octaviantocan.pulls.ui.design.fileStems
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.SessionStateKey

/**
 * Mentions in AI text go to their place: a changed file opens the diff on the line, any other
 * file opens through [open] (rule 96). The resolver takes a whole path or the end of one, and a code
 * name the AI left unlinked opens the changed file named after it (N17).
 */
fun mentionsFor(session: ReviewSession, open: (CodePlace) -> Unit): Mentions =
    Mentions(resolve = { name -> resolveMention(session, name) }, open = open, repo = reviewRepo(session), names = { name -> codeNameOf(session, name) })

/** The changed files by their names without extension, built once per list of files: a mention looks a name up, never walks the files. */
private class MentionNames {
    var stemsOf: Pair<List<ReviewFile>, Map<String, String>>? = null
}
private val namesKey = SessionStateKey<MentionNames>()

/** The changed file a code name is named after (`PrTourScreen` for `ui/review/PrTourScreen.kt`), or null (see [fileStems]). */
fun codeNameOf(session: ReviewSession, name: String): String? {
    if (session.captureView() == null) return null
    val cache = session.pageState(namesKey) { MentionNames() }
    val files = session.files
    val stems = cache.stemsOf?.takeIf { it.first === files }?.second ?: fileStems(files.map { it.path }).also { cache.stemsOf = files to it }
    return stems[name]
}

/** The `owner/name` of the repository under review, or null for a local branch. */
fun reviewRepo(session: ReviewSession): String? = when (val target = session.target) {
    is OpenTarget.Pull -> "${target.owner}/${target.repo}"
    is OpenTarget.Compare -> "${target.owner}/${target.repo}"
    is OpenTarget.Commit -> "${target.owner}/${target.repo}"
    else -> null
}

/** The changed file a mention names, by its whole path or the end of it, or null. A `./` or `../` in front is read from the root. */
fun resolveMention(session: ReviewSession, name: String): String? {
    val path = repositoryPath(name) ?: return null
    return session.files.firstOrNull { it.path == path }?.path ?: session.files.firstOrNull { it.path.endsWith("/$path") }?.path
}
