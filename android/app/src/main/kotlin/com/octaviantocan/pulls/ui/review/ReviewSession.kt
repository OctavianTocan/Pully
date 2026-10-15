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
import com.octaviantocan.pulls.data.PrStack
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ReviewData
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.SavedState
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.data.TourStore
import com.octaviantocan.pulls.data.asTaskState
import com.octaviantocan.pulls.data.matches
import com.octaviantocan.pulls.data.withHunks
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.ui.design.ReviewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

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
class ReviewSession(val target: OpenTarget, val repo: Repo, val scope: CoroutineScope, val settings: AppSettings, private val tourStore: TourStore? = null) {
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

    /**
     * Jev's word on each file of [opened], by path: `p0` needs a human, `p1` is worth a look, `p2` is low attention. Empty
     * while the server has no Jev, before it answers, and when asking failed; a file it has not sorted yet is missing.
     */
    var attention by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** What Jev made of the pull request at [opened]'s head: its kind and risk, once a review had Jev read it. */
    var pullJev by mutableStateOf<com.octaviantocan.pulls.data.PullJev?>(null)
        private set
    /** The diff and head the marks are for: a pull request keeps its key across pushes, so the head counts too. */
    private var attentionFor: String? = null
    private var attentionJob: Job? = null

    /** The stack this pull request is a layer of, bottom first (PRs only); null when it is in none or did not load. */
    var stack by mutableStateOf<PrStack?>(null)
        private set

    /** A tour asked for while an analysis without one ran: it starts when that analysis is done (rule 113). */
    var tourQueued by mutableStateOf(false)
    private var stackJob: Job? = null

    /** [overview] is still the server's stored copy, which may trail the diff by a push, so it says nothing about staleness. */
    var overviewStored by mutableStateOf(false)
        private set

    /** Phone-only: `diff` (the review page) or `tour` (the AI walkthrough). */
    var pageTab by mutableStateOf("diff")

    /** `rules` or `ai`: which grouping the diff shows (the header's "Analyze by" toggle). */
    var analyzeMode by mutableStateOf("ai")

    /** Commit picker selection, `from`..`to` shas (inclusive); null is all commits. */
    var selection by mutableStateOf<Pair<String, String>?>(null)
        private set

    /** A tour source tapped before the diff arrived (a kept tour opens first); it opens once the diff is in. */
    var waitingSource by mutableStateOf<TourSource?>(null)

    /** Scroll requests for the diff body; the body consumes and keeps the last one. */
    var focus by mutableStateOf<Focus?>(null)

    /** A place the Tour asked to see: that one file's diff on a sheet over the Tour, which stays where it is (L28). */
    var sourceSheet by mutableStateOf<Focus?>(null)
    var focusGroupKey by mutableStateOf<String?>(null)

    /** Tasks by id, merged across polls. Use [taskFor] to find the one a button shows. */
    val tasks = mutableStateMapOf<String, TaskState>()
    private val followers = mutableMapOf<String, Job>()

    val pull: OpenTarget.Pull? get() = target as? OpenTarget.Pull

    /** Where a pull request opened earlier in this run is kept, so opening it again shows it at once. */
    private val rememberedAs: String? = (target as? OpenTarget.Pull)?.label

    init {
        // Opened before: show what was there straight away and refresh it underneath, instead of waiting on the server.
        rememberedAs?.let { recent[it] }?.let { before ->
            prOpened = before.opened
            opened = before.opened
            overview = before.overview
        }
    }
    val key: String? get() = opened?.key
    val files: List<ReviewFile> get() = opened?.files.orEmpty()

    /** The tour this phone kept from an earlier visit ([TourStore]), read as the page opens. */
    private var storedTour by mutableStateOf(rememberedAs?.let { name -> tourStore?.get(name) })

    /**
     * The tour to show: the server's once it has answered with one, else the copy the phone kept, so a tour opened before
     * shows at once, before the diff and the server do. A commit range has tours of its own, so it shows only the server's.
     */
    val tour: Tour? get() = saved?.tour ?: storedTour.takeIf { selection == null }

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

    fun load(fresh: Boolean = false) {
        scope.launch {
            loading = true
            loadError = null
            runCatching { repo.open(target, fresh) }
                .onSuccess {
                    prOpened = it
                    patchesAsked.clear()
                    patchProblems.clear()
                    if (selection == null) { opened = it; shownSelection = null }
                    // The overview can arrive before the diff does, so this page's own copy counts too.
                    rememberedAs?.let { name -> recent[name] = Remembered(it, overview ?: recent[name]?.overview) }
                    afterOpen()
                }
                .onFailure { loadError = it.message ?: it.toString() }
            loading = false
        }
        pull?.let { reloadOverview(); reloadReviews(); loadStack() }
    }

