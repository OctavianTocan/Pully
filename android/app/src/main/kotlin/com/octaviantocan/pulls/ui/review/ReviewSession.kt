package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.data.FileGroup
import com.octaviantocan.pulls.data.OpenedReview
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ReviewData
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.SavedState
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.data.asTaskState
import com.octaviantocan.pulls.ui.design.ReviewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder

/** Where the diff should scroll: a file, optionally a line on one side, optionally a finding. */
data class Focus(val path: String, val line: Int = 0, val side: String = "additions", val findingId: String? = null, val nonce: Long = System.nanoTime())

/**
 * The state of one open review page, shared by every section of it (the web `DiffsStore`
 * plus `PrOverviewStore`, `ReviewsStore`, `CritiqueStore` and the task followers). One
 * instance per page; sections read its Compose state and call its actions.
 *
 * Ownership of the sections that read it: header and overview (`Header.kt`, `Overview.kt`,
 * `ReviewDialogs.kt`), diff body (`DiffBody.kt`, `FileDiff.kt`, `Threads.kt`), AI
 * (`ui/ai/`). Add state here only when two sections need it.
 */
class ReviewSession(val target: OpenTarget, val repo: Repo, val scope: CoroutineScope, val settings: AppSettings) {
    /** The diff on screen: the PR itself, or the commits picked in the commit picker. */
    var opened by mutableStateOf<OpenedReview?>(null)
        private set

    /** The PR's own diff, kept while a commit selection replaces [opened]. */
    var prOpened by mutableStateOf<OpenedReview?>(null)
        private set

    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    /** AI work saved on the server for [opened]: asks, critique, chat, AI groups, analysis. */
    var saved by mutableStateOf<SavedState?>(null)

    /** GitHub review threads, summaries and the pending review (PRs only). */
    var reviewData by mutableStateOf<ReviewData?>(null)

    /** File blob shas marked reviewed, and paths reviewed earlier that changed since. */
    var reviewed by mutableStateOf(setOf<String>())
        private set
    var changedSinceReviewed by mutableStateOf(setOf<String>())
        private set

    /** The `pr-overview` answer: `{ overview, mergeability, defaultCommits }` (PRs only). */
    var overview by mutableStateOf<JsonObject?>(null)
    var overviewError by mutableStateOf<String?>(null)

    /** `rules` or `ai`: which grouping the diff shows (the header's "Analyze by" toggle). */
    var analyzeMode by mutableStateOf("ai")

    /** Commit picker selection, `from`..`to` shas (inclusive); null is all commits. */
    var selection by mutableStateOf<Pair<String, String>?>(null)
        private set

    /** Scroll requests for the diff body; the body consumes and keeps the last one. */
    var focus by mutableStateOf<Focus?>(null)
    var focusGroupKey by mutableStateOf<String?>(null)

    /** Tasks by id, merged across polls. Use [taskFor] to find the one a button shows. */
    val tasks = mutableStateMapOf<String, TaskState>()
    private val followers = mutableMapOf<String, Job>()

    val pull: OpenTarget.Pull? get() = target as? OpenTarget.Pull
    val key: String? get() = opened?.key
    val files: List<ReviewFile> get() = opened?.files.orEmpty()

    /** Groups to render: the AI analysis when shown and present, else the rule-based ones, then "Uncategorized". */
    val groups: List<FileGroup>
        get() {
            val ai = saved?.aiGroups.orEmpty()
            val base = if (analyzeMode == "ai" && ai.isNotEmpty()) ai else opened?.groups.orEmpty()
            return resolveGroups(base, files)
        }

    val hasAiResult: Boolean get() = saved?.aiGroups?.isNotEmpty() == true

    /** The server's GitHub token may write reviews here (web `reviews.canWrite`). */
    val canWrite: Boolean get() = reviewData?.canWrite == true

    /** Severities the critique panel shows; the diff's inline findings follow the same filter. */
    var findingSeverities by mutableStateOf<Set<String>?>(null)

    val visibleFindings: List<CritiqueFinding>
        get() = saved?.critique?.findings.orEmpty().filter { finding -> findingSeverities?.contains(finding.severity) ?: true }

    fun load() {
        scope.launch {
            loading = true
            loadError = null
            runCatching { repo.open(target) }
                .onSuccess { prOpened = it; if (selection == null) opened = it; afterOpen() }
                .onFailure { loadError = it.message ?: it.toString() }
            loading = false
        }
        pull?.let { reloadOverview(); reloadReviews() }
    }

    /** Refetches the diff, the overview and the reviews; AI results stay. A commit selection is dropped. */
    fun refresh() {
        selection = null
        load()
    }

    private suspend fun afterOpen() {
        val key = opened?.key ?: return
        reloadSavedNow()
        runCatching { repo.reviewed(key) }.onSuccess { (shas, changed) -> reviewed = shas; changedSinceReviewed = changed }
        if (!hasAiResult) analyzeMode = "rules"
        resumeTasks()
    }

    fun reloadSaved() { scope.launch { reloadSavedNow() } }

    private suspend fun reloadSavedNow() {
        val key = opened?.key ?: return
        runCatching { repo.savedState(key) }.onSuccess { saved = it }
    }

    fun reloadOverview() {
        val pull = pull ?: return
        scope.launch {
            runCatching { repo.overview(pull) }.onSuccess { overview = it; overviewError = null }.onFailure { overviewError = it.message }
        }
    }

    fun reloadReviews() {
        val pull = pull ?: return
        scope.launch { runCatching { repo.reviewData(pull) }.onSuccess { reviewData = it } }
    }

