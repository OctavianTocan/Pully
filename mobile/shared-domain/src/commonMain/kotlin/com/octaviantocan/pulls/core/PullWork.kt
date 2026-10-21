package com.octaviantocan.pulls.core

/** The part of a server task the Inbox cares about. `startedAt` is epoch milliseconds; `id` is what stops it. */
data class WorkTask(val key: String, val kind: String, val status: String, val title: String? = null, val startedAt: Long = 0, val id: String = "")

/** What the server is doing for one pull request right now, or finished doing lately. */
enum class PullWork { Analyzed, Analyzing }

/** The AI passes that make a pull request "being analyzed"; a question or a chat reply is not one. */
private val ANALYSIS_KINDS = setOf("analyze", "tour", "brief", "critique")

/** `github:owner/repo#number`, the key the server files a pull request's analysis under. */
fun pullKey(owner: String, repo: String, number: Int): String = "github:$owner/$repo#$number"

/** The pull request a task key names, or `null` for a compare, a commit or a local diff. */
data class PullRef(val owner: String, val repo: String, val number: Int)

fun parsePullKey(key: String): PullRef? {
    val match = Regex("""^github:([^/]+)/([^#@]+)#(\d+)$""").matchEntire(key) ?: return null
    val (owner, repo, number) = match.destructured
    return PullRef(owner, repo, number.toInt())
}

/**
 * Per pull request: [PullWork.Analyzing] while any of its passes runs, [PullWork.Analyzed] when its newest analysis
 * finished and nothing runs. A pull request whose newest analysis failed or was stopped has no mark, even if an older
 * run of it succeeded.
 */
fun pullWorkByKey(tasks: List<WorkTask>): Map<String, PullWork> {
    val result = mutableMapOf<String, PullWork>()
    val byKey = tasks.filter { it.kind in ANALYSIS_KINDS }.groupBy { it.key }
    for ((key, group) in byKey) {
        val newestAnalysis = group.filter { it.kind == "analyze" }.maxByOrNull { it.startedAt }
        when {
            group.any { it.status == "running" } -> result[key] = PullWork.Analyzing
            newestAnalysis?.status == "done" -> result[key] = PullWork.Analyzed
        }
    }
    return result
}

/** What a pull request's running tasks are doing, in the words the Inbox shows: "Analyzing", "Writing the tour"… */
fun runningWork(tasks: List<WorkTask>, key: String): List<String> = tasks
    .filter { it.key == key && it.status == "running" }
    .sortedBy { it.startedAt }
    .mapNotNull {
        when (it.kind) {
            "analyze" -> "Analyzing"
            "tour" -> "Writing the tour"
            "brief" -> "Designing the brief"
            "critique" -> "Reviewing"
            else -> null
        }
    }
    .distinct()

/** The id of the newest running task of [kind] for [key], or `null` when none runs. */
fun runningTaskId(tasks: List<WorkTask>, key: String, kind: String): String? =
    tasks.filter { it.key == key && it.kind == kind && it.status == "running" && it.id.isNotEmpty() }.maxByOrNull { it.startedAt }?.id
