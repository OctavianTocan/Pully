package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.FileGroup
import com.octaviantocan.pulls.data.FullFile
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A comment/selection target in one file: `side` is `additions` or `deletions`; [startLine] for a range. */
data class DraftTarget(val path: String, val side: String, val line: Int, val startLine: Int? = null) {
    val range: IntRange get() = (startLine ?: line)..line
}

/** One highlighted token; a null color is the theme foreground. */
data class Tok(val text: String, val color: Color?)

/** The pierre theme colors the server sends with highlight spans. */
data class DiffTheme(val background: Color?, val foreground: Color?, val added: Color?, val removed: Color?, val gutter: Color?)

/** The line a focus request points at (a finding jump), highlighted until the next one. */
data class LineMark(val path: String, val side: String, val line: Int, val findingId: String?)

/**
 * Per-page UI state of the diff body: drafts and selections, highlight spans, loaded full files,
 * and the item index map the focus scroller reads.
 */
class DiffUi(val session: ReviewSession) {
    /** Full file contents by path, and which loads are running or failed. */
    val fullFiles = mutableStateMapOf<String, FullFile>()
    val fullLoading = mutableStateMapOf<String, Boolean>()
    val fullErrors = mutableStateMapOf<String, String>()

    /** Gap ids opened per path once the full file is in; a null value opens every gap ("Load full file content"). */
    val expandedGaps = mutableStateMapOf<String, Set<Int>?>()

    /** The selected lines per file, which the bar at the bottom comments on (rule 94). */
    val drafts = mutableStateMapOf<String, DraftTarget>()

    /** Gutter centres on screen, `path|side|line` -> y in root px, for drag selection. */
    internal val gutterY = HashMap<String, Float>()

    /** A drag selection in progress (long-press a line number, then drag): highlighted, not yet a draft. */
    var dragSel by mutableStateOf<DraftTarget?>(null)

    /** The line on [side] of [path] whose gutter centre is nearest [y]. */
    internal fun lineAt(path: String, side: String, y: Float): Int? {
        val prefix = "$path|$side|"
        return gutterY.entries.filter { it.key.startsWith(prefix) }.minByOrNull { kotlin.math.abs(it.value - y) }?.key?.removePrefix(prefix)?.toIntOrNull()
    }

    /** A range selection in progress per file: its first tapped line. */
    val selectionStart = mutableStateMapOf<String, DraftTarget>()

    /** Highlight tokens by path, then by line text (the server tokenizes each line on its own). */
    val tokens = mutableStateMapOf<String, Map<String, List<Tok>>>()
    var theme by mutableStateOf<DiffTheme?>(null)
    internal var themeDark: Boolean? = null

    /** The line a focus request points at. */
    var mark by mutableStateOf<LineMark?>(null)

    /** Width of the page in dp, for the split/unified choice (kept by [DiffFocusEffect]). */
    var widthDp by mutableStateOf(0)

    /** Labels that need i18n outside composition; [DiffFocusEffect] keeps them current. */
    var uncategorizedLabel by mutableStateOf("Uncategorized")
    var uncategorizedSummary by mutableStateOf("Files not covered by the current analysis.")

    /** File-list sections the reader folded (true) or opened (false), by heading; an unset one follows [isFolded]'s default. */
    val folds = mutableStateMapOf<String, Boolean>()

    /** Shared sideways scroll of each file's code, by path and column (0 unified, 1 left, 2 right). */
    internal val scrolls = HashMap<Pair<String, Int>, HScroll>()

    // By file path, rebuilt by every emission of that file's list: item key -> index in it, line anchor -> item key. Two
    // files' lists are composed at once while one slides in beside the other, so each keeps its own.
    internal val indexOf = HashMap<String, Map<String, Int>>()
    internal val lineKey = HashMap<String, Map<String, String>>()

