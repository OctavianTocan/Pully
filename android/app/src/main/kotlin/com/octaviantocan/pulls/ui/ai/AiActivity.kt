package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.data.Activity

/*
 * Port of `components/ai/ai-activity.ts`: the work log as the page tracks it, liveness
 * of running steps, and the "Ran 3 commands, Read 4 files" summary. Pure Kotlin,
 * unit-tested in `AiActivityTest`. (`activitiesFromTranscript` / `usageFromTranscript`
 * are not ported: the phone only ever sees server task snapshots, never an agent transcript.)
 */

/** A work-log entry plus its revision and when this page last saw it change (client clock). */
data class TrackedActivity(val activity: Activity, val rev: Int, val seenAt: Long) {
    val id: String get() = activity.id
}

/** How long a running step may stay silent before the log calls it idle. */
const val IDLE_AFTER_MS = 30_000L

/**
 * Merges a batch into the log: unknown entries append, a known one is replaced only when
 * its revision is newer.
 */
fun upsertActivities(log: List<TrackedActivity>, incoming: List<Pair<Activity, Int>>, now: Long = System.currentTimeMillis()): List<TrackedActivity> {
    val next = log.toMutableList()
    val indexById = next.withIndex().associate { it.value.id to it.index }.toMutableMap()
    for ((activity, rev) in incoming) {
        val index = indexById[activity.id]
        if (index == null) {
            indexById[activity.id] = next.size
            next += TrackedActivity(activity, rev, now)
        } else if (rev > next[index].rev) {
            next[index] = TrackedActivity(activity, rev, now)
        }
    }
    return next
}

/**
 * Tracks a list of snapshots that carry no revision (the phone's merged task activities):
 * an entry whose content changed counts as a newer revision.
 */
fun trackActivities(log: List<TrackedActivity>, current: List<Activity>, now: Long = System.currentTimeMillis()): List<TrackedActivity> {
    val byId = log.associateBy { it.id }
    val incoming = current.map { activity ->
        val known = byId[activity.id]
        activity to when {
            known == null -> 0
            known.activity != activity -> known.rev + 1
            else -> known.rev
        }
    }
    val merged = upsertActivities(log, incoming, now)
    // Keep the server's order (by start), not first-seen order, so a resumed log reads the same.
    val order = current.withIndex().associate { it.value.id to it.index }
    return merged.filter { it.id in order }.sortedBy { order[it.id] }
}

enum class TimingState { Idle, Now, Active }

data class ActivityTiming(val state: TimingState, val since: String, val elapsed: String)

/** How lively a running step is, for its "Active 4s ago · 38s elapsed" line; null once settled. */
fun activityTiming(tracked: TrackedActivity, now: Long): ActivityTiming? {
    if (tracked.activity.status != "running") return null
    val idle = maxOf(0L, now - tracked.seenAt)
    val state = when {
        idle >= IDLE_AFTER_MS -> TimingState.Idle
        idle < 1000 -> TimingState.Now
        else -> TimingState.Active
    }
    return ActivityTiming(state, formatClockDuration(idle), formatClockDuration(now - tracked.activity.startedAt))
}

/** In `TOOL_CATEGORIES` order. */
enum class ToolCategory(val key: String) { Command("command"), Edit("edit"), Read("read"), Search("search"), Other("other") }

// Codex reports the program a shell step ran (`cat`, `rg`, `git`) as its tool name.
private val CATEGORY_BY_TOOL: Map<String, ToolCategory> = buildMap {
    listOf("read_source", "read_file_diff", "read_hunks", "read_diffs", "read", "cat", "sed", "head", "tail", "nl").forEach { put(it, ToolCategory.Read) }
    listOf("search_diff", "search_source", "web_search", "find_files", "search", "grep", "rg", "find", "glob", "ls", "list", "list_files").forEach { put(it, ToolCategory.Search) }
    listOf("bash", "shell", "run", "exec", "command", "git", "gh", "pnpm", "npm", "npx", "node", "python", "python3", "make", "jq", "wc").forEach { put(it, ToolCategory.Command) }
    listOf("edit", "write", "apply_patch").forEach { put(it, ToolCategory.Edit) }
}

