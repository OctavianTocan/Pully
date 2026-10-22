package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.LocalPullsColors

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.octaviantocan.pulls.data.FullFile
import com.octaviantocan.pulls.data.ReviewFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
class DiffUi(session: ReviewSession) : OverviewUi(session) {
    /** Full file contents by path, and which loads are running or failed. */
    val fullFiles = mutableStateMapOf<String, FullFile>()
    val fullLoading = mutableStateMapOf<String, Boolean>()
    val fullErrors = mutableStateMapOf<String, String>()

    /** Gap ids opened per path once the full file is in; a null value opens every gap ("Load full file content"). */
    val expandedGaps = mutableStateMapOf<String, Set<Int>?>()

    /** The selected lines per file, which the bar at the bottom comments on (rule 94). */
    val drafts = mutableStateMapOf<String, DraftTarget>()

    /** Gutter centres on screen, `path|side|line` -> y in root px, for drag selection. */
    val gutterY = HashMap<String, Float>()

    /** A drag selection in progress (long-press a line number, then drag): highlighted, not yet a draft. */
    var dragSel by mutableStateOf<DraftTarget?>(null)

    /** The lines a long press is holding, whose sheet (Ask, Copy, Copy GitHub link) is open over the diff (K11). */
    var lineActions by mutableStateOf<DraftTarget?>(null)

