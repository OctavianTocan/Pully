package com.octaviantocan.pulls.core

/** The server work represented by the one ongoing Android notification. */
data class WorkNotice(val title: String, val lines: List<String>, val startedAt: Long?)

const val WORK_NOTICE_FRESH_MS = 30_000L

/** A complete, fresh server snapshot bound to the account/server that requested it. */
class WorkNoticeState<S> {
    class Source<S> internal constructor(internal val identity: S)

    private var source: Source<S>? = null
    private var observedAt: Long? = null
    private var tasks = emptyList<WorkTask>()

    /** Changing source invalidates every earlier in-flight request, even if the source changes back. */
    fun capture(identity: S): Source<S> {
        if (source?.identity != identity) {
            clear()
            source = Source(identity)
        }
        return source!!
    }

    fun accepts(token: Source<S>?, identity: S): Boolean = token != null && token === source && token.identity == identity

    /** Page activity may ask for a refresh, but cannot renew any task's freshness or elapsed timer. */
    fun activity(task: WorkTask, token: Source<S>?, identity: S): Boolean =
        isNotifiedWork(task.kind) && accepts(token, identity)

    fun replace(tasks: List<WorkTask>, token: Source<S>?, identity: S, now: Long): Boolean {
        if (!accepts(token, identity)) return false
        this.tasks = tasks
        observedAt = now
        return true
    }

    fun notice(now: Long): WorkNotice? = observedAt?.takeIf { now - it in 0 until WORK_NOTICE_FRESH_MS }
        ?.let { workNotice(tasks, now) }

    fun clear() {
        source = null
        observedAt = null
        tasks = emptyList()
    }
}

/** Questions and chat replies stay in their composer; whole-diff work can continue after leaving it. */
fun isNotifiedWork(kind: String): Boolean = kind in setOf("analyze", "tour", "brief", "critique")

/** No estimated progress: the system chronometer starts at the oldest running task's actual server time. */
fun workNotice(tasks: List<WorkTask>, now: Long): WorkNotice? {
    val running = tasks.filter { it.status == "running" && isNotifiedWork(it.kind) }.distinctBy { it.id }
        .sortedWith(compareBy<WorkTask> { it.startedAt }.thenBy { it.id })
    if (running.isEmpty()) return null
    fun action(task: WorkTask): String = when (task.kind) {
        "tour" -> "Writing tour"
        "brief" -> "Writing brief"
        "critique" -> "Reviewing pull request"
        else -> "Analyzing pull request"
    }
    fun subject(task: WorkTask): String = task.title?.takeIf { it.isNotBlank() }
        ?: parsePullKey(task.key)?.let { "${it.owner}/${it.repo} #${it.number}" }
        ?: "Pully"
    return WorkNotice(
        title = if (running.size == 1) action(running.first()) else "${running.size} AI tasks running",
        lines = running.map { task -> if (running.size == 1) subject(task) else "${action(task)} · ${subject(task)}" },
        // Missing or future timestamps have no trustworthy elapsed time.
        startedAt = running.minOf { it.startedAt }.takeIf { it in 1..now },
    )
}
