package com.octaviantocan.pulls.ui.home

import kotlinx.datetime.LocalDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** The signed-in user's year on GitHub, as the server's `github-stats` answers it (M5). Counts only. */
data class GithubStats(
    val login: String,
    val total: Int,
    val commits: Int,
    val pullRequests: Int,
    val reviews: Int,
    val streak: Int,
    val longestStreak: Int,
    /** The first day of [days], a Sunday. */
    val firstDay: LocalDate,
    /** Contributions per day, oldest first, through today. */
    val days: List<Int>,
) {
    /** Contributions in each of the last twelve months, oldest first, with each month's first day. */
    fun months(): List<Pair<LocalDate, Int>> {
        val last = firstDay.plus((days.size - 1).coerceAtLeast(0), DateTimeUnit.DAY).let { LocalDate(it.year, it.month, 1) }
        val starts = (11 downTo 0).map { last.minus(it, DateTimeUnit.MONTH) }
        val totals = IntArray(12)
        days.forEachIndexed { index, count ->
            val day = firstDay.plus(index, DateTimeUnit.DAY)
            val month = starts.indexOfLast { day >= it }
            if (month >= 0) totals[month] += count
        }
        return starts.zip(totals.toList())
    }

    companion object {
        fun parse(element: kotlinx.serialization.json.JsonElement): GithubStats? {
            val o = element as? JsonObject ?: return null
            fun int(key: String) = (o[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val first = (o["firstDay"] as? JsonPrimitive)?.content?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val days = (o["days"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
            val login = (o["login"] as? JsonPrimitive)?.content.orEmpty()
            return GithubStats(login, int("total"), int("commits"), int("pullRequests"), int("reviews"), int("streak"), int("longestStreak"), first, days)
        }
    }
}
