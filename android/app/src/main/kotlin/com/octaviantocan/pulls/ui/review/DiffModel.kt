package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.AskThread
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.data.DiffLine
import com.octaviantocan.pulls.data.FileNote
import com.octaviantocan.pulls.data.FullFile
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.ReviewThread
import com.octaviantocan.pulls.ui.design.ReviewState
import java.text.Collator
import java.util.Locale

/*
 * Pure diff-body logic, ported from the web and kept free of Compose so it is unit-tested:
 * gaps and full-file expansion (pierre's "N unmodified lines"), split pairing, the
 * per-group file tree (`FileTree.vue`), `noisy-files.ts`, and where threads, AI notes,
 * findings and ask threads sit in a file's diff (`FileDiff.vue`, `file-annotations.ts`).
 */

// --- Diff entries: lines and "N unmodified lines" gaps ---

/** One row of a file's unified diff: a line, or a run of unmodified lines not shown. */
sealed interface DiffEntry {
    data class Line(val line: DiffLine) : DiffEntry

    /**
     * [id] is the index of the hunk the gap precedes ([ReviewFile.hunks] size for the
     * trailing gap, which is only known once the full file is loaded).
     */
    data class Gap(val id: Int, val count: Int) : DiffEntry
}

/**
 * The rows of [file]'s diff. Without [full] the gaps come from the patch
 * ([DiffLine.gapBefore]). With [full], gaps whose id is in [expanded] (every gap when
 * [expanded] is null) are filled with the file's unmodified lines, and the trailing gap
 * after the last hunk appears. A file with no patch lines but a full file (a diff too large
 * to send) is diffed here line by line.
 */
fun diffEntries(file: ReviewFile, full: FullFile? = null, expanded: Set<Int>? = emptySet()): List<DiffEntry> {
    val lines = file.patchLines
    if (full == null) {
        return buildList {
            lines.forEach { line ->
                if (line.gapBefore > 0) add(DiffEntry.Gap(line.hunk, line.gapBefore))
                add(DiffEntry.Line(line.copy(gapBefore = 0)))
            }
        }
    }
    val oldText = full.old?.let(::splitFileLines)
    val newText = full.new?.let(::splitFileLines)
    if (lines.isEmpty()) return wholeFileDiff(oldText, newText).map { DiffEntry.Line(it) }
    if (file.hunks.isEmpty()) return diffEntries(file, null)
    val result = mutableListOf<DiffEntry>()
    var old = 1
    var new = 1
    fun fill(id: Int, untilOld: Int?, untilNew: Int?) {
        // Unmodified lines run until the next hunk starts on either side (or to the end of the file).
        val count = when {
            untilNew != null && newText != null -> untilNew - new
            untilOld != null && oldText != null -> untilOld - old
            untilNew != null -> untilNew - new
            untilOld != null -> untilOld - old
            else -> (newText?.size?.let { it - new + 1 }) ?: (oldText?.size?.let { it - old + 1 }) ?: 0
        }
        if (count <= 0) return
        if (expanded == null || id in expanded) {
            repeat(count) {
                val text = if (newText != null) newText.getOrNull(new - 1) else oldText?.getOrNull(old - 1)
                result += DiffEntry.Line(DiffLine(' ', text.orEmpty(), newLine = new, oldLine = old, hunk = id.coerceAtMost(file.hunks.lastIndex.coerceAtLeast(0))))
                old += 1; new += 1
            }
        } else {
            result += DiffEntry.Gap(id, count)
            old += count; new += count
        }
    }
    val byHunk = lines.groupBy { it.hunk }
    for ((index, hunk) in file.hunks.withIndex()) {
        val hunkLines = byHunk[index].orEmpty()
        val firstOld = hunkLines.firstNotNullOfOrNull { it.oldLine } ?: hunk.oldStart.takeIf { it > 0 }
        val firstNew = hunkLines.firstNotNullOfOrNull { it.newLine } ?: hunk.newStart.takeIf { it > 0 }
        fill(index, firstOld, firstNew)
        hunkLines.forEach { line ->
            result += DiffEntry.Line(line.copy(gapBefore = 0))
            line.oldLine?.let { old = it + 1 }
            line.newLine?.let { new = it + 1 }
        }
    }
    fill(file.hunks.size, null, null)
    return result
}

