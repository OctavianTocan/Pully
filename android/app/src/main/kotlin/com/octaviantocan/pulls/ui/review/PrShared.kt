package com.octaviantocan.pulls.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.PullsFonts

/** The three pages of one review: the overview, one file's diff, and the AI tour. */
internal enum class PrView { Overview, Diff, Tour }

/** What the screens name the pull request by: from the overview once it loads, else from the opened diff. */
internal data class PrHead(
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

internal fun prHead(session: ReviewSession, data: PrOverviewData?): PrHead? {
    val opened = session.prOpened ?: session.opened ?: return null
    val parent = when (val target = session.target) {
        is OpenTarget.Pull -> target.owner to target.repo
        is OpenTarget.Compare -> target.owner to target.repo
        is OpenTarget.Commit -> target.owner to target.repo
        else -> null
    }
    val pr = data?.overview
    return if (pr != null) {
        PrHead(pr.title, "#${pr.number}", pr.url, pr.state, pr.baseRef, pr.headRef, pr.author?.login, pr.createdAt, parent?.first, parent?.second)
    } else {
        PrHead(
            opened.title.ifBlank { parent?.let { "${it.first}/${it.second}" } ?: "Changes" }, opened.label, opened.url, opened.state, opened.baseRef, opened.headRef,
            opened.author?.login?.takeIf { it.isNotBlank() }, null, parent?.first, parent?.second,
        )
    }
}

/** A run of files under an optional heading. */
internal data class FileSection(val label: String?, val files: List<ReviewFile>)

/**
 * The overview's file list. Files follow the groups (AI groups when the analysis exists, the rule-based
 * ones otherwise). Group headings only appear when they help: several groups and either an AI analysis
 * or a long list, so a four-file pull request stays a plain list.
 */
internal fun fileSections(session: ReviewSession, ui: DiffUi): List<FileSection> {
    val views = groupViews(session, ui)
    val ordered = views.flatMap { it.allFiles }.distinctBy { it.path }.ifEmpty { session.files }
    val headed = views.size > 1 && (session.files.size > 6 || (session.analyzeMode == "ai" && session.hasAiResult))
    if (!headed) return listOf(FileSection(null, ordered))
    val seen = HashSet<String>()
    val sections = mutableListOf<FileSection>()
    fun add(label: String, files: List<ReviewFile>) {
        val fresh = files.filter { seen.add(it.path) }
        if (fresh.isNotEmpty()) sections += FileSection(label, fresh)
    }
    views.forEach { view ->
        add(view.label.ifBlank { "Other files" }, view.files)
        view.children.forEach { child -> add("${view.label} › ${child.label}", child.files) }
    }
    return sections
}

/** Puts [text] on the clipboard. Android 13+ shows its own confirmation; the app's toast covers older ones. */
internal fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("pulls.review", text))
}

/** A `path:line` reference pulled out of text, for the clickable cites of the summary and the tour. */
internal data class Cite(val path: String, val line: Int)

/**
 * [text] with each `[file.ext:12]` or `file.ext:12` reference turned into a tappable span. A reference
 * resolves to the first changed file whose path ends with the name given; one that matches no file is
 * left as plain text so nothing taps into a dead end.
 */
internal fun withCites(text: String, files: List<ReviewFile>, link: Color, onCite: (Cite) -> Unit): AnnotatedString {
    val pattern = Regex("""\[?([\w./-]+\.[A-Za-z0-9]+):(\d+)]?""")
    return buildAnnotatedString {
        var at = 0
        for (match in pattern.findAll(text)) {
            val name = match.groupValues[1]
            val file = files.firstOrNull { it.path == name } ?: files.firstOrNull { it.path.endsWith("/$name") } ?: continue
            append(text.substring(at, match.range.first))
            val cite = Cite(file.path, match.groupValues[2].toInt())
            appendCite("${name.substringAfterLast('/')}:${cite.line}", link) { onCite(cite) }
            at = match.range.last + 1
        }
        append(text.substring(at))
    }
}

/** A tappable `file:line` reference: Mona Sans medium in the link color, underlined. */
internal fun AnnotatedString.Builder.appendCite(label: String, link: Color, onClick: () -> Unit) {
    val style = TextLinkStyles(SpanStyle(color = link, fontFamily = PullsFonts.display, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline))
    withLink(LinkAnnotation.Clickable("cite", style) { onClick() }) { append(label) }
}

/** A bold lead-in and a sentence, the shape of the "What needs a human" rows. */
internal fun leadAndText(lead: String, text: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(lead) }
    if (text.isNotBlank()) append(": $text")
}

/** The first sentence-sized piece of a Markdown body, without the markup, for a one-line summary. */
internal fun firstLine(markdown: String, max: Int = 140): String {
    val line = markdown.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("```") }.orEmpty()
    val plain = line.replace(Regex("[`*_>#]"), "").replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1").trim()
    return if (plain.length <= max) plain else plain.take(max).trimEnd() + "…"
}
