package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.data.AskTurn
import com.octaviantocan.pulls.data.InboxChat
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.data.asTaskState
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Required native edges for the original Inbox Ask workflow. Content never enters analytics. */
interface InboxAskEffects {
    fun nowMillis(): Long
    fun track(name: String, vararg props: Pair<String, Any?>)
    fun feedback(cue: Cue, emphasis: Emphasis)
}

/**
 * The original Inbox Ask conversation, shared by Home and both hosts. One instance belongs to one
 * native source. The root calls [invalidate] before publishing replacement credentials.
 */
@Stable
class InboxAsk(
    private val repo: Repo,
    parentScope: CoroutineScope,
    private val settings: AppSettings,
    internal val native: NativeHomeActions,
    private val effects: InboxAskEffects,
    private val current: () -> Boolean,
    private val capabilities: () -> AiCapabilities,
    internal val observations: HomeObservations,
) : HomeAskSurface {
    private val source = runCatching { repo.source() }.getOrNull()
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var retired = false
    private var epoch = 0L
    private var readSequence = 0L
    private var stopRequest: Any? = null
    private var observation = 0L
    private var observing = false
    private var loader: Job? = null
    private var follower: Job? = null
    private var observationSubscription: HomeLifecycleSubscription? = null
    private val mounts = mutableSetOf<InboxAskMount>()

    private var saved by mutableStateOf<InboxChat?>(null)
    private var followed by mutableStateOf<TaskState?>(null)
    private var sent by mutableStateOf<String?>(null)
    private var sentAt by mutableStateOf<Long?>(null)
    private var problem by mutableStateOf<String?>(null)
    private var observationProblem by mutableStateOf<String?>(null)
    private var draft by mutableStateOf("")
    private var clearing by mutableStateOf(false)
    private var acceptedQuestion: InboxAcceptedQuestion? = null
    internal var revision by mutableLongStateOf(0L)
        private set

    init {
        observationSubscription = observations.observe { ready ->
            if (!retired) {
                if (!ready) stopReading()
                else if (mounts.isNotEmpty()) reconcileObservation()
            }
        }
    }

    internal val owned: Boolean get() {
        val opening = source
        if (!retired && (!lifetime.isActive || opening == null || !runCatching { current() && repo.isCurrent(opening) }.getOrDefault(false))) invalidate()
        return !retired
    }
    internal val canRead: Boolean get() = owned && capabilities().canReadResults
    internal val canAsk: Boolean get() = canRead && observations.ready && capabilities().canAsk
    val chat: InboxChat? get() = if (owned) saved else null
    val task: TaskState? get() = if (owned) followed else null
    val pending: String? get() = if (owned) sent else null
    val pendingStartedAt: Long? get() = if (owned) sentAt else null
    val error: String? get() = if (owned) problem else null
    val observationError: String? get() = if (owned) observationProblem else null
    val input: String get() = if (owned) draft else ""
    val busy: Boolean get() = owned && (sent != null || followed?.running == true)
    val accepting: Boolean get() = canAsk && !busy && !clearing

    /** Source retirement is synchronous; dismissing a sheet uses its mount instead. */
    override fun invalidate() {
        if (retired) return
        retired = true
        epoch++; revision++; readSequence++
        saved = null; followed = null; sent = null; sentAt = null
        problem = null; observationProblem = null; draft = ""; clearing = false; acceptedQuestion = null
        stopRequest = null
        observationSubscription?.cancel(); observationSubscription = null
        mounts.toList().forEach { it.close() }
        mounts.clear()
        stopReading()
        scope.cancel()
    }

    override fun sendOrQueue(question: String) {
        sendOrQueue(question) { true }
    }

    internal fun sendOrQueue(question: String, permitted: () -> Boolean) {
        if (!canAsk || !permitted() || question.isBlank()) return
        if (accepting) ask(question, permitted = permitted) else draft = question
    }

    @Composable
    override fun Content(listed: List<ListedPull>, onOpen: (ListedPull) -> Unit, onClose: () -> Unit) =
        InboxAskSheet(this, listed, onOpen, onClose)

    internal fun nowMillis(): Long = effects.nowMillis()
    internal fun setInput(value: String) { if (canAsk) draft = value }
    internal fun sendInput(permitted: () -> Boolean) {
        val text = draft
        ask(text, submittedDraft = text, permitted = permitted)
    }
    internal fun retry(turn: AskTurn, permitted: () -> Boolean) {
        if (saved?.turns?.lastOrNull() == turn) ask(turn.question, retry = true, permitted = permitted)
    }

    /** A write owns the source scope, so a sheet/background pause cannot cancel an accepted start. */
    private fun ask(question: String, retry: Boolean = false, submittedDraft: String? = null, permitted: () -> Boolean): Boolean {
        val text = question.trim()
        if (text.isEmpty() || !accepting || !permitted()) return false
        val request = ++epoch
        revision++
        stopReading()
        val locale = settings.locale
        val skipped = settings.hiddenRepos.sorted()
        val startedAt = effects.nowMillis()
        val before = saved
        val beforeQuestion = acceptedQuestion
        effects.track("ask.send", "where" to "inbox", "chars" to text.length, "retry" to retry)
        if (!owns(request) || !canAsk || !permitted()) return false
        // Main.immediate can complete the write and restore unsent text before launch returns.
        if (submittedDraft != null && draft == submittedDraft) draft = ""
        problem = null; observationProblem = null; sent = text; sentAt = startedAt
        acceptedQuestion = null
        saved = saved?.let { it.copy(turns = if (retry) it.turns.dropLast(1) else it.turns, suggestions = emptyList()) }
        scope.launch {
            try {
                if (!owns(request)) return@launch
                if (!canAsk || !permitted()) {
                    saved = before
                    acceptedQuestion = beforeQuestion
                    if (draft.isBlank()) draft = text
                    return@launch
                }
                val id = repo.startTask("inbox", "inbox") {
                    put("locale", locale); put("question", text)
                    if (retry) put("retry", true)
                    put("skip", JsonArray(skipped.map(::JsonPrimitive)))
                }
                currentCoroutineContext().ensureActive()
                if (!owns(request)) return@launch
                followed = TaskState(id = id, kind = "inbox", key = "inbox", startedAt = startedAt, text = text)
                // A response is a write receipt. Only a visible observer may read the saved conversation next.
                acceptedQuestion = InboxAcceptedQuestion(request, id, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!owns(request)) return@launch
                saved = before
                acceptedQuestion = beforeQuestion
                problem = failure.message ?: "Request failed"
                if (draft.isBlank()) draft = text
                feedback(Cue.Error)
            } finally {
                if (owns(request)) {
                    sent = null; sentAt = null
                    stopReading()
                    reconcileObservation()
                }
            }
        }
        return true
    }

    internal fun stop(drawn: TaskState?, permitted: () -> Boolean) {
        if (!canAsk || !permitted() || drawn == null || followed?.id != drawn.id || followed?.running != true) return
        val request = epoch
        scope.launch {
            val stop = Any()
            try {
                if (!owns(request) || !canAsk || !permitted() || followed?.id != drawn.id || followed?.running != true) return@launch
                stopRequest = stop
                problem = null
                repo.cancelTask(drawn.id, bestEffort = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (owns(request) && stopRequest === stop && followed?.id == drawn.id && followed?.running == true) {
                    problem = failure.message ?: "Request failed"
                    feedback(Cue.Error)
                }
            } finally {
                if (stopRequest === stop) stopRequest = null
            }
        }
    }

    internal fun clear(permitted: () -> Boolean) {
        if (!canAsk || !permitted() || busy || clearing) return
        val request = ++epoch
        val before = saved
        val beforeQuestion = acceptedQuestion
        revision++
        stopReading()
        followed = null; sent = null; sentAt = null; problem = null; observationProblem = null; acceptedQuestion = null
        saved = InboxChat(emptyList(), emptyList())
        clearing = true
        scope.launch {
            try {
                if (!owns(request)) return@launch
                if (!canAsk || !permitted()) { saved = before; acceptedQuestion = beforeQuestion; return@launch }
                repo.clearInboxChat()
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (owns(request)) { saved = before; acceptedQuestion = beforeQuestion; problem = failure.message ?: "Could not start a new chat" }
            } finally {
                if (owns(request)) {
                    clearing = false
                    stopReading()
                    reconcileObservation()
                }
            }
        }
    }

    private fun owns(request: Long): Boolean = owned && epoch == request

    private suspend fun refresh(request: Long, token: Long): Boolean {
        if (!owns(request) || !observes(token)) return false
        val sequence = ++readSequence
        return try {
            val value = observations.read { repo.inboxChat() }
            currentCoroutineContext().ensureActive()
            if (!owns(request) || sequence != readSequence || !observes(token)) false
            else {
                if (saved?.turns?.map { it.taskId to it.question } != value.turns.map { it.taskId to it.question }) revision++
                if (saved == null) problem = null
                saved = value
                acceptedQuestion?.takeIf { it.request == request }?.let { receipt ->
                    value.turns.lastOrNull { it.taskId == receipt.id }?.let { turn ->
                        // The server may have returned its existing running answer instead of accepting this question.
                        if (turn.question != receipt.question && draft.isBlank()) draft = receipt.question
                        acceptedQuestion = null
                    }
                }
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (owns(request) && sequence == readSequence && observes(token)) {
                if (saved == null) problem = failure.message ?: "Could not load the conversation"
                observationProblem = failure.message ?: "Could not load the conversation"
            }
            false
        }
    }

    internal fun mount(mount: InboxAskMount) { if (owned) mounts += mount }
    internal fun unmount(mount: InboxAskMount) { mounts -= mount; reconcileObservation() }
    internal fun reconcileObservation() {
        val visible = owned && mounts.any { it.observing }
        if (!visible) { stopReading(); return }
        if (!observing) { observing = true; load() }
        else if (sent == null && !clearing && loader?.isActive != true && followed?.running == true) follow(followed!!.id)
    }
    private fun observes(token: Long): Boolean = observing && observation == token && canRead && mounts.any { it.observing }
    private fun stopReading() {
        observing = false; observation++
        loader?.cancel(); loader = null
        follower?.cancel(); follower = null
    }
    internal fun retryObservation() { if (canRead) { stopReading(); reconcileObservation() } }

    /** Read jobs are disposable. The accepted start/clear jobs above are deliberately separate. */
    private fun load() {
        if (!observing || sent != null || clearing || loader?.isActive == true) return
        val request = epoch
        val token = observation
        loader = scope.launch {
            try {
                var refreshed = refresh(request, token)
                if (!owns(request) || !observes(token) || sent != null || clearing) return@launch
                saved?.turns?.lastOrNull()?.takeIf { it.answer == null && it.outcome == null && !it.taskId.isNullOrEmpty() }?.let { waiting ->
                    if (followed?.id != waiting.taskId) followed = TaskState(
                        id = waiting.taskId!!, kind = "inbox", key = "inbox", startedAt = waiting.startedAt ?: 0, text = waiting.question,
                    )
                }
                val tasks = observations.read { repo.allTasks() }
                currentCoroutineContext().ensureActive()
                if (!owns(request) || !observes(token)) return@launch
                val live = tasks.firstOrNull { it.id.isNotEmpty() && it.kind == "inbox" && it.key == "inbox" && it.running }
                val known = live ?: tasks.firstOrNull { it.id == followed?.id && it.kind == "inbox" && it.key == "inbox" && it.status in INBOX_TASK_STATUSES }
                if (known != null && !(known.id == followed?.id && followed?.running == false && known.running) &&
                    (known.id != followed?.id || known.rev >= (followed?.rev ?: 0))) followed = known
                if (live == null && saved?.turns?.lastOrNull()?.let { it.outcome == null && it.answer == null } == true) refreshed = refresh(request, token) || refreshed
                if (!owns(request) || !observes(token)) return@launch
                val last = saved?.turns?.lastOrNull()
                if (followed?.running == true && last != null && last.taskId == followed?.id && (last.answer != null || last.outcome != null)) followed = null
                if (refreshed) observationProblem = null
                followed?.takeIf { it.running }?.let { follow(it.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (owns(request) && observes(token)) {
                    observationProblem = failure.message.orEmpty()
                    followed?.takeIf { it.running }?.let { follow(it.id) }
                }
            }
        }
    }

    private fun follow(id: String) {
        if (follower?.isActive == true || !observing) return
        val request = epoch
        val token = observation
        follower = scope.launch {
            var retryDelay = 1_000L
            while (isActive && owns(request) && observes(token)) {
                val before = followed?.takeIf { it.id == id && it.running } ?: return@launch
                try {
                    val next = observations.read { repo.waitTask(id, before.rev) }
                    currentCoroutineContext().ensureActive()
                    if (!owns(request) || !observes(token)) return@launch
                    val latest = followed?.takeIf { it.id == id && it.running } ?: return@launch
                    val fresh = next.asTaskState()
                    val status = (next["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val rev = (next["rev"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw InboxObservationUnavailable()
                    if (!(fresh.id == id && fresh.key == "inbox" && fresh.kind == "inbox" &&
                        status in INBOX_TASK_STATUSES && rev >= 0)) throw InboxObservationUnavailable()
                    if (rev < latest.rev) { delay(100); continue }
                    val merged = if (latest.label.isEmpty()) fresh.copy(
                        id = id, kind = "inbox", key = "inbox", startedAt = fresh.startedAt.takeIf { it > 0 } ?: latest.startedAt,
                        text = fresh.text ?: latest.text, activities = latest.merge(next).activities,
                    ) else latest.merge(next)
                    observationProblem = null; retryDelay = 1_000L
                    followed = merged
                    if (!merged.running) {
                        refresh(request, token)
                        if (owns(request) && observes(token)) when (merged.status) {
                            "failed" -> feedback(Cue.Error)
                            "done" -> feedback(Cue.Ready)
                        }
                        return@launch
                    }
                    if (merged == latest) delay(100)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!owns(request) || !observes(token)) return@launch
                    observationProblem = failure.message.orEmpty()
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun feedback(cue: Cue) { if (canRead && mounts.any { it.observing }) effects.feedback(cue, Emphasis.Subtle) }
}

private val INBOX_TASK_STATUSES = setOf("running", "done", "failed", "cancelled")
private class InboxObservationUnavailable : IllegalStateException()
private data class InboxAcceptedQuestion(val request: Long, val id: String, val question: String)
