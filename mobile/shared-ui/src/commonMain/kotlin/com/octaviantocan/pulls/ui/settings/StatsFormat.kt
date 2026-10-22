package com.octaviantocan.pulls.ui.settings

import kotlin.math.roundToLong

/** How the Stats page writes its numbers. */
object StatsFormat {
    fun count(value: Long, locale: String): String = statsCount(value, locale)

    /** Dollars to the cent; a spend under a cent that is not nothing says so rather than reading as nothing. */
    fun usd(value: Double): String = when {
        value <= 0.0 -> "$0.00"
        value < 0.01 -> "<$0.01"
        else -> "$" + statsDecimal(value, 2, true)
    }

    /** Tokens as a short count: 950, 12.4k, 3.1M. */
    fun tokens(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> statsDecimal(value / 1_000.0, if (value < 100_000) 1 else 0, false) + "k"
        else -> statsDecimal(value / 1_000_000.0, 1, false) + "M"
    }

    /** What a run was for, from the server's one word, as the feature it belongs to. */
    fun feature(task: String): String = when (task) {
        "tour" -> "Tours"
        "brief" -> "Summaries"
        "analyze" -> "Analysis"
        "review" -> "AI review"
        "ask" -> "Ask"
        "chat" -> "Conversation"
        else -> task.replaceFirstChar { it.titlecase() }
    }

    /** A time in seconds: whole seconds under a minute, whole minutes under an hour, then hours to a tenth. */
    fun seconds(value: Double): String = when {
        value < 60 -> "${statsRound(value)} s"
        value < 3_600 -> "${statsRound(value / 60)} min"
        else -> statsDecimal(value / 3_600, 1, false) + " h"
    }

    /** A duration in milliseconds: whole milliseconds under a second, then seconds to a tenth. */
    fun ms(value: Double): String = if (value < 1_000) "${statsRound(value)} ms" else statsDecimal(value / 1_000, 1, false) + " s"

    /** `2026-10-15` as the day and the month in the app's language. */
    fun day(value: String, locale: String): String = statsDay(value, locale)

    /** A screen's fixed name (`home`, `settings-stats`) as words: `Home`, `Settings stats`. */
    fun screen(name: String): String = name.replace('-', ' ').replaceFirstChar { it.titlecase() }
}

internal expect fun statsCount(value: Long, locale: String): String
internal expect fun statsDecimal(value: Double, decimals: Int, grouping: Boolean): String
internal expect fun statsDay(value: String, locale: String): String
private fun statsRound(value: Double): Long = if (value.isNaN()) 0L else value.roundToLong()