    /** Each file's place in its list, kept while the page lives so coming back to a file finds it where it was left. */
    internal val lists = HashMap<String, LazyListState>()

    fun canLoadFull(file: ReviewFile): Boolean = !file.isBinary && fullFiles[file.path] == null && session.key != null

    /** Fetches both sides of [file] (`file-content`), then opens [gap] (every gap when null). */
    fun loadFullFile(file: ReviewFile, gap: Int? = null) {
        val key = session.key ?: return
        if (fullFiles[file.path] != null) { openGap(file.path, gap); return }
        if (fullLoading[file.path] == true) return
        fullLoading[file.path] = true
        fullErrors.remove(file.path)
        session.scope.launch {
            runCatching { session.repo.fullFile(key, file.path) }
                .onSuccess { full ->
                    expandedGaps[file.path] = if (gap == null) null else setOf(gap)
                    fullFiles[file.path] = full
                }
                .onFailure { fullErrors[file.path] = it.message ?: it.toString() }
            fullLoading[file.path] = false
        }
    }

    private fun openGap(path: String, gap: Int?) {
        if (expandedGaps.containsKey(path) && expandedGaps[path] == null) return
        expandedGaps[path] = if (gap == null) null else expandedGaps[path].orEmpty() + gap
    }

    /** The rows of [file] as shown now: patch gaps, or the full file with the opened gaps filled. */
    fun entries(file: ReviewFile): List<DiffEntry> {
        val full = fullFiles[file.path]
        val gaps = if (full == null) emptySet() else expandedGaps[file.path]
        val cached = entryCache[file.path]
        if (cached != null && cached.file === file && cached.full === full && cached.gaps == gaps) return cached.entries
        return diffEntries(file, full, gaps).also { entryCache[file.path] = CachedEntries(file, full, gaps, it) }
    }

    private class CachedEntries(val file: ReviewFile, val full: FullFile?, val gaps: Set<Int>?, val entries: List<DiffEntry>)

    /** Word emphasis ranges per line object of [file]'s current rows (pierre `word-alt`). */
    fun emphasis(file: ReviewFile): java.util.IdentityHashMap<com.octaviantocan.pulls.data.DiffLine, List<IntRange>> {
        val rows = entries(file)
        val cached = emphasisCache[file.path]
        if (cached != null && cached.first === rows) return cached.second
        return lineEmphasis(rows.mapNotNull { (it as? DiffEntry.Line)?.line }).also { emphasisCache[file.path] = rows to it }
    }

    private val emphasisCache = HashMap<String, Pair<List<DiffEntry>, java.util.IdentityHashMap<com.octaviantocan.pulls.data.DiffLine, List<IntRange>>>>()
    private val entryCache = HashMap<String, CachedEntries>()

    /** The code of each line of [file]'s current rows, what [highlight] colours; the same list until the rows change. */
    fun lineTexts(file: ReviewFile): List<String> {
        val rows = entries(file)
        val cached = textCache[file.path]
        if (cached != null && cached.first === rows) return cached.second
        return rows.mapNotNull { (it as? DiffEntry.Line)?.line?.text }.distinct().also { textCache[file.path] = rows to it }
    }

    private val textCache = HashMap<String, Pair<List<DiffEntry>, List<String>>>()

    /** One request at a time per file: the screen asks for a file and its neighbours, and a second ask waits and finds the spans there. */
    private val highlighting = HashMap<String, Mutex>()

