package com.octaviantocan.pulls.ui.review

internal actual fun currentCommitDays(commits: List<PrCommit>): List<Pair<String, List<Int>>> =
    commitDays(commits, java.time.LocalDate.now(), java.time.ZoneId.systemDefault())

/**
 * The commits by the day they were committed, oldest first, as (heading, indexes into [commits]): "Today",
 * "Yesterday", the weekday within a week, else the date. Commits with no date go with the day before them.
 */
fun commitDays(commits: List<PrCommit>, today: java.time.LocalDate, zone: java.time.ZoneId): List<Pair<String, List<Int>>> {
    return groupCommitDays(commits) { millis ->
        dayLabel(java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(), today)
    }
}

private fun dayLabel(day: java.time.LocalDate, today: java.time.LocalDate): String {
    val ago = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    val locale = java.util.Locale.ENGLISH
    return when {
        ago == 0L -> "Today"
        ago == 1L -> "Yesterday"
        ago in 2..6 -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
        day.year == today.year -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", locale))
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
}