    /** Refetches the diff, the overview and the reviews; AI results stay. A commit selection is dropped. */
    fun refresh() {
        selection = null
        load(fresh = true)
    }

    /** Paths whose left-out patches were asked for, so a file scrolled past twice asks once. */
    private val patchesAsked = HashSet<String>()

    /**
     * Left-out patches that did not arrive, by path: [PatchProblem.Moved] when the server's copy of the pull request moved
     * on since this diff opened, [PatchProblem.Failed] when asking failed. The file says so instead of waiting for ever.
     */
    val patchProblems = mutableStateMapOf<String, PatchProblem>()

    /**
     * Brings the patches the server left out of a large diff ([ReviewFile.patchOmitted]) for [paths], and puts them
     * into the diff on screen and the PR's own. A patch for another version of the file is not put in.
     */
    fun loadPatches(paths: List<String>) {
        val key = opened?.key ?: return
        // A pull request keeps its key across pushes, so the head tells an answer for this diff from one for the last.
        val head = opened?.headSha
        val wanted = paths.filter { it !in patchProblems && patchesAsked.add(it) }
        if (wanted.isEmpty()) return
        scope.launch {
            val answer = runCatching { repo.diffPatches(key, wanted) }
            if (opened?.key != key || opened?.headSha != head) {
                patchesAsked.removeAll(wanted.toSet())
                return@launch
            }
            answer
                .onSuccess { patches ->
                    val byPath = opened?.takeIf { it.key == key }?.files.orEmpty().associateBy { it.path }
                    val fitting = patches.filter { (path, patch) -> byPath[path]?.matches(patch) == true }
                    fun fill(review: OpenedReview?): OpenedReview? = review?.takeIf { it.key == key }
                        ?.let { it.copy(files = it.files.map { file -> fitting[file.path]?.let { patch -> file.withHunks(patch.hunks) } ?: file }) } ?: review
                    val before = prOpened
                    prOpened = fill(prOpened)
                    opened = if (opened === before) prOpened else fill(opened)
                    rememberedAs?.let { name -> recent[name]?.let { kept -> prOpened?.let { recent[name] = kept.copy(opened = it) } } }
                    // The server answered: a file it sent no fitting patch for is in a newer version than this diff.
                    wanted.filterNot { it in fitting }.forEach { patchProblems[it] = PatchProblem.Moved }
                }
                .onFailure {
                    // The file on screen says so; the ones ahead ask again when they are shown.
                    patchesAsked.removeAll(wanted.toSet())
                    patchProblems[wanted.first()] = PatchProblem.Failed(it.message ?: it.toString())
                }
        }
    }

    /** Asks again for a left-out patch that failed to arrive. */
    fun retryPatch(path: String) {
        patchProblems.remove(path)
        loadPatches(patchesAround(files, path))
    }

    private suspend fun afterOpen() {
        val key = opened?.key ?: return
        reloadSavedNow()
        runCatching { repo.reviewed(key) }.onSuccess { (shas, changed) -> reviewed = shas; changedSinceReviewed = changed }
        if (!hasAiResult) analyzeMode = "rules"
        loadAttention()
        resumeTasks()
    }

    /**
     * Reads what Jev said about the diff once it has opened, then again every few seconds while it is still rating
     * some files, for two minutes at most. Jev reads a diff only when a review of it starts (an analysis, Look for
     * problems, a tour or a brief), so [follow] reads again as one starts and as it ends. Jev off or a failed call
     * leaves the files unmarked and says nothing: the marks are a hint, not part of the review.
     */
    private fun loadAttention() {
        val opened = opened ?: return
        val key = opened.key
        val version = "$key@${opened.headSha}"
        if (version != attentionFor) { attention = emptyMap(); pullJev = null }
        attentionFor = version
        attentionJob?.cancel()
        attentionJob = scope.launch {
            val until = System.currentTimeMillis() + ATTENTION_FOR_MS
            while (true) {
                val answer = runCatching { repo.jevFiles(key) }.getOrNull() ?: break
                if (!answer.enabled || attentionFor != version) break
                attention = answer.files
                // A read stays until a newer one replaces it: an answer without one (Jev still sorting, or a call that
                // failed) MUST NOT take the line away and bring it back ("it appears, disappears", recording 15 Oct
                // 15:37, 03:29 to 04:12; L5). A new head starts without one, above.
                answer.pull?.let { pullJev = it }
                if (answer.pending <= 0 || System.currentTimeMillis() + ATTENTION_EVERY_MS > until) break
                delay(ATTENTION_EVERY_MS)
            }
        }
    }

