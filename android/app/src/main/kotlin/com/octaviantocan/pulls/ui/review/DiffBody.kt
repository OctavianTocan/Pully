package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.FileGroup
import com.octaviantocan.pulls.data.FullFile
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Per-page UI state of the diff body: collapsed groups and files, drafts and selections,
 * highlight spans, loaded full files, and the item index map the focus scroller reads.
 */
class DiffUi(val session: ReviewSession) {
    /** Items [diffBodyItems] emitted last time, so focus can scroll: index = total - bodyCount + local. */
    var bodyCount: Int = 0

    /** Collapsed top-level and child groups (`collapsedGroups`, `collapsedChildren`); child keys are `parent/child`. */
    val collapsedGroups = mutableStateMapOf<String, Boolean>()

    /** Explicit file collapse state; absent falls back to reviewed-or-noisy (web initial `collapsed`). */
    val collapsedFiles = mutableStateMapOf<String, Boolean>()

    /** Phones only: whether a file's folded actions row is open. */
    val actionsOpen = mutableStateMapOf<String, Boolean>()

    /** Full file contents by path, and which loads are running or failed. */
    val fullFiles = mutableStateMapOf<String, FullFile>()
    val fullLoading = mutableStateMapOf<String, Boolean>()
    val fullErrors = mutableStateMapOf<String, String>()

    /** Gap ids opened per path once the full file is in; a null value opens every gap ("Load full file content"). */
    val expandedGaps = mutableStateMapOf<String, Set<Int>?>()

    /** The open draft (comment composer + selection actions) per file. */
    val drafts = mutableStateMapOf<String, DraftTarget>()

    /** A range selection in progress per file: its first tapped line. */
    val selectionStart = mutableStateMapOf<String, DraftTarget>()

    /** Highlight tokens by path, then by line text (the server tokenizes each line on its own). */
    val tokens = mutableStateMapOf<String, Map<String, List<Tok>>>()
    var theme by mutableStateOf<DiffTheme?>(null)
    internal var themeDark: Boolean? = null

    /** The line a focus request points at. */
    var mark by mutableStateOf<LineMark?>(null)

    /** Paths whose diff rows are on screen (the tree highlights them). */
    var visiblePaths by mutableStateOf(setOf<String>())

    /** Width of the page in dp, for the split/unified choice (kept by [DiffFocusEffect]). */
    var widthDp by mutableStateOf(0)

    /** Labels that need i18n outside composition; [DiffFocusEffect] keeps them current. */
    var uncategorizedLabel by mutableStateOf("Uncategorized")
    var uncategorizedSummary by mutableStateOf("Files not covered by the current analysis.")

    /** Shared sideways scroll of each file's code, by path and column (0 unified, 1 left, 2 right). */
    internal val scrolls = HashMap<Pair<String, Int>, HScroll>()

    // Rebuilt by every emission: item key -> local index, file path of an item, line anchor -> item key.
    internal var indexOf: Map<String, Int> = emptyMap()
    internal var pathOfKey: Map<String, String> = emptyMap()
    internal var lineKey: Map<String, String> = emptyMap()

    fun isFileCollapsed(file: ReviewFile): Boolean =
        collapsedFiles[file.path] ?: (file.sha in session.reviewed || isNoisyFile(file.path))

    fun setFileCollapsed(path: String, value: Boolean) { collapsedFiles[path] = value }

    fun isGroupCollapsed(key: String): Boolean = collapsedGroups[key] == true
    fun toggleGroup(key: String) { collapsedGroups[key] = !isGroupCollapsed(key) }

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
    private val entryCache = HashMap<String, CachedEntries>()

    /** Requests highlight spans for lines of [file] not tokenized yet (`highlight`, the pierre theme). */
    suspend fun highlight(file: ReviewFile, texts: List<String>, dark: Boolean) {
        if (themeDark != dark) { tokens.clear(); themeDark = dark }
        val have = tokens[file.path].orEmpty()
        val missing = texts.filter { it !in have }.distinct()
        if (missing.isEmpty()) return
        val next = have.toMutableMap()
        for (chunk in missing.chunked(1500)) {
            val result = runCatching { session.repo.highlight(file.path, chunk, dark) }.getOrNull() ?: return
            (result["theme"] as? JsonObject)?.let { t ->
                fun col(k: String) = (t[k] as? JsonPrimitive)?.contentOrNull?.let(::hexColor)
                theme = DiffTheme(col("background"), col("foreground"), col("added"), col("removed"), col("gutter"))
            }
            val lines = result["lines"] as? JsonArray ?: return
            chunk.forEachIndexed { i, text ->
                next[text] = (lines.getOrNull(i) as? JsonArray).orEmpty().mapNotNull { span ->
                    val o = span as? JsonObject ?: return@mapNotNull null
                    Tok((o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["color"] as? JsonPrimitive)?.contentOrNull?.let(::hexColor))
                }
            }
        }
        tokens[file.path] = next
    }
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

/** Emits items and records each one's local index, file path, and the line anchors it holds. */
internal class Emitter(private val scope: LazyListScope) {
    var count = 0
    val index = HashMap<String, Int>()
    val paths = HashMap<String, String>()
    val lines = HashMap<String, String>()
    val files = HashSet<String>()