/** What kind of work a tool does; `Other` for tools that don't read, search, edit or run anything. */
fun toolCategory(tool: String?): ToolCategory = tool?.let { CATEGORY_BY_TOOL[it.lowercase()] } ?: ToolCategory.Other

/** Counts a log's tool steps by kind, one entry per kind that occurred, in [ToolCategory] order. */
fun summarizeTools(activities: List<Activity>): List<Pair<ToolCategory, Int>> {
    val counts = mutableMapOf<ToolCategory, Int>()
    activities.filter { it.kind == "tool" }.forEach { counts[toolCategory(it.tool)] = (counts[toolCategory(it.tool)] ?: 0) + 1 }
    return ToolCategory.entries.filter { it in counts }.map { it to counts.getValue(it) }
}

/**
 * The title a step shows. A log saved before the server stopped naming the model opens with "Started Claude (<model id>)";
 * it reads "Started" like a new one, since the reader does not want the model there (F58).
 */
fun stepTitle(activity: Activity): String =
    if (activity.id == "start" && activity.kind == "status" && activity.title.startsWith("Started ")) "Started" else activity.title

/** The server opens every job with a `start` status; a later settled status row is a notice (rate limit, retry). */
fun isWarning(activity: Activity, index: Int): Boolean =
    activity.kind == "status" && activity.status == "done" && index > 0 && WARNING_IDS.any { activity.id.startsWith(it) }

/**
 * The status lines that are notices: a retry, a rate limit, an error the engine reported. A step of the work itself,
 * such as "Planned 6 stops", is a plain line, never amber (K13).
 */
private val WARNING_IDS = listOf("retry", "rate-limit", "status:")

/**
 * The answer as it is being written (rule 99): the text of the newest writing step, as long as no tool or thinking step
 * came after it (a status line, such as writing the follow-up questions, may). Null while the model reads or thinks,
 * so an aside it wrote between two tool calls does not stand in for the answer once it moves on.
 */
fun answerSoFar(activities: List<Activity>): String? {
    val last = activities.lastOrNull { it.kind != "status" } ?: return null
    if (last.kind != "text" || last.status == "failed") return null
    // The server keeps the end of a long text and marks the cut with an ellipsis.
    return last.text?.removePrefix("…")?.trim()?.takeIf { it.isNotEmpty() }
}

/** "Read src/a.ts", "Read src/a.ts (lines 10–40)", "Read the diff of src/a.ts and 2 more files": the file, and the first line read. */
private val READ_TITLE = Regex("""^Read(?:ing)? (?:the diff of )?(\S+?)(?: and \d+ more files?)?(?: \(lines (\d+)[–-]\d+\))?$""")

/**
 * The files a log's read steps read, in the order it first read each, with the line its first read started at (0 for
 * the top): the sources an answer stands on besides the places it names (rule 99). Searches, failed reads and reads
 * of no single file ("Read the PR description and comments") are left out.
 */
fun readFiles(activities: List<Activity>): List<Pair<String, Int>> {
    val read = LinkedHashMap<String, Int>()
    for (activity in activities) {
        if (activity.kind != "tool" || activity.status == "failed" || toolCategory(activity.tool) != ToolCategory.Read) continue
        val match = READ_TITLE.matchEntire(activity.title.trim()) ?: continue
        val path = match.groupValues[1].trim('`', '"', '\'').removePrefix("./")
        if ('#' in path || ('.' !in path && '/' !in path)) continue
        read.getOrPut(path) { match.groupValues[2].toIntOrNull()?.takeIf { it > 1 } ?: 0 }
    }
    return read.map { (path, line) -> path to line }
}

/** The tools that read one pull request of the inbox, whose step names it: "Read owner/repo#12". */
private val PULL_READS = setOf("read_pull_request", "read_pull_request_diff")

/** What a log's settled reads of pull requests say they read, one per line, for the inbox to find the pull requests in. */
fun pullReads(activities: List<Activity>): String =
    activities.filter { it.kind == "tool" && it.status != "failed" && it.tool in PULL_READS }.joinToString("\n") { it.title }