    /**
     * Requests highlight spans for lines of [file] not tokenized yet (`highlight`, the pierre theme). The screen asks
     * for the file on screen and the ones a swipe away, so a file arrives already coloured; the answer is read off the
     * main thread, and the spans are kept per file for as long as the page lives.
     */
    suspend fun highlight(file: ReviewFile, texts: List<String>, dark: Boolean) {
        if (texts.isEmpty()) return
        highlighting.getOrPut(file.path) { Mutex() }.withLock {
            if (themeDark != dark) { tokens.clear(); themeDark = dark }
            val have = tokens[file.path].orEmpty()
            val missing = texts.filter { it !in have }
            if (missing.isEmpty()) return
            val next = have.toMutableMap()
            for (chunk in missing.chunked(1500)) {
                val result = runCatching { session.repo.highlight(file.path, chunk, dark) }.getOrNull() ?: break
                val (colours, spans) = withContext(Dispatchers.Default) { readHighlight(result, chunk) }
                colours?.let { theme = it }
                next.putAll(spans ?: break)
            }
            // The theme changed while the request ran: these spans are for the other one.
            if (themeDark == dark && next.size > have.size) tokens[file.path] = next
        }
    }
}

/** A `highlight` answer: the theme's colours, and the spans of each line of [chunk] by its text (null when it has no lines). */
private fun readHighlight(result: JsonObject, chunk: List<String>): Pair<DiffTheme?, Map<String, List<Tok>>?> {
    val colours = (result["theme"] as? JsonObject)?.let { t ->
        fun col(k: String) = (t[k] as? JsonPrimitive)?.contentOrNull?.let(::hexColor)
        DiffTheme(col("background"), col("foreground"), col("added"), col("removed"), col("gutter"))
    }
    val lines = result["lines"] as? JsonArray ?: return colours to null
    val spans = HashMap<String, List<Tok>>(chunk.size * 2)
    chunk.forEachIndexed { i, text ->
        spans[text] = (lines.getOrNull(i) as? JsonArray).orEmpty().mapNotNull { span ->
            val o = span as? JsonObject ?: return@mapNotNull null
            Tok((o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["color"] as? JsonPrimitive)?.contentOrNull?.let(::hexColor))
        }
    }
    return colours to spans
}

/** `#rrggbb` or `#rrggbbaa` to a Compose color. */
fun hexColor(hex: String): Color? {
    val raw = hex.removePrefix("#")
    if (raw.length != 6 && raw.length != 8) return null
    val rgb = raw.take(6).toLongOrNull(16) ?: return null
    val alpha = if (raw.length == 8) raw.drop(6).toLongOrNull(16) ?: 0xFF else 0xFF
    return Color((alpha shl 24) or rgb)
}

@Composable
fun rememberDiffUi(session: ReviewSession): DiffUi = remember(session) { DiffUi(session) }

/** `session.groups` as views with resolved files, missing paths and the uncategorized label. */
internal fun groupViews(session: ReviewSession, ui: DiffUi): List<GroupView> {
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

/** Emits items and records each one's local index, file path, and the line anchors it holds. [leading] items sit above the first. */
internal class Emitter(private val scope: LazyListScope, leading: Int = 0) {
    var count = leading
    val index = HashMap<String, Int>()
    val paths = HashMap<String, String>()
    val lines = HashMap<String, String>()

    fun item(key: String, type: String, path: String? = null, content: @Composable LazyItemScope.() -> Unit) {
        index[key] = count++
        if (path != null) paths[key] = path
        scope.item(key = key, contentType = type) { content() }
    }
}

/**
 * Emits the rows of one [file] into this list and records where each item and line landed, so
 * [PrDiffScreen] can scroll to a line. [leading] is how many items the screen put above the rows, and
 * [tail] the room under the last one, so the file ends where its code ends and only the bar is cleared
 * (rule 92).
 */
internal fun LazyListScope.fileDiffItems(session: ReviewSession, ui: DiffUi, file: ReviewFile, leading: Int = 0, tail: Dp = 140.dp) {
    val emit = Emitter(this, leading)
    fileBodyItems(emit, session, ui, file)
    emit.item("diff:end:${file.path}", "end") {
        Box(Modifier.fillMaxWidth().height(tail).background(LocalPullsColors.current.raised))
    }
    ui.indexOf[file.path] = emit.index
    ui.lineKey[file.path] = emit.lines
}
