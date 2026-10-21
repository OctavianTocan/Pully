package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.FileGroup
import com.octaviantocan.pulls.ui.design.PullsFonts

/** The page's header facts: from the overview when it is there, else from the diff; null while neither has arrived. */
fun prHead(session: ReviewSession, data: PrOverviewData?): PrHead? {
    val opened = session.prOpened ?: session.opened
    val parent = when (val target = session.target) {
        is OpenTarget.Pull -> target.owner to target.repo
        is OpenTarget.Compare -> target.owner to target.repo
        is OpenTarget.Commit -> target.owner to target.repo
        else -> null
    }
    val pr = data?.overview
    return if (pr != null) {
        PrHead(pr.title, "#${pr.number}", pr.url, pr.state, pr.baseRef, pr.headRef, pr.author?.login, pr.createdAt, parent?.first, parent?.second)
    } else if (opened != null) {
        PrHead(
            opened.title.ifBlank { parent?.let { "${it.first}/${it.second}" } ?: "Changes" }, opened.label, opened.url, normalizedPrState(opened.state), opened.baseRef, opened.headRef,
            opened.author?.login?.takeIf { it.isNotBlank() }, null, parent?.first, parent?.second,
        )
    } else {
        null
    }
}

/**
 * The overview's file list. Files follow the groups (AI groups when the analysis exists, the rule-based
 * ones otherwise). Group headings only appear when they help: several groups and either an AI analysis
 * or a long list, so a four-file pull request stays a plain list. Past [FOLD_ABOVE] files even one group
 * has its heading, so the list can start folded (rule 101).
 */
fun fileSections(session: ReviewSession, ui: OverviewUi): List<FileSection> {
    val views = groupViews(session, ui)
    val ordered = views.flatMap { it.allFiles }.distinctBy { it.path }.ifEmpty { session.files }
    val headed = views.isNotEmpty() && (session.files.size > FOLD_ABOVE ||
        views.size > 1 && (session.files.size > 6 || (session.analyzeMode == "ai" && session.hasAiResult)))
    if (!headed) return listOf(FileSection(null, ordered))
    val seen = HashSet<String>()
    val sections = mutableListOf<FileSection>()
    fun add(label: String, files: List<ReviewFile>) {
        val fresh = files.filter { seen.add(it.path) }
        if (fresh.isNotEmpty()) sections += FileSection(label, fresh)
    }
    views.forEach { view ->
        add(view.label.ifBlank { "Other files" }, view.files)
        view.children.forEach { child -> add("${view.label}$SECTION_JOIN${child.label}", child.files) }
    }
    return sharePrefixes(sections)
}

/** Is the section headed [label] folded? Only what the reader chose, or the default for a pull request of [total] files. */
fun OverviewUi.isFolded(label: String, total: Int): Boolean = folds[label] ?: (total > FOLD_ABOVE)

/** A tappable `file:line` reference: Mona Sans medium in the link color, with no underline (rule 96). */
fun AnnotatedString.Builder.appendCite(label: String, link: Color, onClick: () -> Unit) {
    val style = TextLinkStyles(SpanStyle(color = link, fontFamily = PullsFonts.display, fontWeight = FontWeight.Medium))
    withLink(LinkAnnotation.Clickable("cite", style) { onClick() }) { append(label) }
}

/** A bold lead-in and a sentence, the shape of the "What needs a human" rows. */
fun leadAndText(lead: String, text: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(lead) }
    if (text.isNotBlank()) append(": $text")
}


/** `session.groups` as views with resolved files, missing paths and the uncategorized label. */
fun groupViews(session: ReviewSession, ui: OverviewUi): List<GroupView> {
    val byPath = session.files.associateBy { it.path }
    val byPrevious = session.files.filter { it.previousPath != null }.associateBy { it.previousPath!! }
    // `resolveGroups` drops unknown paths; the raw groups still name them, shown as removed.
    val raw = HashMap<String, List<String>>()
    fun index(groups: List<FileGroup>) { groups.forEach { raw.getOrPut(it.key) { it.paths }; index(it.children) } }
    index(if (session.analyzeMode == "ai" && session.hasAiResult) session.saved?.aiGroups.orEmpty() else session.opened?.groups.orEmpty())
    fun view(group: FileGroup): GroupView {
        val uncategorized = group.key == "uncategorized"
        return GroupView(
            group,
            if (uncategorized) ui.uncategorizedLabel else group.label,
            if (uncategorized) ui.uncategorizedSummary else group.summary,
            group.paths.mapNotNull { byPath[it] },
            raw[group.key].orEmpty().filter { byPath[it] == null && byPrevious[it] == null },
            group.children.map(::view),
        )
    }
    return session.groups.map(::view)
}
