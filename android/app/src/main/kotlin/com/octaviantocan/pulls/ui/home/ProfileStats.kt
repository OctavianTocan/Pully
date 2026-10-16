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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
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

/**
 * The last answer, kept on the phone, so the sheet opens on the numbers at once, after a restart too, and refreshes
 * under them; [warm] asks for them as Pulls starts, so even a first open does not wait ("this took a long time to load
 * ... it should be loaded immediately": recording 16 Oct 13:06, 02:56 to 03:04; O6).
 */
internal object StatsMemory {
    private const val PREFS = "pulls-github-stats"
    private const val KEY = "last"
    private const val SERVER = "server"
    private var asked = false
    /** The server [last] came from: another server may be signed in to another GitHub account. */
    private var server: String? = null
    var last by mutableStateOf<GithubStats?>(null)
        private set

    /** The numbers kept for the server Pulls talks to now, read from the phone the first time and after a switch. */
    fun load(context: android.content.Context): GithubStats? {
        val url = ServerStore.load(context).url
        if (server != url) {
            server = url
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY, null)?.takeIf { prefs.getString(SERVER, null) == url }
            last = raw?.let { runCatching { GithubStats.parse(kotlinx.serialization.json.Json.parseToJsonElement(it)) }.getOrNull() }
        }
        return last
    }

    /** Asks the server for the numbers and keeps them; false when it could not, or its answer had no calendar. */
    suspend fun refresh(context: android.content.Context, repo: Repo): Boolean {
        val url = ServerStore.load(context).url
        return runCatching { repo.call("github-stats", buildJsonObject { put("fresh", false) }) }
            .map { result ->
                val stats = GithubStats.parse(result) ?: return@map false
                // An answer that arrives after a switch to another server is not kept for the new one.
                if (url == ServerStore.load(context).url) {
                    server = url
                    last = stats
                    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                        .putString(KEY, result.toString()).putString(SERVER, url).apply()
                }
                true
            }.getOrDefault(false)
    }

    /** Once per launch, in the background: the profile then has today's numbers before it is opened. */
    suspend fun warm(context: android.content.Context) {
        load(context)
        if (asked) return
        asked = true
        refresh(context, Repo(context))
    }
}

/**
 * Your year on GitHub under you in the profile sheet (M5, "maybe we can see some of the stats of my GitHub, how many
 * commits I've made, with nice graphs"): the contributions as a big number over the last half year's days, the commits,
 * pull requests, reviews and streak, and the year month by month. Nothing waits on a loading screen: the last numbers
 * show at once, and a first open holds the cards' places until they come. A finger on the days or the months reads
 * them out in the big number's place ("there's no interactivity ... I would like to interact with it somehow":
 * recording 16 Oct 13:06, 03:04 to 03:18; O7).
 */
@Composable
internal fun ColumnScope.ProfileStats(onLogin: (String) -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { Repo(context) }
    val stats = StatsMemory.load(context)
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(stats?.login) { stats?.login?.takeIf { it.isNotBlank() }?.let(onLogin) }
    // An answer with no calendar in it (an older server, an empty year) says so too, rather than leaving the cards' kept
    // places empty for good ("Profile shows no stats": recording 16 Oct 12:10, 07:34 to 07:59; N12).
    LaunchedEffect(Unit) { if (!StatsMemory.refresh(context, repo)) failed = StatsMemory.last == null }
    val c = LocalPullsColors.current
    SheetHeading("Last year on GitHub")
    val shown = stats
    if (shown == null && failed) {
        Text("Couldn't reach GitHub for your numbers.", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = PullsType.sm, color = c.muted)
        return
    }
    // The day or month a finger is on; the big number reads it out until the finger lifts or another is picked.
    var picked by remember { mutableStateOf<Picked?>(null) }
    SheetCard(gap = 6.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (number, words) = when (val p = picked) {
                    null -> (shown?.total?.let(::count) ?: " ") to "contributions"
                    is Picked.Day -> count(p.count) to "on ${p.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${p.date.dayOfMonth} ${p.date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
                    is Picked.Month -> count(p.count) to "in ${p.date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                }
                Text(number, style = PullsType.xxl.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = c.text)
                Text(words, Modifier.padding(bottom = 4.dp), style = PullsType.sm, color = c.muted)
            }
            DayGrid(shown, (picked as? Picked.Day)?.date) { picked = it }
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
        MonthBars(shown, (picked as? Picked.Month)?.date, Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp)) { picked = it }
    }
}

/** What a finger picked on the graphs: one day of the grid or one month's bar, and its count. */
internal sealed interface Picked {
    val date: LocalDate
    val count: Int