/** `content.split('\n')` without the empty line a trailing newline leaves. */
fun splitFileLines(text: String): List<String> {
    val parts = text.split('\n')
    return if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
}

/** A line diff of two whole files (LCS), for a file whose patch was too large to send. */
internal fun wholeFileDiff(old: List<String>?, new: List<String>?): List<DiffLine> {
    if (old == null) return new.orEmpty().mapIndexed { i, text -> DiffLine('+', text, newLine = i + 1) }
    if (new == null) return old.mapIndexed { i, text -> DiffLine('-', text, oldLine = i + 1) }
    // Trim the common head and tail, then LCS the middle if it is small enough.
    var head = 0
    while (head < old.size && head < new.size && old[head] == new[head]) head++
    var tail = 0
    while (tail < old.size - head && tail < new.size - head && old[old.size - 1 - tail] == new[new.size - 1 - tail]) tail++
    val a = old.subList(head, old.size - tail)
    val b = new.subList(head, new.size - tail)
    val out = mutableListOf<DiffLine>()
    for (i in 0 until head) out += DiffLine(' ', new[i], newLine = i + 1, oldLine = i + 1)
    if (a.size.toLong() * b.size <= 4_000_000L) {
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { out += DiffLine(' ', b[j], newLine = head + j + 1, oldLine = head + i + 1); i++; j++ }
                // Deletions before additions, like git.
                i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> { out += DiffLine('-', a[i], oldLine = head + i + 1); i++ }
                else -> { out += DiffLine('+', b[j], newLine = head + j + 1); j++ }
            }
        }
    } else {
        a.forEachIndexed { i, text -> out += DiffLine('-', text, oldLine = head + i + 1) }
        b.forEachIndexed { j, text -> out += DiffLine('+', text, newLine = head + j + 1) }
    }
    for (k in 0 until tail) {
        out += DiffLine(' ', new[new.size - tail + k], newLine = new.size - tail + k + 1, oldLine = old.size - tail + k + 1)
    }
    return out
}

// --- Split pairing ---

/** One split row: indexes into the unified line list, `null` where that side is empty. */
data class SplitRow(val left: Int?, val right: Int?)

/**
 * Pairs a unified diff into two columns the way the web split view does: context on
 * both sides, each run of deletions paired line by line with the additions after it.
 */
fun splitRows(lines: List<DiffLine>): List<SplitRow> {
    val rows = mutableListOf<SplitRow>()
    var index = 0
    while (index < lines.size) {
        when (lines[index].kind) {
            ' ' -> { rows += SplitRow(index, index); index += 1 }
            '-' -> {
                val deletions = mutableListOf<Int>()
                while (index < lines.size && lines[index].kind == '-') deletions += index++
                val additions = mutableListOf<Int>()
                while (index < lines.size && lines[index].kind == '+') additions += index++
                for (pair in 0 until maxOf(deletions.size, additions.size)) rows += SplitRow(deletions.getOrNull(pair), additions.getOrNull(pair))
            }
            else -> { rows += SplitRow(null, index); index += 1 }
        }
    }
    return rows
}

/** One split-view row: a gap, or a pair of lines (null where that side is empty). */
sealed interface SplitEntry {
    data class Gap(val gap: DiffEntry.Gap) : SplitEntry
    data class Pair(val left: DiffLine?, val right: DiffLine?) : SplitEntry
}

