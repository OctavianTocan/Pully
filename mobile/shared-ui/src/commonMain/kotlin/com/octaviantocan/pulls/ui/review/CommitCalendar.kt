package com.octaviantocan.pulls.ui.review

import kotlinx.datetime.LocalDate

internal expect fun currentCommitDays(commits: List<PrCommit>): List<Pair<String, List<Int>>>

/** Original contiguous day grouping: an undated commit follows the previous day, else Undated. */
internal fun groupCommitDays(commits: List<PrCommit>, dayLabel: (Long) -> String): List<Pair<String, List<Int>>> {
    val days = mutableListOf<Pair<String, MutableList<Int>>>()
    commits.forEachIndexed { index, commit ->
        val label = prIsoMillis(commit.committedDate)?.let(dayLabel) ?: days.lastOrNull()?.first ?: "Undated"
        if (days.lastOrNull()?.first == label) days.last().second.add(index) else days.add(label to mutableListOf(index))
    }
    return days
}

/** Android's existing Locale.ENGLISH patterns, using the same ISO calendar on Native. */
internal fun commitDayLabel(day: LocalDate, today: LocalDate): String {
    val ago = today.toEpochDays().toLong() - day.toEpochDays().toLong()
    val weekday = COMMIT_WEEKDAYS[day.dayOfWeek.ordinal]
    val month = COMMIT_MONTHS[day.month.ordinal]
    return when {
        ago == 0L -> "Today"
        ago == 1L -> "Yesterday"
        ago in 2L..6L -> weekday
        day.year == today.year -> "${weekday.take(3)} ${day.dayOfMonth} $month"
        else -> "${day.dayOfMonth} $month ${day.year}"
    }
}

private val COMMIT_WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val COMMIT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
