package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.data.ChatHistoryEntry
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
import com.octaviantocan.pulls.data.asTaskState
import com.octaviantocan.pulls.data.matches
import com.octaviantocan.pulls.data.withHunks
import com.octaviantocan.pulls.ui.design.ReviewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.random.Random

/** Where the diff should scroll: a file, optionally a line on one side, optionally a finding. */
data class Focus(val path: String, val line: Int = 0, val side: String = "additions", val findingId: String? = null, val nonce: Long = Random.nextLong())

/**
 * The state of one open review page, shared by every section of it (the web `DiffsStore`
 * plus `PrOverviewStore`, `ReviewsStore`, `CritiqueStore` and the task followers). One
 * instance per page; sections read its Compose state and call its actions.
 *
 * Ownership of the sections that read it: header and overview (`Header.kt`, `Overview.kt`,
 * `ReviewDialogs.kt`), diff body (`DiffBody.kt`, `FileDiff.kt`, `Threads.kt`), AI
 * (`ui/ai/`). Add state here only when two sections need it.
 */
class ReviewSession(
    val target: OpenTarget,
    val repo: Repo,
    scope: CoroutineScope,
    val settings: AppSettings,
    private val tourStore: SessionTourCache? = null,
    /** Where the page was opened from, for `pr.open`: inbox, search, link, repo, stack or other. */
    private val openedFrom: String = "other",
    private val cache: ReviewSessionCache,
    private val effects: SessionEffects,
    private val clock: SessionClock = SystemSessionClock,
) {
    private val source = cache.capture(repo)
    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    val scope = CoroutineScope(scope.coroutineContext + sessionJob)
    private val observations = ReviewObservation(this.scope)
    private var loadRequested = false
    private var resumeRetry: Job? = null
    private var resumeDelay = 1_000L
    private var resumeOpeningReady = true
    private var invalidated = false
    private val pageState = mutableMapOf<SessionStateKey<*>, Any>()
    private var tourRead: TourRead? = null
    private var viewRevision by mutableLongStateOf(0L)
    private var loadSequence = 0L
    private var savedSequence = 0L
    private var reviewSequence = 0L
    private var stackSequence = 0L
    private var taskNonce = 0L
    private val stopRequests = mutableMapOf<String, Any>()
    private var marksSequence = 0L

    internal data class Version(val key: String, val head: String, val revision: Long)
    private fun version(): Version? = opened?.let { Version(it.key, it.headSha, viewRevision) }
    private fun active(): Boolean = !invalidated && sessionJob.isActive && cache.current(repo, source)
    private fun current(version: Version): Boolean = active() && this.version() == version
    private fun canObserve(): Boolean = active() && observations.foreground
    private fun currentObservation(version: Version, generation: Long): Boolean = current(version) && observations.current(generation)

    /** An opaque owner token for dependent diff/file reads; this is the session's existing revision. */
    internal fun captureView(): Version? = version().takeIf { active() }
    internal fun isCurrentView(version: Version): Boolean = current(version)
    /** Snapshot observers compare the view token without capture()'s source-cache retirement side effects. */
    internal fun matchesView(version: Version): Boolean = this.version() == version

    /** Frame controls may be drawn before the diff arrives, but that opening owner retires once it does. */
    internal fun isCurrentFrame(version: Version?): Boolean = active() && this.version() == version

    internal fun wallClockMillis(): Long = clock.wallMillis()
    internal fun trackAsk(chars: Int, about: Boolean = true) {
        if (active()) effects.track("ask.send", "where" to "pr", "chars" to chars, "about" to about)
    }

    internal fun trackMergeRisk(action: String) {
        if (active() && settings.experiments.mergeRisk)
            effects.track("experiment.use", "experiment" to "merge-risk", "action" to action)
    }

    internal fun captureTourRead(): TourRead? {
        if (source == null || !active()) return null
        return tourRead?.takeIf { isCurrentTourRead(it) }
            ?: TourRead(this, version(), tour).also { tourRead = it }
    }

    internal fun isCurrentTourRead(read: TourRead?): Boolean =
        read != null && read.session === this && source != null && active() &&
            version() == read.view && tour == read.tour

    internal fun <T : Any> sourcePresentation(key: SessionStateKey<T>, create: () -> T): T? =
        if (active()) cache.presentation(repo, source, key, create) else null

    internal fun trackTour(read: TourRead?, step: String, index: Int, stops: Int? = null) {
        if (!isCurrentTourRead(read)) return
        if (stops == null) effects.track("tour.event", "step" to step, "index" to index)
        else effects.track("tour.event", "step" to step, "index" to index, "stops" to stops)
    }

    private fun showDiff(value: OpenedReview?) {
        if (opened?.let { it.key to it.headSha } != value?.let { it.key to it.headSha }) {
            viewRevision++
            followers.values.toList().forEach { it.cancel() }
            followers.clear()
            taskCompletions.clear()
            pendingOutcomes.clear()
            tasks.clear()
            stopRequests.clear()
            taskObservationErrors.clear()
            saved = null
            reviewed = emptySet()
            changedSinceReviewed = emptySet()
            attention = emptyMap()
            pullJev = null
            attentionFor = null
            attentionJob?.cancel()
            chatHistory = null
            chatHistoryFor = null
            chatHistoryError = null
            chatHistoryLoading = false
            chatHistoryJob?.cancel()
        }
        opened = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> pageState(key: SessionStateKey<T>, create: () -> T): T = pageState.getOrPut(key, create) as T

    private fun remembered(): RememberedReview? = rememberedAs?.let { cache.remembered(repo, source, it) }
    private fun remember(value: RememberedReview) { rememberedAs?.let { cache.remember(repo, source, it, value) } }

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

    /** Whether [waitingSource] was asked for on a sheet over the Tour, so it opens there too. */
    var waitingInSheet = false

    /** A New chat or a restored chat on its way to the server: a question waits for it, so it lands in the new chat. */
    var chatChange: kotlinx.coroutines.Job? = null

    /**
     * The chats this pull request had before the open one, newest first (L36): null until the server answered. The Ask
     * bars offer their earlier-chats button only when there is one ("if there's no earlier chats, you can just hide it",
     * recording of 17 Oct 13:26, 01:26 to 01:43, P4).
     */
    var chatHistory by mutableStateOf<List<ChatHistoryEntry>?>(null)
        private set
    var chatHistoryError by mutableStateOf<String?>(null)
        private set
    var chatHistoryLoading by mutableStateOf(false)
        private set
    private var chatHistoryFor: String? = null
    private var chatHistoryJob: Job? = null
    private var chatHistorySequence = 0L

    /**
     * Asks the server for [chatHistory] until it answers once per diff; [force] again, after a chat was put away or
     * brought back. A newer request cancels the one before, so a late answer never puts back an older list, and a failed
     * one keeps the list there was rather than reading as no chats.
     */
    fun loadChatHistory(force: Boolean = false) {
        if (!canObserve()) return
        val expected = version() ?: return
        val generation = observations.generation
        val key = expected.key
        if (!force && chatHistoryFor == key) return
        if (!force && chatHistoryLoading) return
        if (chatHistoryFor != key) chatHistory = null
        chatHistoryJob?.cancel()
        val attempt = ++chatHistorySequence
        chatHistoryError = null
        chatHistoryLoading = true
        chatHistoryJob = observations.scope.launch {
            try {
                val answer = runCatching { repo.chatHistory(key) }
                if (!isActive || !currentObservation(expected, generation) || attempt != chatHistorySequence) return@launch
                answer.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                answer.fold(onSuccess = { list ->
                    chatHistory = list
                    chatHistoryFor = key
                }, onFailure = {
                    chatHistoryError = "Pully could not load earlier chats. Try again."
                })
            } finally {
                if (currentObservation(expected, generation) && attempt == chatHistorySequence) chatHistoryLoading = false
            }
        }
    }

    /** Scroll requests for the diff body; the body consumes and keeps the last one. */
    var focus by mutableStateOf<Focus?>(null)

    /** The comment or review a link pointed at: the Conversation opens it, scrolls to it and marks it (`landOn`). */
    var spot by mutableStateOf<LinkSpot?>(null)

    /** The code review thread a link pointed at, by its first comment: it opens on the diff even resolved or hidden. */
    var linkedThread by mutableStateOf<Long?>(null)

    /** The check run a link pointed at: the Checks sheet marks its row. */
    var linkedCheck by mutableStateOf<Long?>(null)

    /** A place the Tour asked to see: that one file's diff on a sheet over the Tour, which stays where it is (L28). */
    var sourceSheet by mutableStateOf<Focus?>(null)
    var focusGroupKey by mutableStateOf<String?>(null)

    /** Tasks by id, merged across polls. Use [taskFor] to find the one a button shows. */
    val tasks = mutableStateMapOf<String, TaskState>()
    /** A lost observation is not an authoritative failed task or an outcome notification. */
    val taskObservationErrors = mutableStateMapOf<String, String>()
    private val followers = mutableMapOf<String, Job>()
    private class TaskCompletion(val version: Version, val callback: (TaskState) -> Unit)
    private val taskCompletions = mutableMapOf<String, MutableList<TaskCompletion>>()
    private val pendingOutcomes = mutableSetOf<String>()

    val pull: OpenTarget.Pull? get() = target as? OpenTarget.Pull

    /** Where a pull request opened earlier in this run is kept, so opening it again shows it at once. */
    private val rememberedAs: String? = (target as? OpenTarget.Pull)?.label

    init {
        // Opened before: show what was there straight away and refresh it underneath, instead of waiting on the server.
        remembered()?.let { before ->
            prOpened = before.opened
            opened = before.opened
            overview = before.overview
            overviewStored = before.overview != null
        }
    }
    val key: String? get() = opened?.key
    val files: List<ReviewFile> get() = opened?.files.orEmpty()

    /** The tour this phone kept from an earlier visit ([SessionTourCache]), read as the page opens. */
    private var storedTour by mutableStateOf(if (source != null) rememberedAs?.let { name ->
        try { tourStore?.get(name) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    } else null)

    /**
     * The tour to show: the server's once it has answered with one, else the copy the phone kept, so a tour opened before
     * shows at once, before the diff and the server do. A commit range has tours of its own, so it shows only the server's.
     */
    val tour: Tour? get() = saved?.tour ?: storedTour.takeIf { selection == null }

    /** Stale tour versions already offered an update while this review stays open. */
    val promptedStaleTours = mutableSetOf<com.octaviantocan.pulls.ui.ai.StaleTourIdentity>()

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

    /** `pr.open` goes once a page, when its diff first arrives and its size is known. */
    private var openTracked = false

    /** When each file's diff was first shown on this page (monotonic ms), for `file.reviewed`'s seconds. */
    private val shownAt = HashMap<String, Long>()

    fun load(fresh: Boolean = false) {
        loadRequested = true
        if (!canObserve()) return
        val request = ++loadSequence
        val generation = observations.generation
        val shouldRefresh = fresh || !observations.ready
        observations.scope.launch {
            loading = true
            if (observations.ready) loadError = null
            val started = clock.monotonicMillis()
            val hadCopy = opened != null
            val warming = if (shouldRefresh) null else rememberedAs?.let { cache.warming(repo, source, it) }
            val answer = runCatching { warming?.await() ?: repo.open(target, shouldRefresh) }
            if (!isActive || !active() || !observations.current(generation) || request != loadSequence) return@launch
            answer.onSuccess {
                loadError = null
                resumeOpeningReady = true
                effects.track("pr.load", "ms" to clock.monotonicMillis() - started, "cached" to hadCopy, "stale" to it.stale)
                trackOpen(it)
                prOpened = it
                patchesAsked.clear()
                patchProblems.clear()
                if (selection == null) {
                    showDiff(it)
                    shownSelection = null
                }
                remember(RememberedReview(it, overview ?: remembered()?.overview))
                if (selection != shownSelection) {
                    selectCommits(selection)
                    return@launch
                }
                val reconciled = afterOpen()
                if (isActive && active() && observations.current(generation) && request == loadSequence) {
                    if (reconciled) finishResume(generation)
                    else if (!observations.ready) {
                        loadError = "Saved review work is unavailable. Trying again."
                        retryResume(generation)
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                loadError = it.message ?: it.toString()
                effects.error("pr.load", it.message)
                retryResume(generation)
            }
            if (isActive && active() && observations.current(generation) && request == loadSequence) loading = false
        }
        pull?.let { reloadOverview(); reloadReviews(); loadStack() }
    }

    private fun trackOpen(review: OpenedReview) {
        val pull = pull ?: return
        if (openTracked) return
        openTracked = true
        effects.track(
            "pr.open",
            "repo" to "${pull.owner}/${pull.repo}", "number" to pull.number, "files" to review.files.size,
            "additions" to review.files.sumOf { it.additions }, "deletions" to review.files.sumOf { it.deletions }, "from" to openedFrom,
        )
    }

    /** A file's diff is on screen (`file.view`): its extension only, how many lines its patch has, and the layout. */
    fun fileShown(file: ReviewFile) {
        if (!active()) return
        shownAt.getOrPut(file.path) { clock.monotonicMillis() }
        effects.fileShown(file.path, file.patchLines.size, settings.layout)
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
        if (!canObserve()) return
        val expected = version() ?: return
        val key = expected.key
        // A pull request keeps its key across pushes, so the head tells an answer for this diff from one for the last.
        val head = opened?.headSha
        val wanted = paths.filter { it !in patchProblems && patchesAsked.add(it) }
        if (wanted.isEmpty()) return
        observations.scope.launch {
            val answer = runCatching { repo.diffPatches(key, wanted) }
            if (!isActive || !current(expected) || opened?.headSha != head) return@launch
            answer
                .onSuccess { patches ->
                    val byPath = opened?.takeIf { it.key == key }?.files.orEmpty().associateBy { it.path }
                    val fitting = patches.filter { (path, patch) -> byPath[path]?.matches(patch) == true }
                    fun fill(review: OpenedReview?): OpenedReview? = review?.takeIf { it.key == key }
                        ?.let { it.copy(files = it.files.map { file -> fitting[file.path]?.let { patch -> file.withHunks(patch.hunks) } ?: file }) } ?: review
                    val before = prOpened
                    prOpened = fill(prOpened)
                    opened = if (opened === before) prOpened else fill(opened)
                    remembered()?.let { kept -> prOpened?.let { remember(kept.copy(opened = it)) } }
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

    private suspend fun afterOpen(): Boolean {
        val expected = version() ?: return false
        val generation = observations.generation
        if (!currentObservation(expected, generation)) return false
        val saved = reloadSavedNow()
        if (!currentObservation(expected, generation)) return false
        val marksRequest = ++marksSequence
        val marks = runCatching { repo.reviewed(expected.key) }.getOrNull()
        if (!currentObservation(expected, generation)) return false
        if (marksRequest == marksSequence) marks?.let { (shas, changed) -> reviewed = shas; changedSinceReviewed = changed }
        if (!hasAiResult) analyzeMode = "rules"
        loadAttention()
        return resumeTasks() && saved
    }

    /**
     * Reads what Jev said about the diff once it has opened, then again every few seconds while it is still rating
     * some files, for two minutes at most. Jev reads a diff only when a review of it starts (an analysis, Look for
     * problems, a tour or a brief), so [follow] reads again as one starts and as it ends. Jev off or a failed call
     * leaves the files unmarked and says nothing: the marks are a hint, not part of the review.
     */
    private fun loadAttention() {
        if (!canObserve()) return
        val expected = version() ?: return
        val key = expected.key
        val version = "$key@${expected.head}"
        if (version != attentionFor) { attention = emptyMap(); pullJev = null }
        attentionFor = version
        attentionJob?.cancel()
        attentionJob = observations.scope.launch {
            val until = clock.monotonicMillis() + ATTENTION_FOR_MS
            while (true) {
                val answer = runCatching { repo.jevFiles(key) }.getOrNull() ?: break
                if (!isActive || !current(expected) || !answer.enabled || attentionFor != version) break
                attention = answer.files
                // A read stays until a newer one replaces it: an answer without one (Jev still sorting, or a call that
                // failed) MUST NOT take the line away and bring it back ("it appears, disappears", recording 15 Oct
                // 15:37, 03:29 to 04:12; L5). A new head starts without one, above.
                answer.pull?.let { pullJev = it }
                if (answer.pending <= 0 || clock.monotonicMillis() + ATTENTION_EVERY_MS > until) break
                delay(ATTENTION_EVERY_MS)
            }
        }
    }

    fun reloadSaved() { observations.scope.launch { reloadSavedNow() } }

    /** Fetches the saved AI work again and shows it; false when asking failed and the old work still shows. */
    suspend fun reloadSavedNow(reloadHistory: Boolean = true): Boolean {
        val fetched = fetchSaved() ?: return false
        saved = fetched.state
        keepTour(fetched)
        if (!currentObservation(fetched.version, fetched.generation)) return false
        // A New chat or a chat brought back changes which chats are earlier ones.
        if (reloadHistory && chatHistoryFor != null) loadChatHistory(force = true)
        return true
    }

    /** An accepted chat change can finish while suspended; its read and navigation wait for this exact view's resume. */
    internal suspend fun reloadSavedWhenObserved(expected: Version, reloadHistory: Boolean = true): Boolean {
        while (current(expected)) {
            observations.awaitReady()
            if (!current(expected)) return false
            val generation = observations.generation
            // This read belongs to the foreground generation even when its accepted mutation belongs to the lifetime.
            val read = observations.scope.async { reloadSavedNow(reloadHistory) }
            val reloaded = try {
                read.await()
            } catch (cancelled: CancellationException) {
                read.cancel()
                currentCoroutineContext().ensureActive()
                if (observations.current(generation) && observations.ready) throw cancelled
                continue
            }
            if (observations.current(generation) && observations.ready) return reloaded
        }
        return false
    }

    /** Saved AI work as the server sent it, and whether it is the pull request's own rather than a commit range's. */
    private class FetchedSaved(val state: SavedState, val own: Boolean, val version: Version, val generation: Long)

    /** The AI work saved on the server for [opened]; null when asking failed. */
    private suspend fun fetchSaved(): FetchedSaved? {
        val expected = version() ?: return null
        val generation = observations.generation
        if (!currentObservation(expected, generation)) return null
        val request = ++savedSequence
        val own = selection == null
        val answer = runCatching { repo.savedState(expected.key) }.getOrNull() ?: return null
        currentCoroutineContext().ensureActive()
        if (!currentObservation(expected, generation) || request != savedSequence) return null
        return FetchedSaved(answer, own, expected, generation)
    }

    /** The PR's own tour is persisted by its native, identity-bound cache; commit ranges are not. */
    private suspend fun keepTour(fetched: FetchedSaved) {
        if (!currentObservation(fetched.version, fetched.generation)) return
        val json = fetched.state.tourJson
        val name = rememberedAs
        if (fetched.own && json != null && name != null && tourStore != null) {
            try {
                tourStore.put(name, json)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A phone cache is optional; the server result remains usable if persistence fails.
            }
            if (currentObservation(fetched.version, fetched.generation)) fetched.state.tour?.let { storedTour = it }
        }
    }

    /**
     * Each reload is numbered as it is asked for, and an answer is as new as its ask: one asked later saw GitHub at
     * least as late. [freshText] is the newest `pr-text` answer, [shownText] the ask whose title and description are
     * on screen (0: a kept copy) and [shownFull] the ask of the whole overview on screen, so an answer that arrives
     * late never puts back what a newer one replaced.
     */
    private var asks = 0L
    private var freshText: JsonObject? = null
    private var freshTextAsk = 0L
    private var shownText = 0L
    private var shownFull = 0L
    private var overviewAsk: Job? = null
    private var overviewAskedAt = 0L

    fun reloadOverview() {
        if (!canObserve()) return
        val pull = pull ?: return
        val ask = ++asks
        // The title and description alone answer well before the whole overview, so one edited since the copy on
        // screen was fetched (an agent just changed it, then sent the link) is in place at once rather than after
        // GitHub's slow answer, which kept the old one up ("the description was not updated", Tavi, 16 Oct 13:52).
        observations.scope.launch {
            val text = runCatching { repo.prText(pull) }.getOrNull() ?: return@launch
            if (!isActive || !active() || ask <= freshTextAsk) return@launch
            freshText = text
            freshTextAsk = ask
            if (ask > shownText) withText(overview, text)?.let { showOverview(it); shownText = ask }
        }
        // Nothing on screen yet: the copy the server stored as it refreshed shows at once, until GitHub's own arrives.
        if (overview == null) observations.scope.launch {
            runCatching { repo.storedOverview(pull) }.getOrNull()?.let { stored ->
                if (!isActive || !active() || overview != null) return@let
                val withFresh = withText(stored, freshText)
                overview = withFresh ?: stored
                shownText = if (withFresh != null) freshTextAsk else 0
                overviewStored = true
            }
        }
        // One asked for a moment ago is still the newest there is: coming back to the app and the link that brought it
        // back both ask, and one answer serves the two.
        val now = clock.monotonicMillis()
        if (overviewAsk?.isActive == true && now - overviewAskedAt < OVERVIEW_ASKED_RECENTLY_MS) return
        overviewAskedAt = now
        overviewAsk = observations.scope.launch {
            runCatching { repo.overview(pull) }.onSuccess {
                if (!isActive || !active() || ask < shownFull) return@onSuccess
                // A newer title and description than this answer's stay on screen over it.
                val newer = freshText.takeIf { freshTextAsk > ask }?.let { text -> withText(it, text) }
                showOverview(newer ?: it)
                shownFull = ask
                shownText = if (newer != null) freshTextAsk else ask
                overviewStored = false
                overviewError = null
            }.onFailure { if (isActive && active()) overviewError = it.message }
        }
    }

    private fun showOverview(value: JsonObject) {
        overview = value
        remembered()?.let { before -> remember(before.copy(overview = value)) }
    }

    /**
     * Asked beside the overview on every load, so a refresh sees a layer that moved or left the stack; the newest ask
     * wins. A failure keeps the stack already shown, or leaves its mark off.
     */
    private fun loadStack() {
        if (!canObserve()) return
        val pull = pull ?: return
        val request = ++stackSequence
        stackJob?.cancel()
        stackJob = observations.scope.launch {
            val answer = runCatching { repo.prStack(pull) }.getOrNull()
            if (isActive && active() && request == stackSequence) answer?.let { stack = it }
        }
    }

    fun reloadReviews() {
        if (!canObserve()) return
        val pull = pull ?: return
        val request = ++reviewSequence
        observations.scope.launch {
            val answer = runCatching { repo.reviewData(pull) }.getOrNull()
            if (isActive && active() && request == reviewSequence) answer?.let { reviewData = it }
        }
    }

    fun reviewState(file: ReviewFile): ReviewState = when {
        file.sha in reviewed -> ReviewState.Reviewed
        file.path in changedSinceReviewed -> ReviewState.Changed
        else -> ReviewState.Unreviewed
    }

    /** Marks or unmarks files by blob sha; either way clears their "changed since reviewed" flag (web `setReviewed`). */
    fun setReviewed(shas: Collection<String>, value: Boolean) {
        if (!active()) return
        marksSequence++
        val key = key ?: return
        if (value) {
            // `file.reviewed`: the file's place in the list and how long since its diff first showed, never its name.
            val now = clock.monotonicMillis()
            files.forEachIndexed { index, file ->
                if (file.sha in shas && file.sha !in reviewed) {
                    effects.track("file.reviewed", "index" to index, "seconds" to shownAt[file.path]?.let { (now - it) / 1000 })
                }
            }
        }
        reviewed = if (value) reviewed + shas else reviewed - shas.toSet()
        val paths = files.filter { it.sha in shas }.map { it.path }.toSet()
        changedSinceReviewed = changedSinceReviewed - paths
        scope.launch { if (active()) runCatching { repo.setReviewed(key, shas, value) } }
    }

    /** Narrows the diff to commits `from..to` of the PR (web `CommitPicker`); null restores all commits. */
    private var selectSeq = 0

    /** The range [opened] actually shows (null: all commits), which a newer, unfinished selection has not changed yet. */
    private var shownSelection: Pair<String, String>? = null

    fun selectCommits(range: Pair<String, String>?) {
        if (!canObserve()) return
        val pull = pull ?: return
        val seq = ++selectSeq
        val generation = observations.generation
        selection = range
        loadError = null
        if (range == null) {
            showDiff(prOpened)
            shownSelection = null
            observations.scope.launch { if (afterOpen()) finishResume(generation) else retryResume(generation) }
            return
        }
        val narrowed = if (range.first == range.second) OpenTarget.Commit(pull.owner, pull.repo, range.first)
        else OpenTarget.Compare(pull.owner, pull.repo, "${range.first}^", range.second)
        observations.scope.launch {
            loading = true
            val result = runCatching { repo.open(narrowed) }
            // A newer selection (fast previous/next taps) wins; drop this answer.
            if (!isActive || !active() || seq != selectSeq) return@launch
            // On failure the diff on screen is still the one for [shownSelection], so the selection goes back to match it.
            result.onSuccess {
                showDiff(it)
                shownSelection = range
                if (afterOpen()) finishResume(generation) else retryResume(generation)
            }.onFailure {
                if (it is CancellationException) throw it
                loadError = it.message
                selection = shownSelection
                if (shownSelection == null) showDiff(prOpened)
                if (!observations.ready) {
                    if (afterOpen()) finishResume(generation) else retryResume(generation)
                }
            }
            if (isActive && active() && observations.current(generation) && seq == selectSeq) loading = false
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
    /** When this session last started a task of each kind, on the session's monotonic clock in nanoseconds; see [guardedStop]. */
    private val starts = HashMap<String, Long>()

    /** When this session last started a task of one of [kinds], on the session's monotonic clock in nanoseconds; 0 if it never did. */
    fun lastStart(kinds: Collection<String>): Long = kinds.maxOfOrNull { starts[it] ?: 0L } ?: 0L

    fun millisSinceLastStart(kinds: Collection<String>): Long =
        if (kinds.none { it in starts }) Long.MAX_VALUE else clock.monotonicMillis() - lastStart(kinds) / 1_000_000

    fun startTask(
        kind: String,
        extra: JsonObjectBuilder.() -> Unit = {},
        effort: String? = null,
        then: List<String> = emptyList(),
        onStarted: (String) -> Unit = {},
        onDone: (TaskState) -> Unit = {},
    ) {
        val expected = version() ?: return
        if (!current(expected)) return
        starts[kind] = clock.monotonicMillis() * 1_000_000
        // The optimistic placeholder appears in the same frame as Generate, before the request can suspend.
        val pending = TaskState(id = "pending-${++taskNonce}", kind = kind, key = expected.key, then = then, startedAt = clock.wallMillis())
        tasks[pending.id] = pending
        scope.launch {
            val workSource = effects.workSource()
            val answer = runCatching {
                repo.startTask(kind, expected.key, effort) {
                    extra()
                    if (then.isNotEmpty()) put("then", JsonArray(then.map(::JsonPrimitive)))
                }
            }
            if (!isActive || !current(expected)) return@launch
            val id = answer.getOrElse { error ->
                if (error is CancellationException) throw error
                val failed = pending.copy(id = "failed-${++taskNonce}", status = "failed", error = error.message)
                tasks[failed.id] = failed
                tasks.remove(pending.id)
                onStarted(failed.id)
                onDone(failed)
                return@launch
            }
            if (tasks[id] == null) tasks[id] = pending.copy(id = id)
            tasks.remove(pending.id)
            taskCompletions.getOrPut(id) { mutableListOf() }.add(TaskCompletion(expected, onDone))
            pendingOutcomes += id
            tasks[id]?.let { effects.started(it, workSource) }
            if (!current(expected)) return@launch
            onStarted(id)
            if (current(expected)) follow(id)
        }
    }

    fun cancelTask(id: String, beforeDispatch: () -> Boolean = { true }, onError: ((String) -> Unit)? = null) {
        if (onError == null) {
            if (!active()) return
            scope.launch { if (active() && beforeDispatch()) repo.cancelTask(id) }
            return
        }
        val expected = version() ?: return
        if (!current(expected)) return
        scope.launch {
            if (!current(expected) || !beforeDispatch() || !current(expected)) return@launch
            val request = Any()
            stopRequests[id] = request
            try {
                repo.cancelTask(id, bestEffort = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current(expected) && stopRequests[id] === request && tasks[id]?.running == true)
                    onError(failure.message ?: "Pully could not stop this task. Try again.")
            } finally {
                if (stopRequests[id] === request) stopRequests.remove(id)
            }
        }
    }

    /** Stops foreground reads only. The lifetime scope, accepted writes and server work are retained. */
    internal fun pauseObservations() {
        observations.pause()
        resumeOpeningReady = false
        resumeDelay = 1_000L
        followers.clear()
        chatHistoryLoading = false
    }

    internal fun resumeObservations() {
        if (!active() || !observations.resume()) return
        if (loadRequested) load(fresh = true)
        else { resumeOpeningReady = true; finishResume(observations.generation) }
    }

    private fun finishResume(generation: Long) {
        if (!active() || !resumeOpeningReady || !observations.reconciled(generation)) return
        val expected = version() ?: return
        resumeDelay = 1_000L
        tasks.values.toList().forEach { task ->
            if (!currentObservation(expected, generation)) return
            if (task.running) follow(task.id)
            else if (task.status in TERMINAL_TASK_STATUSES && task.id in pendingOutcomes) {
                // Saved results were read after this terminal list, so a paused live callback can settle exactly once.
                effects.updated(task, effects.workSource())
                if (!currentObservation(expected, generation)) return
                when (task.status) {
                    "failed" -> effects.cue(SessionCue.Error)
                    "done" -> if (tasks.values.none { it.running }) effects.cue(SessionCue.Ready)
                }
                completeTask(task)
            }
        }
    }

    private fun retryResume(generation: Long) {
        if (!active() || !observations.current(generation) || observations.ready || resumeRetry?.isActive == true) return
        val wait = resumeDelay
        resumeDelay = (resumeDelay * 2).coerceAtMost(30_000L)
        resumeRetry = observations.scope.launch {
            delay(wait)
            resumeRetry = null
            if (active() && observations.current(generation) && !observations.ready) load(fresh = true)
        }
    }

    /** Closing a route detaches observations; the server's work keeps running. */
    fun detach() {
        sessionJob.cancel()
        followers.clear()
        taskCompletions.clear()
        pendingOutcomes.clear()
        stopRequests.clear()
        pageState.clear()
        tourRead = null
        cache.release(this)
    }

    /** Identity invalidation also removes private presentation state from an existing screen. */
    internal fun invalidate() {
        invalidated = true
        detach()
        opened = null
        prOpened = null
        saved = null
        storedTour = null
        overview = null
        overviewError = null
        overviewStored = false
        reviewData = null
        reviewed = emptySet()
        changedSinceReviewed = emptySet()
        attention = emptyMap()
        pullJev = null
        stack = null
        chatHistory = null
        chatHistoryError = null
        chatHistoryLoading = false
        focus = null
        spot = null
        linkedThread = null
        linkedCheck = null
        sourceSheet = null
        focusGroupKey = null
        waitingSource = null
        tasks.clear()
        taskObservationErrors.clear()
        patchProblems.clear()
        patchesAsked.clear()
        promptedStaleTours.clear()
        shownAt.clear()
        loading = false
        loadError = null
    }

    private fun follow(id: String) {
        if (!active() || !observations.ready || followers[id]?.isActive == true) return
        val expected = version() ?: return
        val generation = observations.generation
        if (tasks[id]?.key != expected.key) return
        pendingOutcomes += id
        val follower = observations.scope.launch {
            val triaged = tasks[id]?.kind in JEV_TASKS
            var rated = false
            if (triaged) loadAttention()
            var settled: TaskState? = null
            var retryDelay = 1_000L
            suspend fun reconnect(message: String) {
                taskObservationErrors[id] = message
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
            while (isActive && currentObservation(expected, generation)) {
                val before = tasks[id] ?: return@launch
                if (before.status in TERMINAL_TASK_STATUSES) {
                    effects.updated(before, effects.workSource())
                    settled = before
                    break
                }
                val workSource = effects.workSource()
                val answer = runCatching { repo.waitTask(id, before.rev) }
                if (!isActive || !currentObservation(expected, generation)) return@launch
                // A task-list refresh may have advanced this task while its poll was suspended.
                val latest = tasks[id] ?: return@launch
                if (latest.status in TERMINAL_TASK_STATUSES) {
                    effects.updated(latest, workSource)
                    settled = latest
                    break
                }
                val next = answer.getOrNull()
                if (next == null) {
                    val error = answer.exceptionOrNull()!!
                    if (error is CancellationException) throw error
                    // Failure to observe says nothing about the remote task's outcome.
                    reconnect(error.message ?: "Task observation interrupted")
                    continue
                }
                val fresh = next.asTaskState()
                if ((fresh.id.isNotEmpty() && fresh.id != id) || (fresh.key.isNotEmpty() && fresh.key != expected.key)) {
                    reconnect("Task response belongs to a different review")
                    continue
                }
                if (fresh.status !in KNOWN_TASK_STATUSES) {
                    reconnect("Task status is not yet recognized")
                    continue
                }
                val revision = (next["rev"] as? JsonPrimitive)?.intOrNull ?: latest.rev
                if (revision < latest.rev) {
                    delay(100)
                    continue
                }
                val merged = if (latest.label.isEmpty()) fresh.copy(
                    id = id, key = expected.key, kind = fresh.kind.ifBlank { latest.kind },
                    activities = latest.merge(next).activities,
                ) else latest.merge(next)
                retryDelay = 1_000L
                taskObservationErrors.remove(id)
                effects.updated(merged, workSource)
                if (triaged && !rated && merged.activities.any { it.id == JEV_STEP && it.status != "running" }) {
                    rated = true
                    loadAttention()
                }
                if (merged.running) tasks[id] = merged else {
                    settled = merged
                    break
                }
            }
            if (!isActive || !currentObservation(expected, generation)) return@launch
            if (triaged) loadAttention()
            // A terminal poll does not consume its callback until actual results and server-owned passes reconcile.
            while (isActive && currentObservation(expected, generation)) {
                val state = fetchSaved()
                if (!isActive || !currentObservation(expected, generation)) return@launch
                if (state == null) {
                    reconnect("Saved task results are unavailable")
                    continue
                }
                val needsFollowing = settled?.let { it.kind == "analyze" && it.status == "done" } == true
                val following = if (needsFollowing) fetchTasks() else null
                if (!isActive || !currentObservation(expected, generation)) return@launch
                if (needsFollowing && following == null) {
                    reconnect("Following analysis tasks are unavailable")
                    continue
                }
                val latest = tasks[id]
                if (latest?.running == true && settled != null && latest.rev > settled.rev) {
                    // Refresh may also advance a task while its saved result is being fetched.
                    observations.scope.launch { delay(100); if (current(expected)) follow(id) }
                    return@launch
                }
                settled?.let { terminal ->
                    if (latest == null || latest.status !in TERMINAL_TASK_STATUSES || latest.rev < terminal.rev) tasks[id] = terminal
                }
                taskObservationErrors.remove(id)
                saved = state.state
                following?.let { adoptTasks(it) }
                if (tasks[id]?.kind == "analyze" && hasAiResult) analyzeMode = "ai"
                keepTour(state)
                if (!isActive || !currentObservation(expected, generation)) return@launch
                when (tasks[id]?.status) {
                    "failed" -> effects.cue(SessionCue.Error)
                    "done" -> if (tasks.values.none { it.running }) effects.cue(SessionCue.Ready)
                }
                tasks[id]?.takeIf { it.status in TERMINAL_TASK_STATUSES }?.let(::completeTask)
                return@launch
            }
        }
        followers[id] = follower
        follower.invokeOnCompletion { if (followers[id] === follower) followers.remove(id) }
    }

    private fun completeTask(task: TaskState) {
        pendingOutcomes.remove(task.id)
        taskCompletions.remove(task.id).orEmpty().forEach { completion ->
            if (current(completion.version)) completion.callback(task)
        }
    }

    private suspend fun resumeTasks(): Boolean {
        val snapshot = fetchTasks() ?: return false
        // A task can finish between the earlier saved-state read and this list. Read its outputs after that outcome.
        if (snapshot.tasks.any { it.status in TERMINAL_TASK_STATUSES } && !reloadSavedNow()) return false
        if (!currentObservation(snapshot.version, snapshot.generation)) return false
        adoptTasks(snapshot)
        return true
    }

    private class TaskSnapshot(val tasks: List<TaskState>, val workSource: SessionWorkSource?, val version: Version, val generation: Long)

    private suspend fun fetchTasks(): TaskSnapshot? {
        val expected = version() ?: return null
        val generation = observations.generation
        if (!currentObservation(expected, generation)) return null
        val workSource = effects.workSource()
        val list = runCatching { repo.tasks(expected.key) }.getOrNull() ?: return null
        return if (currentObservation(expected, generation)) TaskSnapshot(list, workSource, expected, generation) else null
    }

    private fun adoptTasks(snapshot: TaskSnapshot) {
        if (!currentObservation(snapshot.version, snapshot.generation)) return
        snapshot.tasks.filter { it.key == snapshot.version.key && it.id.isNotBlank() }.forEach { task ->
            val previous = tasks[task.id]
            if (previous != null && (task.rev < previous.rev || (previous.status in TERMINAL_TASK_STATUSES && task.running))) return@forEach
            if (task.status !in KNOWN_TASK_STATUSES) {
                // Keep controls attached to this work until a recognized server outcome arrives.
                if (previous?.status in TERMINAL_TASK_STATUSES) return@forEach
                tasks[task.id] = previous ?: task.copy(status = "running", rev = 0)
                taskObservationErrors[task.id] = "Task status is not yet recognized"
                follow(task.id)
                return@forEach
            }
            val merged = if (previous == null) task else task.copy(
                activities = (previous.activities + task.activities).associateBy { it.id }.values.sortedBy { it.startedAt },
            )
            if (merged.running) effects.updated(merged, snapshot.workSource)
            tasks[task.id] = merged
            if (merged.running || merged.id in pendingOutcomes) follow(merged.id)
        }
    }

    /** Jump to a finding's line in the diff (web `critique.focus`). */
    fun focusFinding(finding: CritiqueFinding) {
        if (!active()) return
        focus = Focus(finding.path, finding.line, finding.side, finding.id)
    }

    init {
        if (cache.current(repo, source)) cache.attach(this) else invalidate()
    }

    companion object {
        const val WARM_TOP = ReviewSessionCache.WARM_TOP
        private const val ATTENTION_EVERY_MS = 3_000L
        private const val OVERVIEW_ASKED_RECENTLY_MS = 2_000L
        private const val ATTENTION_FOR_MS = 120_000L
        private val JEV_TASKS = setOf("analyze", "critique", "tour", "brief")
        private const val JEV_STEP = "jev-triage"
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

private val TERMINAL_TASK_STATUSES = setOf("done", "failed", "cancelled")
private val KNOWN_TASK_STATUSES = TERMINAL_TASK_STATUSES + "running"