/** The web split view: gaps stay full-width, line runs pair via [splitRows]. */
fun splitEntries(entries: List<DiffEntry>): List<SplitEntry> {
    val out = mutableListOf<SplitEntry>()
    val run = mutableListOf<DiffLine>()
    fun flush() {
        splitRows(run).forEach { row ->
            out += SplitEntry.Pair(row.left?.let { run[it] }, row.right?.let { run[it] })
        }
        run.clear()
    }
    entries.forEach { entry ->
        when (entry) {
            is DiffEntry.Gap -> { flush(); out += SplitEntry.Gap(entry) }
            is DiffEntry.Line -> run += entry.line
        }
    }
    flush()
    return out
}

/** The narrowest screen the split layout is drawn on; a phone held upright is unified. */
const val SPLIT_MIN_DP = 640

/** Web `effectiveLayout`: unified when asked, for one-sided files, or below [SPLIT_MIN_DP]. */
fun effectiveSplit(layout: String, file: ReviewFile, widthDp: Int): Boolean =
    layout == "split" && file.additions > 0 && file.deletions > 0 && (widthDp <= 0 || widthDp >= SPLIT_MIN_DP)

// --- Noisy files (`noisy-files.ts` + core `GENERATED_PATTERNS`) ---

/** `GENERATED_PATTERNS` from `packages/core/src/analyze/adapters/rule-based/rules.ts`. */
val GENERATED_PATTERNS = listOf(
    "**/*.generated.*",
    "**/pnpm-lock.yaml",
    "**/yarn.lock",
    "**/package-lock.json",
    "**/Cargo.lock",
    "**/go.sum",
    "**/dist/**",
    "**/*.lock",
)

/** A picomatch-style glob as a regex: `**` spans segments, `*` stays in one, neither matches a dot segment. */
fun globRegex(glob: String): Regex {
    val out = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val ch = glob[i]
        when {
            glob.startsWith("**/", i) -> { out.append("(?:(?!\\.)[^/]+/)*"); i += 3 }
            glob.startsWith("/**", i) && i + 3 == glob.length -> { out.append("(?:/(?!\\.)[^/]+)*"); i += 3 }
            glob.startsWith("**", i) -> { out.append("(?:(?!\\.)[^/]*(?:/(?!\\.)[^/]*)*)"); i += 2 }
            ch == '*' -> {
                val segmentStart = i == 0 || glob[i - 1] == '/'
                out.append(if (segmentStart) "(?!\\.)[^/]*" else "[^/]*"); i += 1
            }
            ch == '?' -> { out.append("[^/]"); i += 1 }
            else -> { out.append(Regex.escape(ch.toString())); i += 1 }
        }
    }
    return Regex(out.append("$").toString())
}

private val generated = GENERATED_PATTERNS.map(::globRegex)

/** Generated/lockfile-style files, collapsed on first render. */
fun isNoisyFile(path: String): Boolean = generated.any { it.matches(path) }

// --- File tree (`FileTree.vue`) ---

enum class TreeRowType { Folder, File, Missing }

/** One tree row; folder rows carry every file under them for the folder checkbox. */
data class TreeRow(val key: String, val depth: Int, val type: TreeRowType, val name: String, val file: ReviewFile? = null, val files: List<ReviewFile> = emptyList())

private class TreeNode(val name: String) {
    val children = LinkedHashMap<String, TreeNode>()
    var file: ReviewFile? = null
    var missing = false
}

private val collator: Collator = Collator.getInstance(Locale.ENGLISH)

/**
 * The web tree rows: paths sorted like `localeCompare`, a chain of single-child folders
 * joined into one row (`src/components`), files and missing paths (analysis paths no
 * longer in the diff) as leaves.
 */
