package com.octaviantocan.pulls.core

/**
 * The thing on a pull request's GitHub page that a link points at, read from GitHub's own tabs and `#` anchors, so Pully
 * lands on that thing and not just the pull request ("It needs to take us to the thing. Seamless parity with GitHub":
 * Tavi, 20 Oct). A commit or compare link can carry a [File] too.
 */
sealed interface LinkPlace {
    /** `#issuecomment-<id>`: a comment on the conversation. */
    data class Comment(val id: Long) : LinkPlace

    /** `#pullrequestreview-<id>`: a review on the conversation. */
    data class Review(val id: Long) : LinkPlace

    /** `#discussion_r<id>`, or `#r<id>` on the files tab: one comment of a review thread on the code. */
    data class CodeComment(val id: Long) : LinkPlace

    /** `#issue-<id>`: the description. */
    data object Description : LinkPlace

    /** Any other place on the conversation: an event, a reference, a label. */
    data object Conversation : LinkPlace

    /**
     * The files tab (`/files`, `/changes`): one file when [file] is GitHub's `diff-<sha256 of the path>` anchor, one line
     * of it when [line] is set ([left] for the old side, `L12`; the new side is `R12`), and only some commits when
     * [commits] is `sha` or `from..to`.
     */
    data class Files(val file: String? = null, val line: Int? = null, val left: Boolean = false, val commits: String? = null) : LinkPlace

    /** The commits tab, or one commit of it (`/commits/<sha>`, `#commits-pushed-<sha>`), with a file of it when [file] is set. */
    data class Commits(val sha: String? = null, val file: Files? = null) : LinkPlace

    /** The checks tab, on one run when [runId] is set (`?check_run_id=`). */
    data class Checks(val runId: Long? = null) : LinkPlace
}

object LinkPlaces {
    private val pullPath = Regex("""^/[^/]+/[^/]+/pull/\d+(/.*)?$""")
    private val diffAnchor = Regex("""^diff-([0-9a-fA-F]{64})(?:([LR])(\d+))?(?:-[LR]\d+)?$""")
    private val sha = Regex("""^[0-9a-fA-F]{7,64}$""")
    private val range = Regex("""^([0-9a-fA-F]{7,64})\.\.\.?([0-9a-fA-F]{7,64})$""")

    /**
     * Where on a GitHub pull request, commit or compare page [url] points, or null for the page itself. Only github.com
     * links are read; the path, query and anchor are taken as GitHub gives them.
     */
    fun parse(url: String): LinkPlace? {
        val parts = split(url) ?: return null
        val (path, query, anchor) = parts
        if (!pullPath.matches(path)) return fileOf(anchor)
        val tab = path.split('/').drop(5).filter { it.isNotEmpty() }
        return when (tab.firstOrNull()) {
            null -> conversationPlace(anchor)
            "files", "changes" -> {
                val commits = tab.getOrNull(1)?.takeIf { sha.matches(it) || range.matches(it) }
                codeComment(anchor) ?: (fileOf(anchor) ?: LinkPlace.Files()).copy(commits = commits)
            }
            "commits" -> {
                val one = tab.getOrNull(1)?.takeIf { sha.matches(it) }
                if (one != null) LinkPlace.Commits(one, fileOf(anchor)) else LinkPlace.Commits()
            }
            "checks" -> LinkPlace.Checks(queryValue(query, "check_run_id")?.toLongOrNull())
            else -> conversationPlace(anchor)
        }
    }

    /** GitHub's anchor for a file in a diff: `diff-` and the SHA-256 of its path, in hex. */
    fun fileAnchor(path: String): String =
        sha256Utf8(path)

    /** Of [paths], the one GitHub's [anchor] (the 64 hex characters after `diff-`) names, or null. */
    fun fileFor(anchor: String, paths: Iterable<String>): String? = paths.firstOrNull { fileAnchor(it) == anchor.lowercase() }

    /** `from..to` or one sha: the first and last commit a files-tab link narrows to. */
    fun commitRange(commits: String): Pair<String, String> =
        range.matchEntire(commits)?.let { it.groupValues[1] to it.groupValues[2] } ?: (commits to commits)

    private fun conversationPlace(anchor: String?): LinkPlace? {
        if (anchor.isNullOrEmpty()) return null
        number(anchor, "issuecomment-")?.let { return LinkPlace.Comment(it) }
        number(anchor, "pullrequestreview-")?.let { return LinkPlace.Review(it) }
        codeComment(anchor)?.let { return it }
        if (anchor.startsWith("commits-pushed-")) return LinkPlace.Commits(anchor.removePrefix("commits-pushed-").takeIf { sha.matches(it) })
        if (number(anchor, "issue-") != null) return LinkPlace.Description
        fileOf(anchor)?.let { return it }
        return LinkPlace.Conversation
    }

    private fun codeComment(anchor: String?): LinkPlace.CodeComment? {
        if (anchor == null) return null
        return (number(anchor, "discussion_r") ?: number(anchor, "r"))?.let { LinkPlace.CodeComment(it) }
    }

    private fun fileOf(anchor: String?): LinkPlace.Files? {
        val match = anchor?.let(diffAnchor::matchEntire) ?: return null
        val (file, side, line) = match.destructured
        return LinkPlace.Files(file.lowercase(), line.toIntOrNull(), left = side == "L")
    }

    private fun number(anchor: String, prefix: String): Long? =
        anchor.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()

    private fun queryValue(query: String?, key: String): String? =
        query?.split('&')?.firstNotNullOfOrNull { pair -> pair.split('=', limit = 2).takeIf { it[0] == key }?.getOrNull(1) }

    /** A github.com URL's path (no trailing slash), query and anchor; null for any other host. */
    private fun split(url: String): Triple<String, String?, String?>? {
        val rest = Regex("""^(?:https?://)?(?:www\.)?github\.com(/[^\s]*)$""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.get(1) ?: return null
        val anchor = rest.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val beforeAnchor = rest.substringBefore('#')
        val query = beforeAnchor.substringAfter('?', "").takeIf { it.isNotEmpty() }
        val path = beforeAnchor.substringBefore('?').trimEnd('/')
        return Triple(path, query, anchor)
    }
}
