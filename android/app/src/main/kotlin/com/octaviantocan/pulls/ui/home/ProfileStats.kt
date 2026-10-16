package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetHeading
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The signed-in user's year on GitHub, as the server's `github-stats` answers it (M5). Counts only. */
internal data class GithubStats(
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
        val last = firstDay.plusDays((days.size - 1).toLong().coerceAtLeast(0)).withDayOfMonth(1)
        val starts = (11 downTo 0).map { last.minusMonths(it.toLong()) }
        val totals = IntArray(12)
        days.forEachIndexed { index, count ->
            val day = firstDay.plusDays(index.toLong())
            val month = starts.indexOfLast { !day.isBefore(it) }
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

/** The last answer, kept while the app runs, so the sheet opens on the numbers at once and refreshes under them. */
private object StatsMemory {
    var last: GithubStats? = null
}

/**
 * Your year on GitHub under you in the profile sheet (M5, "maybe we can see some of the stats of my GitHub, how many
 * commits I've made, with nice graphs"): the contributions as a big number over the last half year's days, the commits,
 * pull requests, reviews and streak, and the year month by month. Nothing waits on a loading screen: the last numbers
 * show at once, and a first open holds the cards' places until they come.
 */
@Composable
internal fun ColumnScope.ProfileStats(onLogin: (String) -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { Repo(context) }
    var stats by remember { mutableStateOf(StatsMemory.last) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(stats?.login) { stats?.login?.takeIf { it.isNotBlank() }?.let(onLogin) }
    LaunchedEffect(Unit) {
        runCatching { repo.call("github-stats", buildJsonObject { put("fresh", false) }) }
            // An answer with no calendar in it (an older server, an empty year) says so too, rather than leaving the
            // cards' kept places empty for good ("Profile shows no stats": recording 16 Oct 12:10, 07:34 to 07:59; N12).
            .onSuccess { result -> GithubStats.parse(result)?.let { stats = it; StatsMemory.last = it } ?: run { failed = stats == null } }
            .onFailure { failed = stats == null }
    }
    val c = LocalPullsColors.current
    SheetHeading("Last year on GitHub")
    val shown = stats
    if (shown == null && failed) {
        Text("Couldn't reach GitHub for your numbers.", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = PullsType.sm, color = c.muted)
        return
    }
    SheetCard(gap = 6.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(shown?.total?.let(::count) ?: " ", style = PullsType.xxl.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = c.text)
                Text("contributions", Modifier.padding(bottom = 4.dp), style = PullsType.sm, color = c.muted)
            }
            DayGrid(shown)
        }
    }
    SheetCard {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp)) {
            Figure(shown?.commits, "Commits", Modifier.weight(1f))
            Figure(shown?.pullRequests, "Pull requests", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 16.dp, end = 16.dp)) {
            Figure(shown?.reviews, "Reviews", Modifier.weight(1f))
            Figure(shown?.streak, if (shown == null) "Day streak" else "Day streak, longest ${shown.longestStreak}", Modifier.weight(1f))
        }
        MonthBars(shown, Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp))
    }
}

private fun count(value: Int): String = NumberFormat.getIntegerInstance().format(value)

/** One number and what it counts; before the numbers come, its place is kept empty. */
@Composable
private fun Figure(value: Int?, label: String, modifier: Modifier) {
    val c = LocalPullsColors.current
    Column(modifier) {
        Text(value?.let(::count) ?: " ", style = PullsType.xl.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = c.text, maxLines = 1)
        Text(label, style = PullsType.xs, color = c.muted, maxLines = 1)
    }
}

/** How many weeks the day grid shows: half a year, so each day is big enough to see on a phone. */
private const val GRID_WEEKS = 26

/**
 * The last [GRID_WEEKS] weeks a column each and the days of the week down them, as GitHub draws its calendar: an empty
 * day in the page's paper, a busier one in a deeper teal, each step a quarter of the busiest day.
 */
@Composable
private fun DayGrid(stats: GithubStats?) {
    val c = LocalPullsColors.current
    val days = stats?.days.orEmpty()
    // The grid starts on the Sunday GRID_WEEKS weeks back, so its columns line up with GitHub's.
    val start = (days.size - 1) / 7 * 7 - (GRID_WEEKS - 1) * 7
    val shown = if (start >= 0) days.drop(start) else emptyList()
    val busiest = shown.maxOrNull()?.coerceAtLeast(1) ?: 1
    val total = shown.sum()
    Canvas(
        Modifier.fillMaxWidth().height(116.dp).semantics { contentDescription = if (stats == null) "Contributions, not loaded yet" else "$total contributions in the last $GRID_WEEKS weeks" },
    ) {
        val gap = 3.dp.toPx()
        val cell = minOf((size.width - gap * (GRID_WEEKS - 1)) / GRID_WEEKS, (size.height - gap * 6) / 7)
        val left = (size.width - (cell * GRID_WEEKS + gap * (GRID_WEEKS - 1))) / 2
        for (week in 0 until GRID_WEEKS) for (day in 0 until 7) {
            val index = week * 7 + day
            val value = shown.getOrNull(index)
            if (stats != null && value == null) continue
            val level = if (value == null || value == 0) 0f else 0.25f + 0.75f * ((value - 1).toFloat() / busiest).coerceIn(0f, 1f)
            drawRoundRect(
                color = if (level == 0f) c.paper2 else c.accentTeal.copy(alpha = level),
                topLeft = Offset(left + week * (cell + gap), day * (cell + gap)),
                size = Size(cell, cell),
                cornerRadius = CornerRadius(cell * 0.25f),
            )
        }
    }
}

/** The year month by month as bars, this month the last and in full teal, the others lighter, the month's letter under each. */
@Composable
private fun MonthBars(stats: GithubStats?, modifier: Modifier) {
    val c = LocalPullsColors.current
    val months = stats?.months().orEmpty()
    val highest = months.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = months.joinToString { (month, total) -> "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} $total" } }) {
            val gap = 6.dp.toPx()
            val bar = (size.width - gap * 11) / 12
            for (index in 0 until 12) {
                val value = months.getOrNull(index)?.second
                val height = if (value == null) size.height * 0.3f else (size.height * value / highest).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = when {
                        value == null -> c.paper2
                        index == 11 -> c.accentTeal
                        else -> c.accentTeal.copy(alpha = 0.45f)
                    },
                    topLeft = Offset(index * (bar + gap), size.height - height),
                    size = Size(bar, height),
                    cornerRadius = CornerRadius(minOf(bar / 2, 4.dp.toPx())),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (index in 0 until 12) {
                val letter = months.getOrNull(index)?.first?.month?.getDisplayName(TextStyle.NARROW, Locale.getDefault()) ?: " "
                Text(letter, Modifier.weight(1f), style = PullsType.mini, color = c.faint, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
            }
        }
    }
}