fun treeRows(files: List<ReviewFile>, missing: List<String> = emptyList()): List<TreeRow> {
    val root = TreeNode("")
    val leaves = (files.map { it.path to it } + missing.map { it to null }).sortedWith { a, b -> collator.compare(a.first, b.first) }
    for ((path, file) in leaves) {
        val segments = path.split('/')
        var node = root
        for (segment in segments.dropLast(1)) node = node.children.getOrPut(segment) { TreeNode(segment) }
        node.children["\u0000file:$path"] = TreeNode(segments.last()).also { it.file = file; it.missing = file == null }
    }
    fun collect(node: TreeNode): List<ReviewFile> = node.children.values.flatMap { child -> child.file?.let { listOf(it) } ?: collect(child) }
    val result = mutableListOf<TreeRow>()
    fun walk(node: TreeNode, depth: Int, prefix: String) {
        for ((key, child) in node.children) {
            val file = child.file
            if (file != null) { result += TreeRow("file:${file.path}", depth, TreeRowType.File, child.name, file = file); continue }
            if (child.missing) { result += TreeRow("missing:$prefix${child.name}", depth, TreeRowType.Missing, child.name); continue }
            var name = child.name
            var tail = child
            var path = "$prefix$key/"
            while (tail.children.size == 1) {
                val (onlyKey, only) = tail.children.entries.first()
                if (only.file != null || only.missing) break
                name += "/${only.name}"
                tail = only
                path += "$onlyKey/"
            }
            result += TreeRow("folder:$path", depth, TreeRowType.Folder, name, files = collect(tail))
            walk(tail, depth + 1, path)
        }
    }
    walk(root, 0, "")
    return result
}

/** Folder checkbox state: all, some (partial) or none of its files reviewed. */
fun folderState(files: List<ReviewFile>, reviewed: Set<String>): ReviewState {
    val count = files.count { it.sha in reviewed }
    return when {
        count == 0 -> ReviewState.Unreviewed
        count == files.size -> ReviewState.Reviewed
        else -> ReviewState.Partial
    }
}

// --- Annotations: threads, AI notes, findings, ask threads ---

/** Where an annotation sits: `additions` (new line numbers) or `deletions` (old). */
data class Anchor(val side: String, val line: Int)

/** The anchors a diff line answers to: a context line sits on both sides. */
fun DiffLine.anchors(): List<Anchor> = buildList {
    if (kind != '-') newLine?.let { add(Anchor("additions", it)) }
    if (kind != '+') oldLine?.let { add(Anchor("deletions", it)) }
}

/** Index of the entry each anchor sits under; anchors outside the shown lines are left out, like pierre. */
fun placeAnchors(lines: List<List<DiffLine>>, anchors: Collection<Anchor>): Map<Anchor, Int> {
    if (anchors.isEmpty()) return emptyMap()
    val wanted = anchors.toSet()
    val out = HashMap<Anchor, Int>()
    lines.forEachIndexed { index, row -> row.forEach { line -> line.anchors().forEach { if (it in wanted && it !in out) out[it] = index } } }
    return out
}

/** Web `visibleThreads`: this file's threads on a line, not outdated, not resolved, shown or pending. */
fun visibleThreads(threads: List<ReviewThread>, path: String, showThreads: Boolean, includeResolved: Boolean = false): List<ReviewThread> =
    threads.filter { it.path == path && it.line != null && !it.outdated && (includeResolved || !it.resolved) && (showThreads || it.pending) }

/**
 * `hunkAnchor`: a hunk note sits under the last unchanged line before the hunk's first
 * change, or on that change when the hunk opens with it.
 */
fun hunkAnchor(hunkLines: List<DiffLine>): Anchor? {
    if (hunkLines.isEmpty()) return null
    var leading = 0
    for (line in hunkLines) {
        if (line.kind == ' ') { leading++; continue }
        if (leading > 0) break
        return if (line.kind == '-') Anchor("deletions", line.oldLine ?: return null) else Anchor("additions", line.newLine ?: return null)
    }
    return if (leading > 0) hunkLines[leading - 1].newLine?.let { Anchor("additions", it) } else null
}

/** `AiAnchor`: AI content shown under one line. */
data class AiAnchor(val anchor: Anchor, val findings: List<CritiqueFinding>, val notes: List<String>, val asks: List<AskThread>)