    data class Day(override val date: LocalDate, override val count: Int) : Picked
    data class Month(override val date: LocalDate, override val count: Int) : Picked
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
private fun DayGrid(stats: GithubStats?, selected: LocalDate?, onPick: (Picked.Day?) -> Unit) {
    val c = LocalPullsColors.current
    val days = stats?.days.orEmpty()
    // The grid starts on the Sunday GRID_WEEKS weeks back, so its columns line up with GitHub's.
    val start = (days.size - 1) / 7 * 7 - (GRID_WEEKS - 1) * 7
    val shown = if (start >= 0) days.drop(start) else emptyList()
    val busiest = shown.maxOrNull()?.coerceAtLeast(1) ?: 1
    val total = shown.sum()
    val latestPick by androidx.compose.runtime.rememberUpdatedState(onPick)
    // Which day sits under a point of the canvas, from the same cell size the drawing uses.
    fun dayAt(size: androidx.compose.ui.unit.IntSize, at: Offset, density: Float): Picked.Day? {
        if (stats == null || start < 0) return null
        val gap = 3f * density
        val cell = minOf((size.width - gap * (GRID_WEEKS - 1)) / GRID_WEEKS, (size.height - gap * 6) / 7)
        val left = (size.width - (cell * GRID_WEEKS + gap * (GRID_WEEKS - 1))) / 2
        val week = ((at.x - left) / (cell + gap)).toInt().coerceIn(0, GRID_WEEKS - 1)
        val day = (at.y / (cell + gap)).toInt().coerceIn(0, 6)
        val index = week * 7 + day
        val value = shown.getOrNull(index) ?: return null
        return Picked.Day(stats.firstDay.plusDays((start + index).toLong()), value)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var last by remember { mutableStateOf<LocalDate?>(null) }
    Canvas(
        Modifier.fillMaxWidth().height(116.dp)
            .semantics { contentDescription = if (stats == null) "Contributions, not loaded yet" else "$total contributions in the last $GRID_WEEKS weeks" }
            .pointerInput(stats) {
                // A tap reads one day out, a second tap on it lets it go; a finger moved over the grid reads each day it
                // crosses, with a tick as it goes from one to the next.
                detectTapGestures { at -> val day = dayAt(size, at, density); latestPick(if (day?.date == last) null else day); last = if (day?.date == last) null else day?.date }
            }
            .pointerInput(stats) {
                // Sideways only, so an up or down drag still scrolls the sheet.
                detectHorizontalDragGestures { change, _ ->
                    val day = dayAt(size, change.position, density) ?: return@detectHorizontalDragGestures
                    if (day.date != last) {
                        last = day.date
                        com.octaviantocan.pulls.sound.Haptics.threshold()
                        latestPick(day)
                    }
                }
            },
    ) {
        val gap = 3.dp.toPx()
        val cell = minOf((size.width - gap * (GRID_WEEKS - 1)) / GRID_WEEKS, (size.height - gap * 6) / 7)
        val left = (size.width - (cell * GRID_WEEKS + gap * (GRID_WEEKS - 1))) / 2
        val chosen = selected?.let { stats?.let { s -> java.time.temporal.ChronoUnit.DAYS.between(s.firstDay, it).toInt() - start } }
        for (week in 0 until GRID_WEEKS) for (day in 0 until 7) {
            val index = week * 7 + day
            val value = shown.getOrNull(index)
            if (stats != null && value == null) continue
            val level = if (value == null || value == 0) 0f else 0.25f + 0.75f * ((value - 1).toFloat() / busiest).coerceIn(0f, 1f)
            val topLeft = Offset(left + week * (cell + gap), day * (cell + gap))
            drawRoundRect(
                color = if (level == 0f) c.paper2 else c.accentTeal.copy(alpha = level),
                topLeft = topLeft,
                size = Size(cell, cell),
                cornerRadius = CornerRadius(cell * 0.25f),
            )
            // The picked day wears a ring in the ink, so it is found again at a glance.
            if (index == chosen) drawRoundRect(
                color = c.text,
                topLeft = topLeft - Offset(1.dp.toPx(), 1.dp.toPx()),
                size = Size(cell + 2.dp.toPx(), cell + 2.dp.toPx()),
                cornerRadius = CornerRadius(cell * 0.3f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
            )
        }
    }
}

/** The year month by month as bars, this month the last and in full teal, the others lighter, the month's letter under each. */
@Composable
private fun MonthBars(stats: GithubStats?, selected: LocalDate?, modifier: Modifier, onPick: (Picked.Month?) -> Unit) {
    val c = LocalPullsColors.current
    val months = stats?.months().orEmpty()
    val highest = months.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val latestPick by androidx.compose.runtime.rememberUpdatedState(onPick)
    fun monthAt(width: Int, x: Float): Picked.Month? {
        if (months.isEmpty()) return null
        val index = (x / width * 12).toInt().coerceIn(0, 11)
        return months.getOrNull(index)?.let { Picked.Month(it.first, it.second) }
    }
    var last by remember { mutableStateOf<LocalDate?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(72.dp)
                .semantics { contentDescription = months.joinToString { (month, total) -> "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} $total" } }
                .pointerInput(stats) {
                    detectTapGestures { at -> val month = monthAt(size.width, at.x); val again = month?.date == last; last = if (again) null else month?.date; latestPick(if (again) null else month) }
                }
                .pointerInput(stats) {
                    detectHorizontalDragGestures { change, _ ->
                        val month = monthAt(size.width, change.position.x) ?: return@detectHorizontalDragGestures
                        if (month.date != last) {
                            last = month.date
                            com.octaviantocan.pulls.sound.Haptics.threshold()
                            latestPick(month)
                        }
                    }
                },
        ) {
            val gap = 6.dp.toPx()
            val bar = (size.width - gap * 11) / 12
            for (index in 0 until 12) {
                val value = months.getOrNull(index)?.second
                val height = if (value == null) size.height * 0.3f else (size.height * value / highest).coerceAtLeast(3.dp.toPx())
                val isPicked = selected != null && months.getOrNull(index)?.first == selected
                drawRoundRect(
                    color = when {
                        value == null -> c.paper2
                        isPicked -> c.text
                        selected == null && index == 11 -> c.accentTeal
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