    /** The line on [side] of [path] whose gutter centre is nearest [y]. */
    fun lineAt(path: String, side: String, y: Float): Int? {
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

    /** Shared sideways scroll of each file's code, by path and column (0 unified, 1 left, 2 right). */
    val scrolls = HashMap<Pair<String, Int>, HScroll>()

    // By file path, rebuilt by every emission of that file's list: item key -> index in it, line anchor -> item key. Two
    // files' lists are composed at once while one slides in beside the other, so each keeps its own.
    val indexOf = HashMap<String, Map<String, Int>>()
    val lineKey = HashMap<String, Map<String, String>>()

    /** Each file's place in its list, kept while the page lives so coming back to a file finds it where it was left. */
    val lists = HashMap<String, LazyListState>()

    private var contentView: ReviewSession.Version? = session.captureView()
    private val fileReadIssuer = Any()
    private var fileRead: DiffFileRead? = null
    private val drawnFileReads = HashMap<String, DiffFileRead>()
    private var unchangedOwners = IdentityMap<ReviewFile, ReviewSession.Version>()

    /** Changed files must be current row objects; synthetic FileView rows carry their actual read owner. */
    private fun belongsToView(file: ReviewFile): Boolean =
        session.files.firstOrNull { it.path == file.path }?.let { it === file }
            ?: (unchangedOwners[file]?.let(session::isCurrentView) == true)

    fun captureFileRead(): DiffFileRead? {
        val owner = currentView() ?: return null
        return fileRead ?: DiffFileRead(fileReadIssuer, owner).also { fileRead = it }
    }
    fun isCurrentFileRead(read: DiffFileRead): Boolean =
        read.issuer === fileReadIssuer && currentView() != null && session.isCurrentView(read.owner) &&
            (read.file == null || belongsToView(read.file))

    /** A row callback carries both the issued view and the exact current file object. */
    fun captureFileRead(file: ReviewFile): DiffFileRead? {
        val owner = currentView() ?: return null
        if (!belongsToView(file)) return null
        return drawnFileReads[file.path]?.takeIf { it.file === file }
            ?: DiffFileRead(fileReadIssuer, owner, file).also { drawnFileReads[file.path] = it }
    }
    internal fun isCurrentFileRead(read: DiffFileRead?, file: ReviewFile): Boolean =
        read != null && isCurrentFileRead(read) && belongsToView(file)

    /** Registers only a result read under this same source/key/head/revision. */
    fun readUnchangedFile(read: DiffFileRead, path: String, text: String): ReviewFile? {
        if (!isCurrentFileRead(read)) return null
        return unchangedFile(path, text).also { unchangedOwners[it] = read.owner }
    }

    /** A newer head/commit selection owns separate contents, drafts, rows and syntax spans. */
    private fun currentView(): ReviewSession.Version? {
        val next = session.captureView()
        if (contentView != next) {
            fullFiles.clear(); fullLoading.clear(); fullErrors.clear(); expandedGaps.clear()
            drafts.clear(); gutterY.clear(); dragSel = null; lineActions = null; selectionStart.clear()
            tokens.clear(); theme = null; themeDark = null; mark = null
            scrolls.clear(); indexOf.clear(); lineKey.clear(); lists.clear()
            emphasisCache.clear(); entryCache.clear(); textCache.clear(); highlighting.clear()
            unchangedOwners = IdentityMap()
            fileRead = null; drawnFileReads.clear()
            contentView = next
        }
        return next
    }

    fun canLoadFull(file: ReviewFile): Boolean = currentView() != null && belongsToView(file) && !file.isBinary && fullFiles[file.path] == null

    /** Fetches both sides of [file] (`file-content`), then opens [gap] (every gap when null). */
    fun loadFullFile(file: ReviewFile, gap: Int? = null) {
        val owner = currentView() ?: return
        if (!belongsToView(file)) return
        val key = owner.key
        if (fullFiles[file.path] != null) { openGap(file.path, gap); return }
        if (fullLoading[file.path] == true) return
        fullLoading[file.path] = true
        fullErrors.remove(file.path)
        session.scope.launch {
            try {
                val full = session.repo.fullFile(key, file.path)
                currentCoroutineContext().ensureActive()
                if (!session.isCurrentView(owner)) return@launch
                expandedGaps[file.path] = if (gap == null) null else setOf(gap)
                fullFiles[file.path] = full
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (session.isCurrentView(owner)) fullErrors[file.path] = failure.message ?: failure.toString()
            } finally {
                if (session.isCurrentView(owner)) fullLoading[file.path] = false
            }
        }
    }

    private fun openGap(path: String, gap: Int?) {
        if (expandedGaps.containsKey(path) && expandedGaps[path] == null) return
        expandedGaps[path] = if (gap == null) null else expandedGaps[path].orEmpty() + gap
    }

    /** The rows of [file] as shown now: patch gaps, or the full file with the opened gaps filled. */
    fun entries(file: ReviewFile): List<DiffEntry> {
        if (currentView() == null || !belongsToView(file)) return emptyList()
        val full = fullFiles[file.path]
        val gaps = if (full == null) emptySet() else expandedGaps[file.path]
        val cached = entryCache[file.path]
        if (cached != null && cached.file === file && cached.full === full && cached.gaps == gaps) return cached.entries
        return diffEntries(file, full, gaps).also { entryCache[file.path] = CachedEntries(file, full, gaps, it) }
    }

    private class CachedEntries(val file: ReviewFile, val full: FullFile?, val gaps: Set<Int>?, val entries: List<DiffEntry>)

    /** Word emphasis ranges per line object of [file]'s current rows (pierre `word-alt`). */
    fun emphasis(file: ReviewFile): IdentityMap<com.octaviantocan.pulls.data.DiffLine, List<IntRange>> {
        val rows = entries(file)
        val cached = emphasisCache[file.path]
        if (cached != null && cached.first === rows) return cached.second
        return lineEmphasis(rows.mapNotNull { (it as? DiffEntry.Line)?.line }).also { emphasisCache[file.path] = rows to it }
    }

    private val emphasisCache = HashMap<String, Pair<List<DiffEntry>, IdentityMap<com.octaviantocan.pulls.data.DiffLine, List<IntRange>>>>()
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
        val owner = currentView() ?: return
        if (!belongsToView(file)) return
        if (texts.isEmpty()) return
        highlighting.getOrPut(file.path) { Mutex() }.withLock {
            if (!session.isCurrentView(owner)) return@withLock
            if (themeDark != dark) { tokens.clear(); themeDark = dark }
            val have = tokens[file.path].orEmpty()
            val missing = texts.filter { it !in have }
            if (missing.isEmpty()) return
            val next = have.toMutableMap()
            for (chunk in missing.chunked(HIGHLIGHT_BATCH_SIZE)) {
                val result = try { session.repo.highlight(file.path, chunk, dark) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { break }
                currentCoroutineContext().ensureActive()
                if (!session.isCurrentView(owner) || themeDark != dark) return@withLock
                val (colours, spans) = withContext(Dispatchers.Default) { readHighlight(result, chunk) }
                if (!session.isCurrentView(owner) || themeDark != dark) return@withLock
                colours?.let { theme = it }
                next.putAll(spans ?: break)
            }
            // The theme changed while the request ran: these spans are for the other one.
            if (session.isCurrentView(owner) && themeDark == dark && next.size > have.size) tokens[file.path] = next
        }
    }
}

internal const val HIGHLIGHT_BATCH_SIZE = 400

/** A `highlight` answer: the theme's colours, and the spans of each line of [chunk] by its text (null when it has no lines). */
internal fun readHighlight(result: JsonObject, chunk: List<String>): Pair<DiffTheme?, Map<String, List<Tok>>?> {
    val colours = (result["theme"] as? JsonObject)?.let { t ->
        fun col(k: String) = (t[k] as? JsonPrimitive)?.contentOrNull?.let(::hexColor)
        DiffTheme(col("background"), col("foreground"), col("added"), col("removed"), col("gutter"))
    }
    val lines = result["lines"] as? JsonArray ?: return colours to null
    val spans = HashMap<String, List<Tok>>(chunk.size * 2)
    chunk.forEachIndexed { i, text ->
        val line = lines.getOrNull(i) as? JsonArray ?: return@forEachIndexed
        spans[text] = line.mapNotNull { span ->
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


/** A shared sideways scroll for the code of one file (one per side in split view). */
class HScroll {
    var offset by mutableFloatStateOf(0f)
    var max = 0f
    /** The width the code is shown in, as last laid out, so a pinch can work out the reach at a new size. */
    var viewport = 0
}

/** Opaque source/key/head/revision captured before the unchanged-file request starts. */
class DiffFileRead internal constructor(internal val issuer: Any, internal val owner: ReviewSession.Version, internal val file: ReviewFile? = null)

/** Emits items and records each one's local index, file path, and the line anchors it holds. [leading] items sit above the first. */
internal class Emitter(private val scope: LazyListScope, leading: Int = 0, val aiAnnotation: @Composable (ReviewSession, AiAnchor, String?) -> Unit) {
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
fun LazyListScope.fileDiffItems(session: ReviewSession, ui: DiffUi, file: ReviewFile, leading: Int = 0, tail: Dp = 140.dp, aiAnnotation: @Composable (ReviewSession, AiAnchor, String?) -> Unit = { page, anchor, highlighted -> AiAnnotationBlock(page, anchor, highlighted) }) {
    val emit = Emitter(this, leading, aiAnnotation)
    fileBodyItems(emit, session, ui, file)
    emit.item("diff:end:${file.path}", "end") {
        Box(Modifier.fillMaxWidth().height(tail).background(LocalPullsColors.current.raised))
    }
    ui.indexOf[file.path] = emit.index
    ui.lineKey[file.path] = emit.lines
}