/** `aiAnchors`: findings, hunk notes and ask threads of one file, grouped by line. */
fun aiAnchors(file: ReviewFile, findings: List<CritiqueFinding>, note: FileNote?, asks: List<AskThread>): Map<Anchor, AiAnchor> {
    val out = LinkedHashMap<Anchor, AiAnchor>()
    fun at(anchor: Anchor) = out.getOrPut(anchor) { AiAnchor(anchor, emptyList(), emptyList(), emptyList()) }
    findings.filter { it.path == file.path }.forEach { f -> val a = Anchor(f.side, f.line); out[a] = at(a).let { it.copy(findings = it.findings + f) } }
    if (note?.path == file.path) {
        val byHunk = file.patchLines.groupBy { it.hunk }
        note.hunks.toSortedMap().forEach { (index, text) ->
            val a = hunkAnchor(byHunk[index].orEmpty()) ?: return@forEach
            out[a] = at(a).let { it.copy(notes = it.notes + text) }
        }
    }
    asks.filter { it.path == file.path }.forEach { t -> val a = Anchor(t.side.ifBlank { "additions" }, t.endLine); out[a] = at(a).let { it.copy(asks = it.asks + t) } }
    return out
}

/** A draft over lines [a]..[b] (either order) on one side; a single line has no startLine. */
fun selectRange(path: String, side: String, a: Int, b: Int): com.octaviantocan.pulls.ui.review.DraftTarget {
    val lo = minOf(a, b)
    val hi = maxOf(a, b)
    return DraftTarget(path, side, hi, lo.takeIf { it < hi })
}

/**
 * The code of lines [range] on one [side] of the rows shown, in order, one line each: what an Ask about selected lines
 * carries. A context line belongs to both sides. Lines the diff has not loaded leave a hole, marked with a line of "…"
 * so the snippet never reads as the whole selection.
 */
fun selectedCode(entries: List<DiffEntry>, side: String, range: IntRange): String {
    val out = mutableListOf<String>()
    var last: Int? = null
    for (line in entries.mapNotNull { (it as? DiffEntry.Line)?.line }) {
        val at = line.anchors().firstOrNull { it.side == side && it.line in range }?.line ?: continue
        if (last != null && at > last + 1) out += "…"
        out += line.text
        last = at
    }
    return out.joinToString("\n")
}

// --- Formatting ---

/** `formatTimeAgo` from `@antfu/design`: "just now", "3 d ago", "in 2 h". */
fun timeAgo(time: Long, now: Long = System.currentTimeMillis()): String {
    val second = 1000L
    val minute = 60 * second
    val hour = 60 * minute
    val day = 24 * hour
    val month = 30 * day
    val year = 365 * day
    val delta = now - time
    val abs = kotlin.math.abs(delta)
    if (abs < second) return "just now"
    val units = listOf(Triple(minute, second, "s"), Triple(hour, minute, "min"), Triple(day, hour, "h"), Triple(month, day, "d"), Triple(year, month, "mo"), Triple(Long.MAX_VALUE, year, "y"))
    for ((limit, divisor, unit) in units) {
        if (abs < limit) {
            val value = Math.round(abs.toDouble() / divisor)
            return if (delta >= 0) "$value $unit ago" else "in $value $unit"
        }
    }
    return ""
}

fun parseIsoMillis(iso: String): Long? = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()

// --- Word emphasis (pierre `lineDiffType: 'word-alt'`, jsdiff `diffWordsWithSpace`) ---

private const val WORD_CHARS = "a-zA-Z0-9_\u00AD\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02C6\u02C8-\u02D7\u02DE-\u02FF\u1E00-\u1EFF"
private val WORD_TOKEN = Regex("(\\r?\\n)|[$WORD_CHARS]+|[^\\S\\n\\r]+|[^$WORD_CHARS]")