    fun item(key: String, type: String, path: String? = null, content: @Composable LazyItemScope.() -> Unit) {
        index[key] = count++
        if (path != null) paths[key] = path
        scope.item(key = key, contentType = type) { content() }
    }

    fun sticky(key: String, type: String, path: String? = null, content: @Composable LazyItemScope.() -> Unit) {
        index[key] = count++
        if (path != null) paths[key] = path
        scope.stickyHeader(key = key, contentType = type) { content() }
    }
}

/**
 * Ports `DiffGroup.vue`, `FileTree.vue`, `FileDiff.vue` and the review threads. MUST be the
 * last call in the page's LazyColumn (it ends with the "end of diff" footer). Emits small
 * items (group header, tree, file header, one per diff row, one per annotation group) so
 * long diffs scroll smoothly.
 */
fun LazyListScope.diffBodyItems(session: ReviewSession, ui: DiffUi) {
    val emit = Emitter(this)
    if (session.opened != null) {
        groupViews(session, ui).forEach { view ->
            if (view.children.isNotEmpty()) {
                // A parent with children is a full-width chapter band; children render as ordinary groups.
                val collapsed = ui.isGroupCollapsed(view.key)
                emit.item("diff:g:${view.key}", "chapter") {
                    ChapterHeader(view, collapsed, session.reviewed, { ui.toggleGroup(view.key) }) { child -> session.focusGroupKey = "${view.key}/$child" }
                }
                if (!collapsed) {
                    if (view.files.isNotEmpty()) groupBody(emit, session, ui, view, view.key)
                    view.children.forEach { child -> normalGroup(emit, session, ui, child, "${view.key}/${child.key}", parent = view) }
                }
            } else {
                normalGroup(emit, session, ui, view, view.key, parent = null)
            }
        }
        emit.item("diff:end", "end") { EndOfDiff() }
    }
    ui.bodyCount = emit.count
    ui.indexOf = emit.index
    ui.pathOfKey = emit.paths
    ui.lineKey = emit.lines
}

private fun normalGroup(emit: Emitter, session: ReviewSession, ui: DiffUi, view: GroupView, key: String, parent: GroupView?) {
    val collapsed = ui.isGroupCollapsed(key)
    emit.item("diff:g:$key", "group") {
        GroupHeader(view, collapsed, session.reviewed, parent?.label, { ui.toggleGroup(key) }) { parent?.let { session.focusGroupKey = it.key } }
    }
    if (!collapsed) groupBody(emit, session, ui, view, key)
}

/** The group's tree, then each of its files (the web `aside` + files column, stacked on phones). */
private fun groupBody(emit: Emitter, session: ReviewSession, ui: DiffUi, view: GroupView, key: String) {
    val rows = treeRows(view.files, view.missing)
    if (rows.isNotEmpty()) {
        emit.item("diff:t:$key", "tree") {
            FileTreeView(session, rows, ui.visiblePaths, { path -> session.focus = Focus(path) }, Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp))
        }
    }
    // A file two groups both name renders once (item keys must be unique), under the first.
    view.files.forEach { file -> if (emit.files.add(file.path)) fileItems(emit, session, ui, file) }
    emit.item("diff:gend:$key", "spacer") { Box(Modifier.fillMaxWidth().padding(top = 16.dp)) }
}

/**
 * Keeps `session.focus` and `session.focusGroupKey` on screen: opens the group and file,
 * loads the full file when a gap hides the line, scrolls to the file header or the exact
 * line and marks it. Also folds files on review changes and tracks the files on screen.
 */
