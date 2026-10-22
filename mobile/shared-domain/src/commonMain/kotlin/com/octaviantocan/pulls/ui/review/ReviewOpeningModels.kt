package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.Tour

/** The three pages of one review: the overview, one file's diff, and the AI tour. */
enum class PrView { Overview, Diff, Tour }

/** What the screens name the pull request by: from the overview once it loads, else from the opened diff. */
data class PrHead(
    val title: String,
    /** `#241`, or whatever short id the source has. */
    val label: String,
    val url: String,
    /** `open`, `draft`, `closed`, `merged`, or empty for a compare or commit. */
    val state: String,
    val base: String,
    val head: String,
    val author: String?,
    val createdAt: String?,
    val owner: String?,
    val repo: String?,
)

/** Approving or requesting changes needs a known open pull request. */
val PrHead.isOpen: Boolean get() = state == "open" || state == "draft"

/**
 * The header facts for a tour the phone kept, before the overview or the diff has arrived: the title from the Inbox
 * or the Tours list ([preview]), else the tour's own. Null for anything but a pull request, or with no kept [tour].
 * The state is unknown until the overview comes unless the list already knew it.
 */
fun storedTourHead(target: OpenTarget, preview: PullPreview?, tour: Tour?): PrHead? {
    val pull = target as? OpenTarget.Pull ?: return null
    if (tour == null) return null
    return PrHead(
        preview?.title?.takeIf { it.isNotBlank() } ?: tour.title, "#${pull.number}", "https://github.com/${pull.owner}/${pull.repo}/pull/${pull.number}",
        preview?.state ?: "unknown", "", "", preview?.author, null, pull.owner, pull.repo,
    )
}

/**
 * A run of files under an optional heading. [label] is the whole name, which keys the section's fold; [heading] is the
 * "Prefix" it shares with the sections beside it and [name] what its own row shows once that prefix is said once over
 * them (N34). A section that shares nothing has no [heading], and [name] is its [label].
 */
data class FileSection(val label: String?, val files: List<ReviewFile>, val heading: String? = null, val name: String? = label)

/** Whether [section] needs a line of its own over it saying its [FileSection.heading]: the first of its run, unless its parent's section, just above, already says it. */
fun opensHeading(section: FileSection, before: FileSection?): Boolean =
    section.heading != null && before?.heading != section.heading && before?.label != section.heading

/** Between a group's name and its child's, as the file sections write them. */
const val SECTION_JOIN = " › "

/**
 * Says a shared "Prefix › " once (N34): where two or more sections in a row are named "Prefix › Something" with the same
 * Prefix, each gets that Prefix as its [FileSection.heading] and the rest as its [FileSection.name], so no two rows read
 * the same ("Wayland capture producer › …" four times, recording of 16 Oct 12:10, 17:41 to 18:02). Right under their
 * parent's own section, named Prefix, even one does: the parent already says the Prefix. Any other lone one keeps its
 * whole name.
 */
fun sharePrefixes(sections: List<FileSection>): List<FileSection> {
    fun prefix(section: FileSection): String? = section.label?.takeIf { SECTION_JOIN in it }?.substringBefore(SECTION_JOIN)
    val out = ArrayList<FileSection>(sections.size)
    var at = 0
    while (at < sections.size) {
        val shared = prefix(sections[at])
        var end = at + 1
        if (shared != null) while (end < sections.size && prefix(sections[end]) == shared) end++
        val run = sections.subList(at, end)
        val underParent = out.lastOrNull()?.let { it.label == shared && it.heading == null } == true
        if (shared != null && (run.size > 1 || underParent)) run.forEach { out += it.copy(heading = shared, name = it.label!!.substringAfter(SECTION_JOIN)) }
        else out += run
        at = end
    }
    return out
}

/** Above this many files a pull request's file sections start folded, so a huge one opens as a short list of headings. */
const val FOLD_ABOVE = 80

/** The sheet route for a file's actions: this, then the file's path. */
const val FILE_SHEET = "file:"

/** A place in the code a cite points at (a finding's line, a mention in the summary or the brief); line 0 is the whole file. */
data class Cite(val path: String, val line: Int)

/** The first sentence-sized piece of a Markdown body, without the markup, for a one-line summary. */
fun firstLine(markdown: String, max: Int = 140): String {
    val line = markdown.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("```") }.orEmpty()
    val plain = line.replace(Regex("[`*_>#]"), "").replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1").trim()
    return if (plain.length <= max) plain else plain.take(max).trimEnd() + "…"
}

/** Only PR number labels link out; commit, compare and posted-diff labels are not GitHub pull requests. */
fun canonicalPrUrl(label: String, owner: String?, repo: String?): String? {
    if (owner.isNullOrBlank() || repo.isNullOrBlank() || !label.startsWith('#')) return null
    val number = label.drop(1).toIntOrNull()?.takeIf { it > 0 } ?: return null
    return "https://github.com/$owner/$repo/pull/$number"
}

/** How many rows a long list shows before its [ShowMore]. */
const val FIRST_FEW = 5

/**
 * How many of [total] rows a list shows: all of them once [open], or when hiding them would leave a "Show 1 more" (one
 * more row is cheaper than the line that offers it); otherwise the first [first].
 */
fun firstFew(total: Int, open: Boolean, first: Int = FIRST_FEW): Int = if (open || total <= first + 1) total else first

/**
 * How many of the Overview's file [sections] show before "Show N more": the first few, carried on to the end of a run
 * that shares a heading (N34), so a heading never stands over only some of its groups.
 */
fun firstFewSections(sections: List<FileSection>, open: Boolean): Int {
    var cut = firstFew(sections.size, open)
    while (cut in 1 until sections.size && sections[cut].heading != null && !opensHeading(sections[cut], sections[cut - 1])) cut++
    return cut
}