/** `WordsWithSpaceDiff.tokenize`: words, whitespace runs, and single other characters. */
fun wordTokens(text: String): List<String> = WORD_TOKEN.findAll(text).map { it.value }.toList()

/** A token run of a word diff: 0 unchanged, -1 removed, 1 added. */
data class WordPart(val kind: Int, val value: String)

/** Token diff by longest common subsequence; a change region lists removals before additions, like jsdiff. */
fun diffWords(old: String, new: String): List<WordPart> {
    val a = wordTokens(old)
    val b = wordTokens(new)
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed())
        lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    val out = mutableListOf<WordPart>()
    fun push(kind: Int, value: String) {
        val last = out.lastOrNull()
        if (last != null && last.kind == kind) out[out.lastIndex] = last.copy(value = last.value + value) else out += WordPart(kind, value)
    }
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        when {
            i < a.size && j < b.size && a[i] == b[j] -> { push(0, a[i]); i++; j++ }
            else -> {
                // Collect the whole change region, removals first.
                val removed = StringBuilder()
                val added = StringBuilder()
                while (i < a.size || j < b.size) {
                    if (i < a.size && j < b.size && a[i] == b[j]) break
                    if (j >= b.size || (i < a.size && lcs[i + 1][j] >= lcs[i][j + 1])) { removed.append(a[i]); i++ }
                    else { added.append(b[j]); j++ }
                }
                if (removed.isNotEmpty()) out += WordPart(-1, removed.toString())
                if (added.isNotEmpty()) out += WordPart(1, added.toString())
            }
        }
    }
    return out
}

/**
 * `computeLineDiffDecorations` with `word-alt`: the character ranges to emphasise on a removed
 * line and its paired added line. Neutral runs of one character between changes join the change,
 * so `a.b` -> `c.d` shades as one span. Lines over 1000 characters get none (`maxLineDiffLength`).
 */
fun wordEmphasis(old: String, new: String): Pair<List<IntRange>, List<IntRange>> {
    if (old.length > 1000 || new.length > 1000) return emptyList<IntRange>() to emptyList()
    val parts = diffWords(old, new)
    val last = parts.lastOrNull()
    fun spans(keep: Int): List<IntRange> {
        val arr = mutableListOf<Pair<Int, String>>()
        for (part in parts) {
            if (part.kind != 0 && part.kind != keep) continue
            val neutral = part.kind == 0
            val prev = arr.lastOrNull()
            if (prev == null || part === last) { arr += (if (neutral) 0 else 1) to part.value; continue }
            val prevNeutral = prev.first == 0
            if (neutral == prevNeutral || (neutral && part.value.length == 1 && !prevNeutral)) arr[arr.lastIndex] = prev.first to prev.second + part.value
            else arr += (if (neutral) 0 else 1) to part.value
        }
        var at = 0
        return arr.mapNotNull { (flag, text) -> val r = if (flag == 1) at until at + text.length else null; at += text.length; r }
    }
    return spans(-1) to spans(1)
}

/** Emphasis per line object: each run of removals pairs, by position, with the additions right after it. */
fun lineEmphasis(lines: List<DiffLine>): java.util.IdentityHashMap<DiffLine, List<IntRange>> {
    val out = java.util.IdentityHashMap<DiffLine, List<IntRange>>()
    var k = 0
    while (k < lines.size) {
        if (lines[k].kind != '-') { k++; continue }
        val dels = mutableListOf<DiffLine>()
        while (k < lines.size && lines[k].kind == '-') dels += lines[k++]
        val adds = mutableListOf<DiffLine>()
        while (k < lines.size && lines[k].kind == '+') adds += lines[k++]
        for (n in 0 until minOf(dels.size, adds.size)) {
            val (d, a) = wordEmphasis(dels[n].text, adds[n].text)
            if (d.isNotEmpty()) out[dels[n]] = d
            if (a.isNotEmpty()) out[adds[n]] = a
        }
    }
    return out
}