@Composable
fun DiffFocusEffect(session: ReviewSession, ui: DiffUi, listState: LazyListState) {
    val width = LocalConfiguration.current.screenWidthDp
    val messages = LocalMessages.current
    val label = messages.t("group.uncategorized")
    val summary = messages.t("group.uncategorizedSummary")
    SideEffect {
        if (ui.widthDp != width) ui.widthDp = width
        if (ui.uncategorizedLabel != label) ui.uncategorizedLabel = label
        if (ui.uncategorizedSummary != summary) ui.uncategorizedSummary = summary
    }
    val dark = LocalPullsColors.current.dark
    LaunchedEffect(dark) { if (ui.themeDark != null && ui.themeDark != dark) { ui.tokens.clear(); ui.themeDark = dark } }

    suspend fun indexWhenReady(key: String): Int? {
        repeat(90) {
            ui.indexOf[key]?.let { local ->
                val total = listState.layoutInfo.totalItemsCount
                if (total >= ui.bodyCount && ui.bodyCount > 0) return total - ui.bodyCount + local
            }
            delay(16)
        }
        return null
    }

    val focus = session.focus
    LaunchedEffect(focus) {
        val target = focus ?: return@LaunchedEffect
        val file = session.files.firstOrNull { it.path == target.path } ?: return@LaunchedEffect
        groupViews(session, ui).forEach { view ->
            if (file in view.files) ui.collapsedGroups[view.key] = false
            view.children.forEach { child ->
                if (file in child.files) { ui.collapsedGroups[view.key] = false; ui.collapsedGroups["${view.key}/${child.key}"] = false }
            }
        }
        ui.setFileCollapsed(file.path, false)
        if (target.line <= 0) {
            indexWhenReady("diff:fh:${file.path}")?.let { listState.animateScrollToItem(it) }
            return@LaunchedEffect
        }
        ui.mark = LineMark(file.path, target.side, target.line, target.findingId)
        val anchor = "${file.path}|${target.side}|${target.line}"
        delay(32)
        if (ui.lineKey[anchor] == null && !file.isBinary) {
            // The line sits in an unmodified gap: load the full file and open every gap.
            ui.loadFullFile(file)
            repeat(250) { if (ui.fullLoading[file.path] != true) return@repeat; delay(20) }
            if (ui.fullFiles[file.path] != null) ui.expandedGaps[file.path] = null
        }
        var key: String? = null
        repeat(60) { if (key == null) { key = ui.lineKey[anchor]; if (key == null) delay(16) } }
        val index = key?.let { indexWhenReady(it) } ?: indexWhenReady("diff:fh:${file.path}")
        // Leave room above the line for the sticky file header and some context.
        if (index != null) listState.animateScrollToItem(index, scrollOffset = if (key != null) -240 else 0)
    }

    val groupKey = session.focusGroupKey
    LaunchedEffect(groupKey) {
        val key = groupKey ?: return@LaunchedEffect
        ui.collapsedGroups[key] = false
        if (key.contains('/')) ui.collapsedGroups[key.substringBefore('/')] = false
        // A top-level key may name a chapter child (the nav lists children without their parent).
        val resolved = if (ui.indexOf.containsKey("diff:g:$key") || key.contains('/')) key
        else groupViews(session, ui).firstNotNullOfOrNull { v -> v.children.firstOrNull { it.key == key }?.let { "${v.key}/${it.key}".also { k -> ui.collapsedGroups[v.key] = false; ui.collapsedGroups[k] = false } } } ?: key
        indexWhenReady("diff:g:$resolved")?.let { listState.animateScrollToItem(it) }
        // Consumed, so tapping the same group again scrolls again.
        if (session.focusGroupKey == key) session.focusGroupKey = null
    }

    // Web `watch(isReviewed)`: marking a file reviewed folds it, unmarking unfolds it; a file
    // folded while scrolled past is pinned back to its header so the next file starts on screen.
    LaunchedEffect(session) {
        var previous: Set<String>? = null
        snapshotFlow { session.reviewed }.collect { current ->
            val before = previous
            previous = current
            if (before == null) return@collect
            val changed = session.files.filter { (it.sha in before) != (it.sha in current) }
            changed.forEach { ui.setFileCollapsed(it.path, it.sha in current) }
            val folded = changed.singleOrNull { it.sha in current } ?: return@collect
            val local = ui.indexOf["diff:fh:${folded.path}"] ?: return@collect
            val index = listState.layoutInfo.totalItemsCount - ui.bodyCount + local
            if (listState.firstVisibleItemIndex > index) listState.scrollToItem(index)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> (info.key as? String)?.let { ui.pathOfKey[it] } }.toSet() }
            .collect { paths -> if (paths != ui.visiblePaths) ui.visiblePaths = paths }
    }
}