    fun reloadSaved() { scope.launch { reloadSavedNow() } }

    private suspend fun reloadSavedNow() {
        val fetched = fetchSaved() ?: return
        saved = fetched.state
        keepTour(fetched)
    }

    /** Saved AI work as the server sent it, and whether it is the pull request's own rather than a commit range's. */
    private class FetchedSaved(val state: SavedState, val own: Boolean)

    /** The AI work saved on the server for [opened]; null when asking failed. */
    private suspend fun fetchSaved(): FetchedSaved? {
        val key = opened?.key ?: return null
        val own = selection == null
        return runCatching { repo.savedState(key) }.getOrNull()?.let { FetchedSaved(it, own) }
    }

    /** The pull request's own tour is kept on the phone for next time; a commit range's is not. */
    private suspend fun keepTour(fetched: FetchedSaved) {
        val json = fetched.state.tourJson
        val name = rememberedAs
        if (fetched.own && json != null && name != null && tourStore != null) {
            withContext(Dispatchers.IO) { tourStore.put(name, json) }
            fetched.state.tour?.let { storedTour = it }
        }
    }

    fun reloadOverview() {
        val pull = pull ?: return
        // Nothing on screen yet: the copy the server stored as it refreshed shows at once, until GitHub's own arrives.
        if (overview == null) scope.launch {
            runCatching { repo.storedOverview(pull) }.getOrNull()?.let { stored ->
                if (overview == null) { overview = stored; overviewStored = true }
            }
        }
        scope.launch {
            runCatching { repo.overview(pull) }.onSuccess {
                overview = it
                overviewStored = false
                overviewError = null
                rememberedAs?.let { name -> recent[name]?.let { before -> recent[name] = before.copy(overview = it) } }
            }.onFailure { overviewError = it.message }
        }
    }

