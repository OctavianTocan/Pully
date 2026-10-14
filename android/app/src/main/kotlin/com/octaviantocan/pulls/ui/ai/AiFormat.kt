package com.octaviantocan.pulls.ui.ai

/*
 * Port of `components/ai/ai-format.ts`: durations, effort names and verb tenses the AI surfaces
 * show (token counts and costs stay off them, rule 98). Pure Kotlin, unit-tested in `AiFormatTest`.
 */

/** Looks up a UI message (the shape of vue-i18n's `t` these helpers need). */
typealias Translate = (key: String, named: Map<String, Any?>) -> String

/** Effort ids the UI has a proper name for; anything else shows its capitalised id. */
val NAMED_EFFORTS = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/** A compact wall-clock duration: `45s`, `3m 12s`, `1h 5m`, `2d 3h` (negative reads as zero). */
fun formatClockDuration(ms: Long): String {
    val total = maxOf(0L, Math.floorDiv(ms, 1000L))
    val days = total / 86_400
    val hours = total % 86_400 / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/** The display name of a reasoning effort: `ai.effort.<id>` for named ones, else the capitalised id. */
fun effortLabel(id: String, translate: Translate): String =
    if (id in NAMED_EFFORTS) translate("ai.effort.$id", emptyMap()) else id.replaceFirstChar { it.uppercase() }

private val HTML_COMMENT = Regex("<!--[\\s\\S]*?-->")
private val HEADING = Regex("^\\s*#+\\s*")
private val MARKS = Regex("\\*\\*|__|`")

/** The newest non-empty line of streamed text, without Markdown heading, emphasis or code marks. */
fun lastLine(text: String): String {
    val lines = text.replace(HTML_COMMENT, "").split('\n')
    for (i in lines.indices.reversed()) {
        val line = lines[i].replace(HEADING, "").replace(MARKS, "").trim()
        if (line.isNotEmpty()) return line
    }
    return ""
}

/** `ai.verb.<verb>.now` while running, else `ai.verb.<verb>.past`. */
fun verbKey(verb: String, status: String): String = "ai.verb.$verb.${if (status == "running") "now" else "past"}"