    fun reviewState(file: ReviewFile): ReviewState = when {
        file.sha in reviewed -> ReviewState.Reviewed
        file.path in changedSinceReviewed -> ReviewState.Changed
        else -> ReviewState.Unreviewed
    }

    /** Marks or unmarks files by blob sha; either way clears their "changed since reviewed" flag (web `setReviewed`). */
    fun setReviewed(shas: Collection<String>, value: Boolean) {
        val key = key ?: return
        reviewed = if (value) reviewed + shas else reviewed - shas.toSet()
        val paths = files.filter { it.sha in shas }.map { it.path }.toSet()
        changedSinceReviewed = changedSinceReviewed - paths
        scope.launch { runCatching { repo.setReviewed(key, shas, value) } }
    }

    /** Narrows the diff to commits `from..to` of the PR (web `CommitPicker`); null restores all commits. */
    private var selectSeq = 0

    fun selectCommits(range: Pair<String, String>?) {
        val pull = pull ?: return
        val seq = ++selectSeq
        selection = range
        loadError = null
        if (range == null) {
            opened = prOpened
            scope.launch { afterOpen() }
            return
        }
        val narrowed = if (range.first == range.second) OpenTarget.Commit(pull.owner, pull.repo, range.first)
        else OpenTarget.Compare(pull.owner, pull.repo, "${range.first}^", range.second)
        scope.launch {
            loading = true
            val result = runCatching { repo.open(narrowed) }
            // A newer selection (fast previous/next taps) wins; drop this answer.
            if (seq != selectSeq) return@launch
            result.onSuccess { opened = it; afterOpen() }.onFailure { loadError = it.message }
            loading = false
        }
    }

    // --- AI tasks ---

    /** The newest task of [kind] (and, for `ask`, of [threadId]). */
    fun taskFor(kind: String, threadId: String? = null): TaskState? =
        tasks.values.filter { it.kind == kind && (threadId == null || it.threadId == threadId) }.maxByOrNull { it.startedAt }

    /**
     * Starts a server task and follows it; [onDone] runs after it settles and the saved
     * state has been reloaded. It keeps running on the server if the page goes away.
     */
    fun startTask(kind: String, extra: JsonObjectBuilder.() -> Unit = {}, onStarted: (String) -> Unit = {}, onDone: (TaskState) -> Unit = {}) {
        val key = key ?: return
        scope.launch {
            val id = runCatching { repo.startTask(kind, key, extra) }.getOrElse { error ->
                val failed = TaskState(id = "failed-${System.nanoTime()}", kind = kind, key = key, status = "failed", error = error.message, startedAt = System.currentTimeMillis())
                tasks[failed.id] = failed
                onStarted(failed.id)
                onDone(failed)
                return@launch
            }
            tasks[id] = TaskState(id = id, kind = kind, key = key, startedAt = System.currentTimeMillis())
            onStarted(id)
            follow(id, onDone)
        }
    }

    fun cancelTask(id: String) { scope.launch { repo.cancelTask(id) } }

    /** Stops following without cancelling (web `detach`), e.g. when the page closes. */
    fun detach() { followers.values.forEach { it.cancel() }; followers.clear() }

    private fun follow(id: String, onDone: (TaskState) -> Unit = {}) {
        if (followers[id]?.isActive == true) return
        followers[id] = scope.launch {
            while (true) {
                val current = tasks[id] ?: break
                val next = runCatching { repo.waitTask(id, current.rev) }.getOrElse { error ->
                    tasks[id] = current.copy(status = "failed", error = error.message)
                    break
                }
                tasks[id] = if (current.label.isEmpty()) next.asTaskState().let { fresh -> fresh.copy(activities = current.merge(next).activities) } else current.merge(next)
                if (tasks[id]?.running != true) break
            }
            reloadSavedNow()
            if (tasks[id]?.kind == "analyze" && hasAiResult) analyzeMode = "ai"
            tasks[id]?.let(onDone)
        }
    }

    /** Picks up tasks already running on the server for this diff (started before a reload, or on the web). */
    private suspend fun resumeTasks() {
        val key = key ?: return
        runCatching { repo.tasks(key) }.onSuccess { list ->
            list.forEach { task ->
                if (tasks[task.id] == null) tasks[task.id] = task
                if (task.running) follow(task.id)
            }
        }
    }

    /** Jump to a finding's line in the diff (web `critique.focus`). */
    fun focusFinding(finding: CritiqueFinding) {
        focus = Focus(finding.path, finding.line, finding.side, finding.id)
    }
}

/**
 * Port of `group-utils.ts` `resolveGroups`: paths resolve to files (a renamed file by its
 * old path too), and files no group names collect into a trailing "uncategorized" group.
 */
fun resolveGroups(groups: List<FileGroup>, files: List<ReviewFile>): List<FileGroup> {
    val byPath = files.associateBy { it.path }
    val byPrevious = files.filter { it.previousPath != null }.associateBy { it.previousPath!! }
    val referenced = mutableSetOf<String>()
    fun resolve(group: FileGroup): FileGroup {
        val paths = group.paths.mapNotNull { path -> (byPath[path] ?: byPrevious[path])?.path }.onEach { referenced += it }
        return group.copy(paths = paths, children = group.children.map(::resolve))
    }
    val resolved = groups.map(::resolve)
    val rest = files.map { it.path }.filter { it !in referenced }
    return if (rest.isEmpty()) resolved else resolved + FileGroup(label = "", summary = "", paths = rest, key = "uncategorized", category = "other")
}
