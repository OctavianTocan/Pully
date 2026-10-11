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

/** The server opens every job with a `start` status; a later settled status row is a notice (rate limit, retry). */
fun isWarning(activity: Activity, index: Int): Boolean =
    activity.kind == "status" && activity.status == "done" && activity.id != "start" && index > 0