    /**
     * Asked beside the overview on every load, so a refresh sees a layer that moved or left the stack; the newest ask
     * wins. A failure keeps the stack already shown, or leaves its mark off.
     */
    private fun loadStack() {
        val pull = pull ?: return
        stackJob?.cancel()
        stackJob = scope.launch { runCatching { repo.prStack(pull) }.onSuccess { stack = it } }
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

    /** The range [opened] actually shows (null: all commits), which a newer, unfinished selection has not changed yet. */
    private var shownSelection: Pair<String, String>? = null

    fun selectCommits(range: Pair<String, String>?) {
        val pull = pull ?: return
        val seq = ++selectSeq
        selection = range
        loadError = null
        if (range == null) {
            opened = prOpened
            shownSelection = null
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
            // On failure the diff on screen is still the one for [shownSelection], so the selection goes back to match it.
            result.onSuccess { opened = it; shownSelection = range; afterOpen() }.onFailure { loadError = it.message; selection = shownSelection }
            loading = false
        }
    }

    // --- AI tasks ---

    /** The newest task of [kind] (and, for `ask`, of [threadId]). */
    fun taskFor(kind: String, threadId: String? = null): TaskState? =
        tasks.values.filter { it.kind == kind && (threadId == null || it.threadId == threadId) }.maxByOrNull { it.startedAt }

    /**
     * Starts a server task and follows it; [onDone] runs after it settles and the saved
     * state has been reloaded. It keeps running on the server if the page goes away. [effort] overrides the
     * Settings effort for this task, and [then] are the passes an `analyze` makes the server start after itself.
     * The task shows on screen the moment this is called, under a placeholder id the server's own replaces.
     */
    /** When this session last started a task of each kind, on the monotonic clock in nanoseconds; see [guardedStop]. */
    private val starts = HashMap<String, Long>()

    /** When this session last started a task of one of [kinds], on the monotonic clock in nanoseconds; 0 if it never did. */
    fun lastStart(kinds: Collection<String>): Long = kinds.maxOfOrNull { starts[it] ?: 0L } ?: 0L

    fun startTask(
        kind: String,
        extra: JsonObjectBuilder.() -> Unit = {},
        effort: String? = null,
        then: List<String> = emptyList(),
        onStarted: (String) -> Unit = {},
        onDone: (TaskState) -> Unit = {},
    ) {
        val key = key ?: return
        starts[kind] = System.nanoTime()
        // In the same frame as the tap, not once the coroutine runs: a page opened with it (Generate tour opens the Tour)
        // shows the work log from its first frame, never the page from before the start (J28).
        val now = System.currentTimeMillis()
        val pending = TaskState(id = "pending-${System.nanoTime()}", kind = kind, key = key, then = then, startedAt = now)
        tasks[pending.id] = pending
        scope.launch {
            val id = runCatching {
                repo.startTask(kind, key, effort) {
                    extra()
                    if (then.isNotEmpty()) put("then", JsonArray(then.map(::JsonPrimitive)))
                }
            }.getOrElse { error ->
                // It keeps what it would have started, so a tour asked for with it says why it did not come.
                val failed = pending.copy(id = "failed-${System.nanoTime()}", status = "failed", error = error.message)
                tasks[failed.id] = failed
                tasks.remove(pending.id)
                onStarted(failed.id)
                onDone(failed)
                return@launch
            }
            // The server joins a task already doing this work; keep what the page already knows of it.
            if (tasks[id] == null) tasks[id] = pending.copy(id = id)
            tasks.remove(pending.id)
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
            // A review of the whole diff starts with Jev reading it, so its marks are read while it rates and once it has.
            val triaged = tasks[id]?.kind in JEV_TASKS
            var rated = false
            if (triaged) loadAttention()
            var settled: TaskState? = null
            while (true) {
                val current = tasks[id] ?: break
                val next = runCatching { repo.waitTask(id, current.rev) }.getOrElse { error ->
                    settled = current.copy(status = "failed", error = error.message)
                    break
                }
                val merged = if (current.label.isEmpty()) next.asTaskState().let { fresh -> fresh.copy(activities = current.merge(next).activities) } else current.merge(next)
                if (triaged && !rated && merged.activities.any { it.id == JEV_STEP && it.status != "running" }) {
                    rated = true
                    loadAttention()
                }
                if (merged.running) tasks[id] = merged else {
                    settled = merged
                    break
                }
            }
            if (triaged) loadAttention()
            // What the task wrote, and after an analysis the tour and the brief the server started (it lists them before
            // the analysis reads as done), are fetched while the task still reads as running, and land in the same frame
            // as its end. The work log then gives way straight to the tour, or to the log of the tour that follows; a
            // settled task with nothing to show yet put the Tour's empty page up for a moment (J34).
            val state = fetchSaved()
            val following = settled?.takeIf { it.kind == "analyze" && it.status == "done" }?.let { fetchTasks() }
            settled?.let { tasks[id] = it }
            state?.let { saved = it.state }
            following?.let { adoptTasks(it) }
            if (tasks[id]?.kind == "analyze" && hasAiResult) analyzeMode = "ai"
            state?.let { keepTour(it) }
            // A failure is heard when it happens, whatever else is running. Ready waits for the last of a chain
            // (the analysis, then the tour and the brief), so a chain rings once rather than once per task.
            when (tasks[id]?.status) {
                "failed" -> Sounds.play(Cue.Error, Emphasis.Subtle)
                "done" -> if (tasks.values.none { it.running }) Sounds.play(Cue.Ready, Emphasis.Subtle)
            }
            tasks[id]?.let(onDone)
        }
    }

    /** Picks up tasks already running on the server for this diff (started before a reload, or on the web). */
    private suspend fun resumeTasks() {
        fetchTasks()?.let { adoptTasks(it) }
    }

    /** The server's tasks for this diff, or null when asking failed. */
    private suspend fun fetchTasks(): List<TaskState>? {
        val key = key ?: return null
        return runCatching { repo.tasks(key) }.getOrNull()
    }

    private fun adoptTasks(list: List<TaskState>) {
        list.forEach { task ->
            if (tasks[task.id] == null) tasks[task.id] = task
            if (task.running) follow(task.id)
        }
    }

    /** Jump to a finding's line in the diff (web `critique.focus`). */
    fun focusFinding(finding: CritiqueFinding) {
        focus = Focus(finding.path, finding.line, finding.side, finding.id)
    }

    /** A pull request's diff and overview as last loaded in this run. */
    private data class Remembered(val opened: OpenedReview, val overview: JsonObject?)

    private companion object {
        const val REMEMBERED = 6
        const val ATTENTION_EVERY_MS = 3_000L
        const val ATTENTION_FOR_MS = 120_000L
        /** The tasks the server starts with Jev's read of the diff (`TRIAGED` in the server's `tasks.ts`). */
        val JEV_TASKS = setOf("analyze", "critique", "tour", "brief")
        /** The work-log step that read is (`jev-triage` in the server's `jev-review.ts`). */
        const val JEV_STEP = "jev-triage"

        // Least recently opened first; the oldest goes when a seventh arrives. Touched on the main thread only.
        val recent = object : LinkedHashMap<String, Remembered>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Remembered>?): Boolean = size > REMEMBERED
        }
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

/** Why a file's left-out patch is not on screen. */
sealed interface PatchProblem {
    /** The server's copy moved to a newer version of the pull request; loading it again shows the new one. */
    data object Moved : PatchProblem

    data class Failed(val message: String) : PatchProblem
}
